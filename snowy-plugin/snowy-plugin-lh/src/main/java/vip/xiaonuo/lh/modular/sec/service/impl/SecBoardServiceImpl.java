package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.export.entity.GovExportAudit;
import vip.xiaonuo.lh.modular.export.mapper.GovExportAuditMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.entity.SecMaskPolicyProj;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.mapper.SecMaskPolicyProjMapper;
import vip.xiaonuo.lh.modular.sec.service.SecBoardService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SecBoardServiceImpl implements SecBoardService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private SecMaskPolicyProjMapper maskMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovExportAuditMapper exportAuditMapper;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        Date dayAgo = hoursAgo(24);

        long activeGrants = grantMapper.selectCount(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(StrUtil.isNotBlank(workspace), SecAuthGrant::getWs, workspace));

        long sensitiveCols = maskMapper.selectCount(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecMaskPolicyProj::getWs, workspace));

        long sensitiveAssets = assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovAsset::getWs, workspace)
                .isNotNull(GovAsset::getSensitivity)
                .ne(GovAsset::getSensitivity, "")
                .ne(GovAsset::getSensitivity, "公开")
                .ne(GovAsset::getSensitivity, "PUBLIC"));

        long audit24h = exportAuditMapper.selectCount(new QueryWrapper<GovExportAudit>().lambda()
                .eq(GovExportAudit::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovExportAudit::getWs, workspace)
                .ge(GovExportAudit::getEventTime, dayAgo));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("userCount", null);
        r.put("activeGrants", activeGrants);
        r.put("sensitiveColumns", sensitiveCols);
        r.put("sensitiveAssets", sensitiveAssets);
        r.put("auditLast24h", audit24h);
        r.put("source", "portal");
        return r;
    }

    @Override
    public Map<String, Object> pageGrants(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<SecAuthGrant> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecAuthGrant::getWs, workspace)
                .orderByDesc(SecAuthGrant::getCreateTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(SecAuthGrant::getSubjectId, kw)
                    .or().like(SecAuthGrant::getResourceId, kw)
                    .or().like(SecAuthGrant::getAssetId, kw)
                    .or().like(SecAuthGrant::getPrivilege, kw)
                    .or().like(SecAuthGrant::getTicketId, kw));
        }
        Page<SecAuthGrant> page = grantMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (SecAuthGrant g : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("ws", g.getWs());
            row.put("status", g.getStatus());
            row.put("subjectType", g.getSubjectType());
            row.put("subjectId", g.getSubjectId());
            row.put("resourceType", g.getResourceType());
            row.put("resourceId", firstNonBlank(g.getResourceId(), g.getAssetId()));
            row.put("assetId", g.getAssetId());
            row.put("privilege", g.getPrivilege());
            row.put("columnMask", g.getColumnMask());
            row.put("rowFilter", g.getRowFilter());
            row.put("ticketId", g.getTicketId());
            row.put("gravProjected", g.getGravProjected());
            row.put("expiresAt", g.getExpiresAt());
            row.put("createTime", g.getCreateTime());
            records.add(row);
        }
        return pageMap(records, page.getTotal(), cur, sz);
    }

    @Override
    public Map<String, Object> pageMasks(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<SecMaskPolicyProj> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecMaskPolicyProj::getWs, workspace)
                .orderByDesc(SecMaskPolicyProj::getUpdateTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(SecMaskPolicyProj::getGravAssetId, kw)
                    .or().like(SecMaskPolicyProj::getColumnName, kw)
                    .or().like(SecMaskPolicyProj::getMaskAlgo, kw)
                    .or().like(SecMaskPolicyProj::getSensitivity, kw));
        }
        Page<SecMaskPolicyProj> page = maskMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (SecMaskPolicyProj m : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("ws", m.getWs());
            row.put("status", m.getStatus());
            row.put("gravAssetId", m.getGravAssetId());
            row.put("columnName", m.getColumnName());
            row.put("sensitivity", m.getSensitivity());
            row.put("maskAlgo", m.getMaskAlgo());
            row.put("omTagFqn", m.getOmTagFqn());
            row.put("gravPolicyId", m.getGravPolicyId());
            row.put("updateTime", m.getUpdateTime());
            records.add(row);
        }
        return pageMap(records, page.getTotal(), cur, sz);
    }

    @Override
    public Map<String, Object> classification(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<GovAsset> assets = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovAsset::getWs, workspace)
                .select(GovAsset::getSensitivity));
        Map<String, Long> buckets = new LinkedHashMap<>();
        long total = 0;
        for (GovAsset a : assets) {
            String s = StrUtil.blankToDefault(a.getSensitivity(), "未分级").trim();
            if (s.isEmpty()) {
                s = "未分级";
            }
            buckets.merge(s, 1L, Long::sum);
            total++;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : buckets.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sensitivity", e.getKey());
            item.put("count", e.getValue());
            items.add(item);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("total", total);
        r.put("items", items);
        r.put("source", "gov_asset");
        return r;
    }

    @Override
    public Map<String, Object> pageAudit(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<GovExportAudit> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovExportAudit::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovExportAudit::getWs, workspace)
                .orderByDesc(GovExportAudit::getEventTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovExportAudit::getTicketNo, kw)
                    .or().like(GovExportAudit::getSrcTable, kw)
                    .or().like(GovExportAudit::getTarget, kw)
                    .or().like(GovExportAudit::getEventType, kw)
                    .or().like(GovExportAudit::getApprover, kw));
        }
        Page<GovExportAudit> page = exportAuditMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (GovExportAudit a : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("time", a.getEventTime());
            row.put("eventType", a.getEventType());
            row.put("ticketNo", a.getTicketNo());
            row.put("src", a.getSrcTable());
            row.put("target", a.getTarget());
            row.put("approver", a.getApprover());
            row.put("purpose", a.getPurpose());
            row.put("channel", "export");
            row.put("risk", riskOf(a.getEventType()));
            records.add(row);
        }
        Map<String, Object> r = pageMap(records, page.getTotal(), cur, sz);
        r.put("source", records.isEmpty() ? "empty" : "gov_export_audit");
        r.put("hint", records.isEmpty()
                ? "暂无高风险审计片段（出湖审计空；即席审计待扩展）"
                : "当前拼接出湖审计；即席查询审计可后续并入");
        return r;
    }

    @Override
    public Map<String, Object> listSa() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "none");
        r.put("items", List.of());
        r.put("hint", "作业 SA 台账表 P1 再建；当前合法空列表");
        return r;
    }

    @Override
    public Map<String, Object> vaultHealth() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "none");
        r.put("items", List.of());
        r.put("ok", true);
        r.put("hint", "Vault 轮换台账 P1；当前合法空列表");
        return r;
    }

    @Override
    public Map<String, Object> routeWhitelist() {
        List<Map<String, Object>> allow = List.of(
                pathNode("人 / BI", "OIDC → 门户"),
                pathNode("Trino", "脱敏 + 配额"),
                pathNode("Iceberg 读", "按授权列"));
        List<Map<String, Object>> forbid = List.of(
                pathNode("人", "直连源库/CK/MinIO"),
                pathNode("阻断", "安全组 + 防火墙"),
                pathNode("审计", "记录尝试"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("allow", allow);
        r.put("forbid", forbid);
        r.put("source", "policy");
        return r;
    }

    private static Map<String, Object> pathNode(String title, String sub) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("title", title);
        n.put("sub", sub);
        return n;
    }

    private static String riskOf(String eventType) {
        String t = StrUtil.blankToDefault(eventType, "").toLowerCase(Locale.ROOT);
        if (t.contains("expire") || t.contains("purge") || t.contains("reject")) {
            return "high";
        }
        if (t.contains("approved") || t.contains("job")) {
            return "medium";
        }
        return "low";
    }

    private static Date hoursAgo(int hours) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, -hours);
        return c.getTime();
    }

    private static Map<String, Object> pageMap(List<Map<String, Object>> records, long total, long current, long size) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", records);
        r.put("total", total);
        r.put("current", current);
        r.put("size", size);
        return r;
    }

    private static String firstNonBlank(String a, String b) {
        if (StrUtil.isNotBlank(a)) {
            return a.trim();
        }
        if (StrUtil.isNotBlank(b)) {
            return b.trim();
        }
        return null;
    }
}
