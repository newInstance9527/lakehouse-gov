package vip.xiaonuo.lh.modular.metric.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricDim;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricQualifier;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricDimMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricQualifierMapper;
import vip.xiaonuo.lh.modular.metric.param.GovMetricDimUpsertParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQualifierUpsertParam;
import vip.xiaonuo.lh.modular.metric.service.GovMetricOntologyService;
import vip.xiaonuo.lh.modular.metric.support.MetricDimLayerSupport;
import vip.xiaonuo.lh.modular.metric.support.MetricParamBinder;
import vip.xiaonuo.lh.modular.metric.support.MetricQualifierDsl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class GovMetricOntologyServiceImpl implements GovMetricOntologyService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    private static final Pattern CODE_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{1,62}$");
    private static final Pattern COL_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Resource
    private GovMetricQualifierMapper qualifierMapper;
    @Resource
    private GovMetricDimMapper dimMapper;
    @Resource
    private TrinoClient trinoClient;

    @Override
    public List<Map<String, Object>> listQualifiers(String ws, String status, String domain, String q) {
        String home = StrUtil.blankToDefault(ws, WS_DEFAULT);
        QueryWrapper<GovMetricQualifier> qw = new QueryWrapper<GovMetricQualifier>()
                .eq("delete_flag", NOT_DELETE)
                .eq("ws", home)
                .orderByAsc("code");
        if (StrUtil.isNotBlank(status)) {
            qw.eq("status", status.trim());
        }
        if (StrUtil.isNotBlank(domain)) {
            qw.and(w -> w.eq("domain_code", domain.trim()).or().isNull("domain_code").or().eq("domain_code", ""));
        }
        if (StrUtil.isNotBlank(q)) {
            String like = "%" + q.trim() + "%";
            qw.and(w -> w.like("code", like).or().like("name", like).or().like("expr_dsl", like));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovMetricQualifier row : qualifierMapper.selectList(qw)) {
            out.add(toQualifierVo(row));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> qualifierOptions(String ws, String domain, String tableHint) {
        List<Map<String, Object>> list = listQualifiers(ws, "active", domain, null);
        List<Map<String, Object>> opts = new ArrayList<>();
        String table = StrUtil.blankToDefault(tableHint, "").toLowerCase(Locale.ROOT);
        for (Map<String, Object> row : list) {
            String pattern = StrUtil.blankToDefault(String.valueOf(row.getOrDefault("tablePattern", "")), "");
            if (StrUtil.isNotBlank(pattern) && StrUtil.isNotBlank(table)) {
                String regex = pattern.replace("%", ".*").replace("_", ".");
                if (!table.matches("(?i)" + regex) && !table.contains(pattern.replace("%", "").toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            Map<String, Object> o = new LinkedHashMap<>();
            // value = 编译 SQL 快照，写入 ver.qualifier_json
            o.put("value", row.get("exprSql"));
            o.put("label", row.get("name"));
            o.put("name", row.get("name"));
            o.put("code", row.get("code"));
            o.put("sub", row.get("code") + " · " + row.get("exprDsl"));
            o.put("group", StrUtil.blankToDefault(String.valueOf(row.get("domainCode")), "通用"));
            opts.add(o);
        }
        return opts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upsertQualifier(GovMetricQualifierUpsertParam param, boolean create) {
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String code = param.getCode().trim().toUpperCase(Locale.ROOT);
        if (!CODE_RE.matcher(code).matches()) {
            throw new CommonException("修饰词 code 非法");
        }
        String sql;
        try {
            sql = MetricQualifierDsl.compile(param.getExprDsl());
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        GovMetricQualifier existing = qualifierMapper.selectOne(new QueryWrapper<GovMetricQualifier>().lambda()
                .eq(GovMetricQualifier::getWs, ws)
                .eq(GovMetricQualifier::getCode, code)
                .eq(GovMetricQualifier::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        Date now = new Date();
        if (create) {
            if (existing != null) {
                throw new CommonException("修饰词已存在: " + code);
            }
            GovMetricQualifier row = new GovMetricQualifier();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setStatus(StrUtil.blankToDefault(param.getStatus(), "active"));
            row.setWs(ws);
            row.setRemark(param.getRemark());
            row.setCode(code);
            row.setName(param.getName().trim());
            row.setDomainCode(blankToNull(param.getDomainCode()));
            row.setTablePattern(blankToNull(param.getTablePattern()));
            row.setExprDsl(param.getExprDsl().trim());
            row.setExprSql(sql);
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            qualifierMapper.insert(row);
            return toQualifierVo(row);
        }
        if (existing == null) {
            throw new CommonException("修饰词不存在: " + code);
        }
        existing.setName(param.getName().trim());
        existing.setDomainCode(blankToNull(param.getDomainCode()));
        existing.setTablePattern(blankToNull(param.getTablePattern()));
        existing.setExprDsl(param.getExprDsl().trim());
        existing.setExprSql(sql);
        existing.setRemark(param.getRemark());
        if (StrUtil.isNotBlank(param.getStatus())) {
            existing.setStatus(param.getStatus().trim());
        }
        existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
        existing.setUpdateTime(now);
        qualifierMapper.updateById(existing);
        return toQualifierVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disableQualifier(String code, String ws) {
        GovMetricQualifier row = requireQualifier(code, ws);
        row.setStatus("disabled");
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        row.setUpdateTime(new Date());
        qualifierMapper.updateById(row);
        return toQualifierVo(row);
    }

    @Override
    public List<Map<String, Object>> listDims(String ws, String status, String domain, String q) {
        String home = StrUtil.blankToDefault(ws, WS_DEFAULT);
        QueryWrapper<GovMetricDim> qw = new QueryWrapper<GovMetricDim>()
                .eq("delete_flag", NOT_DELETE)
                .eq("ws", home)
                .orderByAsc("code");
        if (StrUtil.isNotBlank(status)) {
            qw.eq("status", status.trim());
        }
        if (StrUtil.isNotBlank(domain)) {
            qw.and(w -> w.eq("domain_code", domain.trim()).or().isNull("domain_code").or().eq("domain_code", ""));
        }
        if (StrUtil.isNotBlank(q)) {
            String like = "%" + q.trim() + "%";
            qw.and(w -> w.like("code", like).or().like("name", like).or().like("column_name", like));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovMetricDim row : dimMapper.selectList(qw)) {
            out.add(toDimVo(row));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> dimOptions(String ws, String domain) {
        List<Map<String, Object>> list = listDims(ws, "active", domain, null);
        List<Map<String, Object>> opts = new ArrayList<>();
        for (Map<String, Object> row : list) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", row.get("columnName"));
            o.put("label", row.get("name"));
            o.put("name", row.get("name"));
            o.put("code", row.get("code"));
            o.put("type", row.get("grainRole"));
            String fact = row.get("factTable") == null ? "" : String.valueOf(row.get("factTable"));
            o.put("sub", row.get("code") + " · " + row.get("columnName")
                    + (StrUtil.isBlank(fact) ? "" : " · " + fact));
            o.put("group", "主数据");
            o.put("factTable", row.get("factTable"));
            o.put("bizProcess", row.get("bizProcess"));
            o.put("dimTable", row.get("dimTable"));
            o.put("memberCodeCol", row.get("memberCodeCol"));
            o.put("memberNameCol", row.get("memberNameCol"));
            o.put("parentCodeCol", row.get("parentCodeCol"));
            o.put("dimStatus", row.get("dimStatus"));
            opts.add(o);
        }
        return opts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upsertDim(GovMetricDimUpsertParam param, boolean create) {
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String code = param.getCode().trim().toUpperCase(Locale.ROOT);
        if (!CODE_RE.matcher(code).matches()) {
            throw new CommonException("维度 code 非法");
        }
        String col = param.getColumnName().trim();
        if (!COL_RE.matcher(col).matches()) {
            throw new CommonException("columnName 非法");
        }
        String role = StrUtil.blankToDefault(param.getGrainRole(), "biz").trim().toLowerCase(Locale.ROOT);
        if (!"time".equals(role) && !"biz".equals(role)) {
            throw new CommonException("grainRole 仅支持 time / biz");
        }
        applyDimLayerFields(param, col);
        GovMetricDim existing = dimMapper.selectOne(new QueryWrapper<GovMetricDim>().lambda()
                .eq(GovMetricDim::getWs, ws)
                .eq(GovMetricDim::getCode, code)
                .eq(GovMetricDim::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        Date now = new Date();
        if (create) {
            if (existing != null) {
                throw new CommonException("维度已存在: " + code);
            }
            GovMetricDim row = new GovMetricDim();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setStatus(StrUtil.blankToDefault(param.getStatus(), "active"));
            row.setWs(ws);
            row.setRemark(param.getRemark());
            row.setCode(code);
            row.setName(param.getName().trim());
            row.setDomainCode(blankToNull(param.getDomainCode()));
            row.setColumnName(col);
            row.setGrainRole(role);
            row.setFactTable(blankToNull(param.getFactTable()));
            row.setBizProcess(blankToNull(param.getBizProcess()));
            fillDimLayerOnRow(row, param, col);
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            dimMapper.insert(row);
            return toDimVo(row);
        }
        if (existing == null) {
            throw new CommonException("维度不存在: " + code);
        }
        existing.setName(param.getName().trim());
        existing.setDomainCode(blankToNull(param.getDomainCode()));
        existing.setColumnName(col);
        existing.setGrainRole(role);
        if (param.getFactTable() != null) {
            existing.setFactTable(blankToNull(param.getFactTable()));
        }
        if (param.getBizProcess() != null) {
            existing.setBizProcess(blankToNull(param.getBizProcess()));
        }
        if (param.getDimTable() != null
                || param.getMemberCodeCol() != null
                || param.getMemberNameCol() != null
                || param.getParentCodeCol() != null) {
            fillDimLayerOnRow(existing, param, col);
        }
        existing.setRemark(param.getRemark());
        if (StrUtil.isNotBlank(param.getStatus())) {
            existing.setStatus(param.getStatus().trim());
        }
        existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
        existing.setUpdateTime(now);
        dimMapper.updateById(existing);
        return toDimVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disableDim(String code, String ws) {
        GovMetricDim row = requireDim(code, ws);
        row.setStatus("disabled");
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        row.setUpdateTime(new Date());
        dimMapper.updateById(row);
        return toDimVo(row);
    }

    @Override
    public Map<String, Object> listDimMembers(String code, String ws, String q, Integer limit) {
        GovMetricDim row = requireDim(code, ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", row.getCode());
        out.put("columnName", row.getColumnName());
        out.put("dimTable", row.getDimTable());
        out.put("dimStatus", row.getDimStatus());
        if (StrUtil.isBlank(row.getDimTable()) || !"bound".equalsIgnoreCase(
                StrUtil.blankToDefault(row.getDimStatus(), "unbound"))) {
            out.put("members", List.of());
            out.put("tree", List.of());
            out.put("message", "请先绑定 DIM 层表 dim_table（如 iceberg.dim.dim_channel）");
            return out;
        }
        String codeCol = MetricDimLayerSupport.defaultCodeCol(row.getColumnName(), row.getMemberCodeCol());
        String nameCol = MetricDimLayerSupport.defaultNameCol(row.getMemberNameCol());
        int lim = limit == null ? 200 : limit;
        String sql;
        try {
            sql = MetricDimLayerSupport.membersSql(
                    row.getDimTable(), codeCol, nameCol, row.getParentCodeCol(), q, lim);
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        Map<String, Object> exec = trinoClient.execute(sql, TrinoClient.ExecuteOptions.job(lim));
        if (Boolean.TRUE.equals(exec.get("degraded"))) {
            out.put("members", List.of());
            out.put("tree", List.of());
            out.put("message", String.valueOf(exec.getOrDefault("message", "Trino 未配置")));
            out.put("degraded", true);
            return out;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = exec.get("rows") instanceof List<?> l
                ? (List<Map<String, Object>>) l
                : List.of();
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", strCell(r, "code"));
            m.put("name", strCell(r, "name"));
            Object parent = r.get("parent_code");
            if (parent == null) {
                parent = r.get("parentCode");
            }
            if (parent != null && StrUtil.isNotBlank(String.valueOf(parent))) {
                m.put("parentCode", String.valueOf(parent));
            }
            members.add(m);
        }
        out.put("members", members);
        out.put("tree", buildMemberTree(members));
        out.put("rowCount", members.size());
        return out;
    }

    /** 由 parentCode 组树；无父子关系时返回空树（前端用扁平 members） */
    private static List<Map<String, Object>> buildMemberTree(List<Map<String, Object>> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        boolean anyParent = members.stream().anyMatch(m ->
                m.get("parentCode") != null && StrUtil.isNotBlank(String.valueOf(m.get("parentCode"))));
        if (!anyParent) {
            return List.of();
        }
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Map<String, Object> m : members) {
            String code = String.valueOf(m.getOrDefault("code", "")).trim();
            if (StrUtil.isBlank(code)) {
                continue;
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", code);
            n.put("name", m.get("name"));
            n.put("parentCode", m.get("parentCode"));
            n.put("children", new ArrayList<Map<String, Object>>());
            nodes.put(code, n);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : nodes.values()) {
            Object pc = n.get("parentCode");
            String parent = pc == null ? "" : String.valueOf(pc).trim();
            if (StrUtil.isNotBlank(parent) && nodes.containsKey(parent) && !parent.equals(n.get("code"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> ch = (List<Map<String, Object>>) nodes.get(parent).get("children");
                ch.add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }

    @Override
    public void assertDimMembers(Map<String, Object> params, List<String> grainKeys, String ws) {
        if (params == null || grainKeys == null || grainKeys.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = params.get("dims") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        if (dims.isEmpty()) {
            return;
        }
        String home = StrUtil.blankToDefault(ws, WS_DEFAULT);
        for (String grain : grainKeys) {
            if (StrUtil.isBlank(grain)) {
                continue;
            }
            Object raw = dims.get(grain);
            if (raw == null) {
                raw = params.get(grain);
            }
            List<String> members = MetricParamBinder.normalizeDimValues(raw);
            if (members.isEmpty()) {
                continue; // 全国 / 全量：不传维键
            }
            for (String member : members) {
                if (!MetricDimLayerSupport.isSafeMember(member)) {
                    throw new CommonException("非法维度值: " + grain);
                }
            }
            GovMetricDim dim = dimMapper.selectOne(new QueryWrapper<GovMetricDim>().lambda()
                    .eq(GovMetricDim::getWs, home)
                    .eq(GovMetricDim::getColumnName, grain)
                    .eq(GovMetricDim::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetricDim::getStatus, "active")
                    .last("LIMIT 1"));
            if (dim == null || StrUtil.isBlank(dim.getDimTable())
                    || !"bound".equalsIgnoreCase(StrUtil.blankToDefault(dim.getDimStatus(), ""))) {
                continue;
            }
            String codeCol = MetricDimLayerSupport.defaultCodeCol(dim.getColumnName(), dim.getMemberCodeCol());
            for (String member : members) {
                String sql;
                try {
                    sql = MetricDimLayerSupport.existsSql(dim.getDimTable(), codeCol, member);
                } catch (IllegalArgumentException e) {
                    throw new CommonException(e.getMessage());
                }
                Map<String, Object> exec = trinoClient.execute(sql, TrinoClient.ExecuteOptions.job(2));
                if (Boolean.TRUE.equals(exec.get("degraded"))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<?> rows = exec.get("rows") instanceof List<?> l ? l : List.of();
                if (rows.isEmpty()) {
                    throw new CommonException("维度成员不存在: " + grain + "=" + member
                            + "（DIM " + dim.getDimTable() + "）");
                }
            }
        }
    }

    @Override
    public List<String> resolveQualifierSnapshots(List<String> items, String ws) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String home = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<String> out = new ArrayList<>();
        for (String raw : items) {
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            String item = raw.trim();
            // 优先按 code 解析（主数据变更不影响已选 SQL 快照：表单 value 已是 exprSql）
            if (CODE_RE.matcher(item).matches() && !item.contains(" ") && !item.contains("=")) {
                GovMetricQualifier q = qualifierMapper.selectOne(new QueryWrapper<GovMetricQualifier>().lambda()
                        .eq(GovMetricQualifier::getWs, home)
                        .eq(GovMetricQualifier::getCode, item.toUpperCase(Locale.ROOT))
                        .eq(GovMetricQualifier::getDeleteFlag, NOT_DELETE)
                        .eq(GovMetricQualifier::getStatus, "active")
                        .last("LIMIT 1"));
                if (q == null) {
                    throw new CommonException("未知修饰词: " + item);
                }
                out.add(q.getExprSql());
                continue;
            }
            try {
                MetricQualifierDsl.assertSafeSqlFragment(item);
            } catch (IllegalArgumentException e) {
                // 若是 DSL 原文则编译
                try {
                    out.add(MetricQualifierDsl.compile(item));
                    continue;
                } catch (IllegalArgumentException ex) {
                    throw new CommonException("非法业务限定: " + e.getMessage());
                }
            }
            out.add(item);
        }
        return out;
    }

    private GovMetricQualifier requireQualifier(String code, String ws) {
        GovMetricQualifier row = qualifierMapper.selectOne(new QueryWrapper<GovMetricQualifier>().lambda()
                .eq(GovMetricQualifier::getWs, StrUtil.blankToDefault(ws, WS_DEFAULT))
                .eq(GovMetricQualifier::getCode, code.trim().toUpperCase(Locale.ROOT))
                .eq(GovMetricQualifier::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (row == null) {
            throw new CommonException("修饰词不存在: " + code);
        }
        return row;
    }

    private GovMetricDim requireDim(String code, String ws) {
        GovMetricDim row = dimMapper.selectOne(new QueryWrapper<GovMetricDim>().lambda()
                .eq(GovMetricDim::getWs, StrUtil.blankToDefault(ws, WS_DEFAULT))
                .eq(GovMetricDim::getCode, code.trim().toUpperCase(Locale.ROOT))
                .eq(GovMetricDim::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (row == null) {
            throw new CommonException("维度不存在: " + code);
        }
        return row;
    }

    private static Map<String, Object> toQualifierVo(GovMetricQualifier row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("code", row.getCode());
        m.put("name", row.getName());
        m.put("domainCode", row.getDomainCode());
        m.put("tablePattern", row.getTablePattern());
        m.put("exprDsl", row.getExprDsl());
        m.put("exprSql", row.getExprSql());
        m.put("status", row.getStatus());
        m.put("ws", row.getWs());
        m.put("remark", row.getRemark());
        return m;
    }

    private static Map<String, Object> toDimVo(GovMetricDim row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("code", row.getCode());
        m.put("name", row.getName());
        m.put("domainCode", row.getDomainCode());
        m.put("columnName", row.getColumnName());
        m.put("grainRole", row.getGrainRole());
        m.put("factTable", row.getFactTable());
        m.put("bizProcess", row.getBizProcess());
        m.put("dimTable", row.getDimTable());
        m.put("memberCodeCol", row.getMemberCodeCol());
        m.put("memberNameCol", row.getMemberNameCol());
        m.put("parentCodeCol", row.getParentCodeCol());
        m.put("dimStatus", StrUtil.blankToDefault(row.getDimStatus(), "unbound"));
        m.put("status", row.getStatus());
        m.put("ws", row.getWs());
        m.put("remark", row.getRemark());
        return m;
    }

    private static void applyDimLayerFields(GovMetricDimUpsertParam param, String columnName) {
        String dimTable = blankToNull(param.getDimTable());
        if (dimTable == null) {
            return;
        }
        try {
            MetricDimLayerSupport.assertDimLayerTable(dimTable);
            String codeCol = MetricDimLayerSupport.defaultCodeCol(columnName, param.getMemberCodeCol());
            String nameCol = MetricDimLayerSupport.defaultNameCol(param.getMemberNameCol());
            MetricDimLayerSupport.assertCol(codeCol, "member_code_col");
            MetricDimLayerSupport.assertCol(nameCol, "member_name_col");
            if (StrUtil.isNotBlank(param.getParentCodeCol())) {
                MetricDimLayerSupport.assertCol(param.getParentCodeCol(), "parent_code_col");
            }
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
    }

    private static void fillDimLayerOnRow(GovMetricDim row, GovMetricDimUpsertParam param, String columnName) {
        String dimTable = blankToNull(param.getDimTable());
        row.setDimTable(dimTable);
        if (dimTable == null) {
            row.setMemberCodeCol(null);
            row.setMemberNameCol(null);
            row.setParentCodeCol(null);
            row.setDimStatus("unbound");
            return;
        }
        row.setMemberCodeCol(MetricDimLayerSupport.defaultCodeCol(columnName, param.getMemberCodeCol()));
        row.setMemberNameCol(MetricDimLayerSupport.defaultNameCol(param.getMemberNameCol()));
        row.setParentCodeCol(blankToNull(param.getParentCodeCol()));
        row.setDimStatus(MetricDimLayerSupport.normalizeDimStatus(dimTable));
    }

    private static String strCell(Map<String, Object> r, String key) {
        Object v = r.get(key);
        if (v == null) {
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        return v == null ? "" : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        return StrUtil.isBlank(s) ? null : s.trim();
    }
}
