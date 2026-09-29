package vip.xiaonuo.lh.modular.domain.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.domain.entity.GovDomain;
import vip.xiaonuo.lh.modular.domain.mapper.GovDomainMapper;
import vip.xiaonuo.lh.modular.domain.param.GovDomainUpsertParam;
import vip.xiaonuo.lh.modular.domain.service.GovDomainService;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.standard.entity.GovStdField;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdFieldMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务数据域 SoT
 */
@Service
public class GovDomainServiceImpl implements GovDomainService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DISABLED = "disabled";
    private static final String WS_DEFAULT = "default";

    /** 内置别名 → 规范码（含 product→goods） */
    private static final Map<String, String> BUILTIN_ALIAS = Map.ofEntries(
            Map.entry("交易", "trade"),
            Map.entry("交易域", "trade"),
            Map.entry("用户", "user"),
            Map.entry("用户域", "user"),
            Map.entry("流量", "user"),
            Map.entry("商品", "goods"),
            Map.entry("商品域", "goods"),
            Map.entry("product", "goods"),
            Map.entry("营销", "marketing"),
            Map.entry("营销域", "marketing"),
            Map.entry("财务", "finance"),
            Map.entry("财务域", "finance"),
            Map.entry("通用", "common")
    );

    /** 规范码 → 历史遗留写入值（usage 统计 OR） */
    private static final Map<String, List<String>> LEGACY_CODES = Map.of(
            "goods", List.of("goods", "product", "商品", "商品域"),
            "trade", List.of("trade", "交易", "交易域"),
            "user", List.of("user", "用户", "用户域", "流量"),
            "marketing", List.of("marketing", "营销", "营销域"),
            "finance", List.of("finance", "财务", "财务域"),
            "common", List.of("common", "通用")
    );

    @Resource
    private GovDomainMapper domainMapper;
    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovStdFieldMapper stdFieldMapper;

    @Override
    public List<Map<String, Object>> list(String ws, String status, String q) {
        String wsKey = StrUtil.trim(ws);
        String statusKey = StrUtil.trim(status);
        String kw = StrUtil.trim(q);
        QueryWrapper<GovDomain> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(wsKey), GovDomain::getWs, wsKey)
                .eq(StrUtil.isNotBlank(statusKey), GovDomain::getStatus, statusKey)
                .and(StrUtil.isNotBlank(kw), w -> w.like(GovDomain::getDomainCode, kw)
                        .or().like(GovDomain::getName, kw)
                        .or().like(GovDomain::getOwner, kw))
                .orderByAsc(GovDomain::getSortNo)
                .orderByAsc(GovDomain::getDomainCode);
        return domainMapper.selectList(qw).stream().map(this::toRow).toList();
    }

    @Override
    public List<Map<String, Object>> options() {
        List<GovDomain> rows = domainMapper.selectList(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getStatus, STATUS_ACTIVE)
                .orderByAsc(GovDomain::getSortNo)
                .orderByAsc(GovDomain::getDomainCode));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovDomain d : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", d.getDomainCode());
            m.put("label", d.getName());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String code) {
        return toRow(requireByCode(code));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(GovDomainUpsertParam param) {
        if (StrUtil.isBlank(param.getDomainCode())) {
            throw new CommonException("domainCode 不能为空");
        }
        if (StrUtil.isBlank(param.getName())) {
            throw new CommonException("name 不能为空");
        }
        String code = normalizeKey(param.getDomainCode());
        if ("product".equals(code)) {
            code = "goods";
        }
        if (!code.matches("^[a-z][a-z0-9_]{0,63}$")) {
            throw new CommonException("domainCode 须为小写字母开头的标识: " + code);
        }
        Long dup = domainMapper.selectCount(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDomainCode, code)
                .eq(GovDomain::getDeleteFlag, NOT_DELETE));
        if (dup != null && dup > 0) {
            throw new CommonException("域编码已存在: " + code);
        }
        GovDomain row = new GovDomain();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setWs(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT).trim());
        row.setDomainCode(code);
        row.setName(param.getName().trim());
        row.setOwner(StrUtil.trim(param.getOwner()));
        row.setSortNo(param.getSortNo() == null ? 100 : param.getSortNo());
        row.setStatus(StrUtil.blankToDefault(param.getStatus(), STATUS_ACTIVE).trim().toLowerCase(Locale.ROOT));
        if (!Set.of(STATUS_ACTIVE, STATUS_DISABLED).contains(row.getStatus())) {
            throw new CommonException("status 须为 active 或 disabled");
        }
        row.setRemark(param.getRemark());
        row.setDeleteFlag(NOT_DELETE);
        domainMapper.insert(row);
        return toRow(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String code, GovDomainUpsertParam param) {
        GovDomain row = requireByCode(code);
        if (StrUtil.isNotBlank(param.getName())) {
            row.setName(param.getName().trim());
        }
        if (param.getOwner() != null) {
            row.setOwner(StrUtil.trim(param.getOwner()));
        }
        if (param.getSortNo() != null) {
            row.setSortNo(param.getSortNo());
        }
        if (param.getRemark() != null) {
            row.setRemark(param.getRemark());
        }
        if (StrUtil.isNotBlank(param.getWs())) {
            row.setWs(param.getWs().trim());
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            String st = param.getStatus().trim().toLowerCase(Locale.ROOT);
            if (!Set.of(STATUS_ACTIVE, STATUS_DISABLED).contains(st)) {
                throw new CommonException("status 须为 active 或 disabled");
            }
            if (STATUS_DISABLED.equals(st) && !STATUS_DISABLED.equals(row.getStatus())) {
                assertNoUsage(row.getDomainCode());
            }
            row.setStatus(st);
        }
        domainMapper.updateById(row);
        return toRow(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> enable(String code) {
        GovDomain row = requireByCode(code);
        row.setStatus(STATUS_ACTIVE);
        domainMapper.updateById(row);
        return toRow(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disable(String code) {
        GovDomain row = requireByCode(code);
        assertNoUsage(row.getDomainCode());
        row.setStatus(STATUS_DISABLED);
        domainMapper.updateById(row);
        return toRow(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(String code) {
        GovDomain row = requireByCode(code);
        assertNoUsage(row.getDomainCode());
        domainMapper.deleteById(row.getId());
    }

    @Override
    public Map<String, Object> usage(String code) {
        GovDomain row = requireByCode(code);
        String canonical = row.getDomainCode();
        List<String> codes = matchCodes(canonical);
        long metricCount = countMetrics(codes);
        long assetCount = countAssets(codes);
        long standardFieldCount = countStdFields(codes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCount", metricCount);
        out.put("assetCount", assetCount);
        out.put("standardFieldCount", standardFieldCount);
        out.put("total", metricCount + assetCount + standardFieldCount);
        return out;
    }

    @Override
    public String resolveCode(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String key = raw.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        if ("product".equals(lower)) {
            lower = "goods";
            key = "goods";
        }
        if (BUILTIN_ALIAS.containsKey(key)) {
            return BUILTIN_ALIAS.get(key);
        }
        if (BUILTIN_ALIAS.containsKey(lower)) {
            return BUILTIN_ALIAS.get(lower);
        }

        // 先按规范码精确匹配
        GovDomain byCode = domainMapper.selectOne(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getDomainCode, lower)
                .last("LIMIT 1"));
        if (byCode != null) {
            return byCode.getDomainCode();
        }

        // 按展示名
        GovDomain byName = domainMapper.selectOne(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getName, key)
                .last("LIMIT 1"));
        if (byName != null) {
            return byName.getDomainCode();
        }

        // extra_json.aliases
        List<GovDomain> all = domainMapper.selectList(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE));
        for (GovDomain d : all) {
            if (aliasContains(d.getExtraJson(), key) || aliasContains(d.getExtraJson(), lower)) {
                return d.getDomainCode();
            }
        }
        return null;
    }

    @Override
    public String requireActive(String code) {
        String resolved = resolveCode(code);
        if (StrUtil.isBlank(resolved)) {
            throw new CommonException("未知数据域: " + code);
        }
        GovDomain row = domainMapper.selectOne(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getDomainCode, resolved)
                .last("LIMIT 1"));
        if (row == null) {
            throw new CommonException("未知数据域: " + code);
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(row.getStatus())) {
            throw new CommonException("数据域未启用: " + resolved);
        }
        return resolved;
    }

    @Override
    public String labelOf(String code) {
        if (StrUtil.isBlank(code)) {
            return code;
        }
        String resolved = resolveCode(code);
        String look = StrUtil.blankToDefault(resolved, code.trim());
        GovDomain row = domainMapper.selectOne(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getDomainCode, look.toLowerCase(Locale.ROOT))
                .last("LIMIT 1"));
        if (row != null && StrUtil.isNotBlank(row.getName())) {
            return row.getName();
        }
        return look;
    }

    // ---- helpers ----

    private void assertNoUsage(String code) {
        Map<String, Object> u = usageCounts(code);
        long total = ((Number) u.get("total")).longValue();
        if (total > 0) {
            throw new CommonException("数据域仍有引用，无法停用/删除: metric="
                    + u.get("metricCount") + ", asset=" + u.get("assetCount")
                    + ", stdField=" + u.get("standardFieldCount"));
        }
    }

    private Map<String, Object> usageCounts(String canonical) {
        List<String> codes = matchCodes(canonical);
        long metricCount = countMetrics(codes);
        long assetCount = countAssets(codes);
        long standardFieldCount = countStdFields(codes);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCount", metricCount);
        out.put("assetCount", assetCount);
        out.put("standardFieldCount", standardFieldCount);
        out.put("total", metricCount + assetCount + standardFieldCount);
        return out;
    }

    private List<String> matchCodes(String canonical) {
        if (LEGACY_CODES.containsKey(canonical)) {
            return LEGACY_CODES.get(canonical);
        }
        return List.of(canonical);
    }

    private long countMetrics(List<String> codes) {
        Long n = metricMapper.selectCount(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .in(GovMetric::getDomainCode, codes));
        return n == null ? 0L : n;
    }

    private long countAssets(List<String> codes) {
        Long n = assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .in(GovAsset::getDomainCode, codes));
        return n == null ? 0L : n;
    }

    private long countStdFields(List<String> codes) {
        Long n = stdFieldMapper.selectCount(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                .in(GovStdField::getDomainCode, codes));
        return n == null ? 0L : n;
    }

    private GovDomain requireByCode(String code) {
        if (StrUtil.isBlank(code)) {
            throw new CommonException("域编码不能为空");
        }
        String resolved = resolveCode(code);
        String look = StrUtil.blankToDefault(resolved, normalizeKey(code));
        GovDomain row = domainMapper.selectOne(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)
                .eq(GovDomain::getDomainCode, look)
                .last("LIMIT 1"));
        if (row == null) {
            throw new CommonException("数据域不存在: " + code);
        }
        return row;
    }

    private static String normalizeKey(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean aliasContains(String extraJson, String key) {
        if (StrUtil.isBlank(extraJson) || StrUtil.isBlank(key)) {
            return false;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(extraJson);
            JSONArray aliases = obj.getJSONArray("aliases");
            if (aliases == null) {
                return false;
            }
            for (Object a : aliases) {
                if (a != null && key.equals(String.valueOf(a).trim())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // ignore malformed extra
        }
        return false;
    }

    private Map<String, Object> toRow(GovDomain d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("ws", d.getWs());
        m.put("domainCode", d.getDomainCode());
        m.put("name", d.getName());
        m.put("owner", d.getOwner());
        m.put("sortNo", d.getSortNo());
        m.put("status", d.getStatus());
        m.put("remark", d.getRemark());
        m.put("extraJson", d.getExtraJson());
        m.put("createTime", d.getCreateTime());
        m.put("updateTime", d.getUpdateTime());
        return m;
    }
}
