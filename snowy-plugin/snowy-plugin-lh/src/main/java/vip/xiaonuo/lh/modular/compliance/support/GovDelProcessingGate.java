package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelRequest;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelTarget;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelRequestMapper;
import vip.xiaonuo.lh.modular.compliance.mapper.GovDelTargetMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * E7 处理门禁：ETL 补数命中已删分区须二次确认；出湖命中 restricted 直接拒绝。
 * <p>
 * 不引入抑制名单表（P1）；仅读 {@code gov_del_target} / {@code gov_del_request}。
 */
@Component
public class GovDelProcessingGate {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovDelTargetMapper targetMapper;
    @Resource
    private GovDelRequestMapper requestMapper;

    /**
     * 查已执行删除（status=done）且 scope 对齐 mark_key=mark_value 的命中项。
     */
    public List<Map<String, Object>> findDeletedPartitionHits(
            Collection<String> tableFqns, String markKey, String markValue) {
        if (tableFqns == null || tableFqns.isEmpty()
                || StrUtil.isBlank(markKey) || StrUtil.isBlank(markValue)) {
            return List.of();
        }
        List<GovDelTarget> done = targetMapper.selectList(new QueryWrapper<GovDelTarget>().lambda()
                .eq(GovDelTarget::getDeleteFlag, NOT_DELETE)
                .eq(GovDelTarget::getStatus, "done")
                .in(GovDelTarget::getCarrier, List.of("iceberg", "ck")));
        if (done.isEmpty()) {
            return List.of();
        }
        String scopeWanted = compactScope(markKey.trim() + "=" + markValue.trim());
        List<GovDelTarget> matched = new ArrayList<>();
        for (GovDelTarget t : done) {
            if (!anyTableMatch(tableFqns, t.getObjectFqn())) {
                continue;
            }
            if (!scopeMatches(t.getScopeExpr(), scopeWanted, markKey, markValue)) {
                continue;
            }
            matched.add(t);
        }
        return toHitMaps(matched);
    }

    /**
     * 命中已删分区且未回填合规请求号时抛错；confirmReqNo 须等于任一命中项的 req_no。
     */
    public List<Map<String, Object>> assertBackfillAllowed(
            Collection<String> tableFqns, String markKey, String markValue, String confirmReqNo) {
        List<Map<String, Object>> hits = findDeletedPartitionHits(tableFqns, markKey, markValue);
        if (hits.isEmpty()) {
            return hits;
        }
        Set<String> reqNos = hits.stream()
                .map(h -> StrUtil.blankToDefault((String) h.get("reqNo"), ""))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String confirm = StrUtil.trim(confirmReqNo);
        if (StrUtil.isNotBlank(confirm) && reqNos.stream().anyMatch(r -> r.equalsIgnoreCase(confirm))) {
            return hits;
        }
        String candidates = reqNos.isEmpty() ? "（无请求号）" : String.join("/", reqNos);
        String detail = hits.stream()
                .map(h -> h.get("objectFqn") + " scope=" + h.get("scopeExpr") + " req=" + h.get("reqNo"))
                .collect(Collectors.joining("; "));
        throw new CommonException(
                "补数命中已删分区，须二次确认：回填合规请求号 confirmReqNo（候选：" + candidates + "）。命中: " + detail);
    }

    /** 限制处理载体 / 工单命中时拒绝出湖。 */
    public void assertLakeExportAllowed(String exportTable) {
        if (StrUtil.isBlank(exportTable)) {
            return;
        }
        List<Map<String, Object>> hits = findRestrictedExportHits(exportTable);
        if (hits.isEmpty()) {
            return;
        }
        String detail = hits.stream()
                .map(h -> h.get("objectFqn") + " req=" + h.get("reqNo")
                        + (h.get("reviewAt") != null ? " review=" + h.get("reviewAt") : ""))
                .collect(Collectors.joining("; "));
        throw new CommonException(
                "出湖拒绝：源表命中合规限制处理（restricted），禁止再出域。命中: " + detail);
    }

    public List<Map<String, Object>> findRestrictedExportHits(String exportTable) {
        if (StrUtil.isBlank(exportTable)) {
            return List.of();
        }
        List<GovDelTarget> restrictedTargets = targetMapper.selectList(new QueryWrapper<GovDelTarget>().lambda()
                .eq(GovDelTarget::getDeleteFlag, NOT_DELETE)
                .eq(GovDelTarget::getStatus, "restricted"));
        List<GovDelRequest> restrictedReqs = requestMapper.selectList(new QueryWrapper<GovDelRequest>().lambda()
                .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                .eq(GovDelRequest::getStatus, "restricted"));
        Set<String> restrictedReqIds = restrictedReqs.stream()
                .map(GovDelRequest::getId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<GovDelTarget> fromRestrictedReq = List.of();
        if (!restrictedReqIds.isEmpty()) {
            fromRestrictedReq = targetMapper.selectList(new QueryWrapper<GovDelTarget>().lambda()
                    .eq(GovDelTarget::getDeleteFlag, NOT_DELETE)
                    .in(GovDelTarget::getReqId, restrictedReqIds)
                    .ne(GovDelTarget::getStatus, "excluded"));
        }
        List<GovDelTarget> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GovDelTarget t : restrictedTargets) {
            if (t.getId() != null && seen.add(t.getId())) {
                all.add(t);
            }
        }
        for (GovDelTarget t : fromRestrictedReq) {
            if (t.getId() != null && seen.add(t.getId())) {
                all.add(t);
            }
        }
        List<GovDelTarget> matched = all.stream()
                .filter(t -> tablesMatch(exportTable, t.getObjectFqn()))
                .collect(Collectors.toList());
        return toHitMaps(matched);
    }

    public Map<String, Object> backfillCheck(
            Collection<String> tableFqns, String markKey, String markValue) {
        List<Map<String, Object>> hits = findDeletedPartitionHits(tableFqns, markKey, markValue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocked", !hits.isEmpty());
        out.put("hits", hits);
        out.put("markKey", markKey);
        out.put("markValue", markValue);
        out.put("suggestConfirmReqNos", hits.stream()
                .map(h -> (String) h.get("reqNo"))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList()));
        return out;
    }

    public Map<String, Object> exportCheck(String exportTable) {
        List<Map<String, Object>> hits = findRestrictedExportHits(exportTable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocked", !hits.isEmpty());
        out.put("hits", hits);
        out.put("exportTable", exportTable);
        return out;
    }

    private List<Map<String, Object>> toHitMaps(List<GovDelTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Set<String> reqIds = targets.stream()
                .map(GovDelTarget::getReqId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, GovDelRequest> reqById = new LinkedHashMap<>();
        if (!reqIds.isEmpty()) {
            List<GovDelRequest> reqs = requestMapper.selectList(new QueryWrapper<GovDelRequest>().lambda()
                    .eq(GovDelRequest::getDeleteFlag, NOT_DELETE)
                    .in(GovDelRequest::getId, reqIds));
            for (GovDelRequest r : reqs) {
                reqById.put(r.getId(), r);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovDelTarget t : targets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetId", t.getId());
            m.put("reqId", t.getReqId());
            GovDelRequest req = reqById.get(t.getReqId());
            m.put("reqNo", req != null ? req.getReqNo() : null);
            m.put("objectFqn", t.getObjectFqn());
            m.put("scopeExpr", t.getScopeExpr());
            m.put("carrier", t.getCarrier());
            m.put("mode", t.getMode());
            m.put("status", t.getStatus());
            m.put("reviewAt", t.getReviewAt());
            out.add(m);
        }
        return out;
    }

    static boolean anyTableMatch(Collection<String> candidates, String objectFqn) {
        if (StrUtil.isBlank(objectFqn) || candidates == null) {
            return false;
        }
        for (String c : candidates) {
            if (tablesMatch(c, objectFqn)) {
                return true;
            }
        }
        return false;
    }

    /** 取末两段（schema.table），兼容 catalog.schema.table。 */
    public static String tableTail(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return "";
        }
        String n = fqn.trim().toLowerCase(Locale.ROOT);
        String[] p = n.split("\\.");
        if (p.length >= 2) {
            return p[p.length - 2] + "." + p[p.length - 1];
        }
        return n;
    }

    public static boolean tablesMatch(String a, String b) {
        String ta = tableTail(a);
        String tb = tableTail(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        if (ta.equals(tb)) {
            return true;
        }
        String la = StrUtil.blankToDefault(a, "").trim().toLowerCase(Locale.ROOT);
        String lb = StrUtil.blankToDefault(b, "").trim().toLowerCase(Locale.ROOT);
        return la.endsWith("." + tb) || lb.endsWith("." + ta) || la.equals(tb) || lb.equals(ta);
    }

    public static String compactScope(String scope) {
        if (scope == null) {
            return "";
        }
        return scope.trim().replaceAll("\\s*=\\s*", "=").replaceAll("\\s+", "");
    }

    static boolean scopeMatches(String scopeExpr, String scopeWantedCompact,
                                String markKey, String markValue) {
        if (StrUtil.isBlank(scopeExpr)) {
            return false;
        }
        String compact = compactScope(scopeExpr);
        if (compact.equalsIgnoreCase(scopeWantedCompact)) {
            return true;
        }
        // 精确 key=value（忽略大小写 key）
        String exact = compactScope(markKey + "=" + markValue);
        return compact.equalsIgnoreCase(exact);
    }
}
