package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.ai.LhMilvusClient;
import vip.xiaonuo.lh.modular.ai.entity.GovAiSession;
import vip.xiaonuo.lh.modular.ai.entity.GovAiTurn;
import vip.xiaonuo.lh.modular.ai.mapper.GovAiSessionMapper;
import vip.xiaonuo.lh.modular.ai.mapper.GovAiTurnMapper;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbChunk;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbEntry;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbChunkMapper;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbEntryMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 合规 AI / 知识库按主体擦除：对话轮次命中主体明文 → 会话软删；
 * 知识条目/分片命中 → 删 chunk + Milvus（soft-fail）+ 条目软删。
 */
@Slf4j
@Component
public class GovDelAiKbPurgeSupport {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String REDACTED = "[compliance-redacted]";
    private static final int MAX_SCAN = 200;

    @Resource
    private GovAiSessionMapper sessionMapper;
    @Resource
    private GovAiTurnMapper turnMapper;
    @Resource
    private GovKbEntryMapper entryMapper;
    @Resource
    private GovKbChunkMapper chunkMapper;
    @Resource
    private LhMilvusClient milvusClient;

    public Map<String, Object> purge(String ws, String objectFqn, String subjectIdHash, String subjectPlain) {
        String fqn = StrUtil.blankToDefault(objectFqn, "").trim().toLowerCase(Locale.ROOT);
        if (fqn.contains("kb") || fqn.contains("knowledge") || fqn.contains("chunk") || fqn.contains("entry")) {
            return purgeKnowledge(ws, subjectPlain);
        }
        if (fqn.contains("ai") || fqn.contains("session") || fqn.contains("chat") || fqn.contains("turn")
                || fqn.isEmpty()) {
            Map<String, Object> ai = purgeAiChat(ws, subjectPlain);
            // 未指定对象时顺带扫知识库
            if (fqn.isEmpty() || "*".equals(fqn)) {
                Map<String, Object> kb = purgeKnowledge(ws, subjectPlain);
                Map<String, Object> out = new LinkedHashMap<>(ai);
                out.put("kb", kb);
                out.put("ok", Boolean.TRUE.equals(ai.get("ok")) && Boolean.TRUE.equals(kb.get("ok")));
                return out;
            }
            return ai;
        }
        // 默认双扫
        Map<String, Object> ai = purgeAiChat(ws, subjectPlain);
        Map<String, Object> kb = purgeKnowledge(ws, subjectPlain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ai", ai);
        out.put("kb", kb);
        out.put("ok", Boolean.TRUE.equals(ai.get("ok")) && Boolean.TRUE.equals(kb.get("ok")));
        out.put("subjectIdHash", subjectIdHash);
        return out;
    }

    public Map<String, Object> purgeAiChat(String ws, String subjectPlain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessions", 0);
        out.put("turnsRedacted", 0);
        if (StrUtil.isBlank(subjectPlain)) {
            out.put("ok", false);
            out.put("message", "无主体明文，跳过对话擦除（Vault 不可用或未登记）");
            return out;
        }
        String needle = subjectPlain.trim();
        Set<String> sessionIds = new LinkedHashSet<>();

        // 1) 会话 userId == 主体（账号注销类）
        List<GovAiSession> byUser = sessionMapper.selectList(new QueryWrapper<GovAiSession>().lambda()
                .eq(GovAiSession::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(ws), GovAiSession::getWs, ws)
                .eq(GovAiSession::getUserId, needle)
                .last("LIMIT " + MAX_SCAN));
        for (GovAiSession s : byUser) {
            sessionIds.add(s.getId());
        }

        // 2) 轮次内容命中主体明文
        List<GovAiTurn> hitTurns = turnMapper.selectList(new QueryWrapper<GovAiTurn>().lambda()
                .like(GovAiTurn::getContent, needle)
                .last("LIMIT " + MAX_SCAN));
        for (GovAiTurn t : hitTurns) {
            if (StrUtil.isNotBlank(t.getSessionId())) {
                sessionIds.add(t.getSessionId());
            }
        }

        Date now = new Date();
        int turnsRedacted = 0;
        int sessions = 0;
        for (String sid : sessionIds) {
            GovAiSession s = sessionMapper.selectById(sid);
            if (s == null || !NOT_DELETE.equals(StrUtil.blankToDefault(s.getDeleteFlag(), NOT_DELETE))) {
                continue;
            }
            if (StrUtil.isNotBlank(ws) && StrUtil.isNotBlank(s.getWs()) && !ws.equals(s.getWs())) {
                continue;
            }
            List<GovAiTurn> turns = turnMapper.selectList(new QueryWrapper<GovAiTurn>().lambda()
                    .eq(GovAiTurn::getSessionId, sid));
            for (GovAiTurn t : turns) {
                if (StrUtil.isNotBlank(t.getContent()) && !REDACTED.equals(t.getContent())) {
                    t.setContent(REDACTED);
                    t.setCitationsJson(null);
                    turnMapper.updateById(t);
                    turnsRedacted++;
                }
            }
            s.setStatus("deleted");
            s.setTitle(StrUtil.blankToDefault(s.getTitle(), "session") + " [compliance]");
            s.setRemark(StrUtil.maxLength(
                    StrUtil.blankToDefault(s.getRemark(), "") + " compliance purge", 500));
            s.setRevision(s.getRevision() == null ? 1 : s.getRevision() + 1);
            s.setUpdateTime(now);
            sessionMapper.updateById(s);
            try {
                sessionMapper.deleteById(sid);
            } catch (Exception e) {
                log.warn("ai session soft-delete soft-fail {}: {}", sid, e.getMessage());
            }
            sessions++;
        }
        out.put("sessions", sessions);
        out.put("turnsRedacted", turnsRedacted);
        out.put("message", "对话擦除 sessions=" + sessions + " turns=" + turnsRedacted);
        return out;
    }

    public Map<String, Object> purgeKnowledge(String ws, String subjectPlain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("entries", 0);
        out.put("chunks", 0);
        out.put("milvusDeleted", 0);
        out.put("milvusFailed", 0);
        if (StrUtil.isBlank(subjectPlain)) {
            out.put("ok", false);
            out.put("message", "无主体明文，跳过知识库擦除");
            return out;
        }
        String needle = subjectPlain.trim();
        Set<String> entryIds = new LinkedHashSet<>();

        List<GovKbEntry> byBody = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(ws), GovKbEntry::getWs, ws)
                .and(w -> w.like(GovKbEntry::getTitle, needle).or().like(GovKbEntry::getBody, needle))
                .last("LIMIT " + MAX_SCAN));
        for (GovKbEntry e : byBody) {
            entryIds.add(e.getId());
        }

        List<GovKbChunk> byChunk = chunkMapper.selectList(new QueryWrapper<GovKbChunk>().lambda()
                .like(GovKbChunk::getTextContent, needle)
                .last("LIMIT " + MAX_SCAN));
        for (GovKbChunk c : byChunk) {
            if (StrUtil.isNotBlank(c.getEntryId())) {
                entryIds.add(c.getEntryId());
            }
        }

        int entries = 0;
        int chunks = 0;
        int milvusOk = 0;
        int milvusFail = 0;
        List<String> notes = new ArrayList<>();
        Date now = new Date();
        for (String eid : entryIds) {
            GovKbEntry row = entryMapper.selectById(eid);
            if (row == null || !NOT_DELETE.equals(StrUtil.blankToDefault(row.getDeleteFlag(), NOT_DELETE))) {
                continue;
            }
            if (StrUtil.isNotBlank(ws) && StrUtil.isNotBlank(row.getWs()) && !ws.equals(row.getWs())
                    && !"platform".equalsIgnoreCase(row.getScope())) {
                continue;
            }
            Long cnt = chunkMapper.selectCount(new QueryWrapper<GovKbChunk>().lambda()
                    .eq(GovKbChunk::getEntryId, eid));
            chunks += cnt == null ? 0 : cnt.intValue();
            chunkMapper.delete(new QueryWrapper<GovKbChunk>().lambda().eq(GovKbChunk::getEntryId, eid));
            try {
                if (milvusClient.deleteByEntryId(eid)) {
                    milvusOk++;
                } else {
                    milvusFail++;
                    notes.add("milvus miss " + eid);
                }
            } catch (Exception e) {
                milvusFail++;
                notes.add("milvus soft-fail " + eid + ": " + StrUtil.maxLength(e.getMessage(), 80));
                log.warn("compliance milvus delete soft-fail entry={}: {}", eid, e.getMessage());
            }
            row.setBody(REDACTED);
            row.setTitle(StrUtil.blankToDefault(row.getTitle(), "entry") + " [compliance]");
            row.setStatus("deleted");
            row.setDeleteFlag("DELETED");
            row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
            row.setUpdateTime(now);
            entryMapper.updateById(row);
            entries++;
        }
        out.put("entries", entries);
        out.put("chunks", chunks);
        out.put("milvusDeleted", milvusOk);
        out.put("milvusFailed", milvusFail);
        out.put("notes", notes);
        out.put("ok", milvusFail == 0 || entries > 0);
        out.put("message", "知识库擦除 entries=" + entries + " chunks=" + chunks
                + " milvusOk=" + milvusOk + " milvusFail=" + milvusFail);
        return out;
    }
}
