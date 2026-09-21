package vip.xiaonuo.lh.modular.aimodel.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiRoute;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiUsageDaily;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiModelMapper;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiRouteMapper;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiUsageDailyMapper;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelEnableParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelPageParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiRouteUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiModelVo;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiRouteVo;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型管理 P0
 */
@Service
public class GovAiModelServiceImpl implements GovAiModelService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovAiModelMapper modelMapper;
    @Resource
    private GovAiRouteMapper routeMapper;
    @Resource
    private GovAiUsageDailyMapper usageMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhLiteLlmClient liteLlmClient;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovAiModel> all = modelMapper.selectList(new QueryWrapper<GovAiModel>().lambda()
                .eq(GovAiModel::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAiModel::getWs, workspace).or().eq(GovAiModel::getWs, "*")));
        long enabled = all.stream().filter(m -> Boolean.TRUE.equals(m.getEnabled())).count();
        long ok = all.stream().filter(m -> "ok".equalsIgnoreCase(m.getStatus())).count();
        double avgLatency = all.stream()
                .filter(m -> m.getLatencyMs() != null && m.getLatencyMs() > 0)
                .mapToInt(GovAiModel::getLatencyMs)
                .average()
                .orElse(0);

        Date from = daysAgo(30);
        List<GovAiUsageDaily> usage = usageMapper.selectList(new QueryWrapper<GovAiUsageDaily>().lambda()
                .ge(GovAiUsageDaily::getDay, from)
                .and(w -> w.eq(GovAiUsageDaily::getWs, workspace).or().eq(GovAiUsageDaily::getWs, "*")));
        long calls = usage.stream().mapToLong(u -> u.getCalls() == null ? 0L : u.getCalls()).sum();
        BigDecimal cost = usage.stream()
                .map(u -> u.getCostAmount() == null ? BigDecimal.ZERO : u.getCostAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelCount", all.size());
        out.put("enabledCount", enabled);
        out.put("okCount", ok);
        out.put("connectivity", ok + "/" + Math.max(all.size(), 1));
        out.put("monthCalls", calls);
        out.put("monthCost", cost.setScale(2, RoundingMode.HALF_UP));
        out.put("avgLatencyMs", Math.round(avgLatency));
        out.put("ws", workspace);
        return out;
    }

    @Override
    public Page<GovAiModelVo> page(GovAiModelPageParam param) {
        QueryWrapper<GovAiModel> qw = new QueryWrapper<GovAiModel>().checkSqlInjection();
        qw.lambda().eq(GovAiModel::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().and(w -> w.eq(GovAiModel::getWs, ws).or().eq(GovAiModel::getWs, "*"));
        if (StrUtil.isNotBlank(param.getKind()) && !"all".equalsIgnoreCase(param.getKind())) {
            qw.lambda().eq(GovAiModel::getKind, param.getKind().trim().toLowerCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(param.getQ())) {
            String kw = param.getQ().trim();
            qw.lambda().and(w -> w.like(GovAiModel::getName, kw)
                    .or().like(GovAiModel::getVendor, kw)
                    .or().like(GovAiModel::getModelName, kw));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            String order = StrUtil.blankToDefault(param.getSortOrder(), CommonSortOrderEnum.DESC.getValue());
            CommonSortOrderEnum.validate(order);
            qw.orderBy(true, order.equalsIgnoreCase(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovAiModel::getUpdateTime).orderByDesc(GovAiModel::getCreateTime);
        }
        Page<GovAiModel> raw = modelMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovAiModelVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(this::toVo).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAiModelVo create(GovAiModelUpsertParam param) {
        String name = StrUtil.blankToDefault(param.getName(), param.getModelName());
        String modelName = StrUtil.blankToDefault(param.getModelName(), param.getModel());
        if (StrUtil.isBlank(name) || StrUtil.isBlank(modelName)) {
            throw new CommonException("name/model 不能为空");
        }
        if (StrUtil.isBlank(param.getBaseUrl())) {
            throw new CommonException("baseUrl 不能为空");
        }
        String id = IdUtil.getSnowflakeNextIdStr();
        String vaultPath = LhVaultPaths.aiModel(id);
        if (StrUtil.isNotBlank(param.getKey())) {
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("apiKey", param.getKey().trim());
            vaultClient.write(vaultPath, secret);
        }

        GovAiModel row = new GovAiModel();
        row.setId(id);
        row.setRevision(1);
        row.setStatus("ok");
        row.setWs(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT));
        row.setRemark(param.getRemark());
        row.setName(name.trim());
        row.setVendor(StrUtil.blankToDefault(param.getVendor(), "custom"));
        row.setKind(normalizeKind(param.getKind()));
        row.setModelName(modelName.trim());
        row.setBaseUrl(param.getBaseUrl().trim());
        row.setVaultPath(vaultPath);
        row.setContextTokens(normalizeContextLabel(
                firstNonBlank(param.getContextTokens(), param.getContext(), "128K")));
        row.setPriceUnit(StrUtil.blankToDefault(param.getPriceUnit(), "usd_1m"));
        row.setInputRate(param.getInputRate() == null ? BigDecimal.ZERO : param.getInputRate());
        row.setOutputRate(param.getOutputRate() == null ? BigDecimal.ZERO : param.getOutputRate());
        row.setEnabled(true);
        row.setLatencyMs(0);
        row.setRoleLabel(firstNonBlank(param.getRoleLabel(), param.getRole(), param.getUse(), ""));
        row.setKeyMask(maskKey(param.getKey()));
        row.setDeleteFlag(NOT_DELETE);
        modelMapper.insert(row);
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAiModelVo update(String id, GovAiModelUpsertParam param) {
        GovAiModel row = requireModel(id);
        if (StrUtil.isNotBlank(param.getName())) {
            row.setName(param.getName().trim());
        }
        if (StrUtil.isNotBlank(param.getVendor())) {
            row.setVendor(param.getVendor().trim());
        }
        String modelName = StrUtil.blankToDefault(param.getModelName(), param.getModel());
        if (StrUtil.isNotBlank(modelName)) {
            row.setModelName(modelName.trim());
        }
        if (StrUtil.isNotBlank(param.getBaseUrl())) {
            row.setBaseUrl(param.getBaseUrl().trim());
        }
        if (StrUtil.isNotBlank(param.getKind())) {
            row.setKind(normalizeKind(param.getKind()));
        }
        String ctx = firstNonBlank(param.getContextTokens(), param.getContext(), null);
        if (ctx != null) {
            row.setContextTokens(normalizeContextLabel(ctx));
        }
        if (StrUtil.isNotBlank(param.getPriceUnit())) {
            row.setPriceUnit(param.getPriceUnit());
        }
        if (param.getInputRate() != null) {
            row.setInputRate(param.getInputRate());
        }
        if (param.getOutputRate() != null) {
            row.setOutputRate(param.getOutputRate());
        }
        String role = firstNonBlank(param.getRoleLabel(), param.getRole(), param.getUse(), null);
        if (role != null) {
            row.setRoleLabel(role);
        }
        if (param.getRemark() != null) {
            row.setRemark(param.getRemark());
        }
        if (StrUtil.isNotBlank(param.getKey())) {
            String vaultPath = StrUtil.blankToDefault(row.getVaultPath(), LhVaultPaths.aiModel(id));
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("apiKey", param.getKey().trim());
            vaultClient.write(vaultPath, secret);
            row.setVaultPath(vaultPath);
            row.setKeyMask(maskKey(param.getKey()));
        }
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        modelMapper.updateById(row);
        return toVo(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> test(String id) {
        GovAiModel row = requireModel(id);
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        if (!liteLlmClient.available()) {
            row.setLatencyMs(50);
            row.setStatus("ok");
            modelMapper.updateById(row);
            out.put("ok", true);
            out.put("mock", true);
            out.put("status", "ok");
            out.put("latencyMs", 50);
            out.put("message", "LiteLLM 未启用，返回 mock 连通");
            return out;
        }
        String reply = liteLlmClient.chatSimple(row.getModelName(), "ping", "reply ok");
        int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
        boolean ok = StrUtil.isNotBlank(reply);
        row.setLatencyMs(latency);
        row.setStatus(ok ? "ok" : "warn");
        modelMapper.updateById(row);
        out.put("ok", ok);
        out.put("mock", false);
        out.put("status", row.getStatus());
        out.put("latencyMs", latency);
        out.put("message", ok ? "连通成功" : "调用失败或空响应");
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAiModelVo enable(String id, GovAiModelEnableParam param) {
        GovAiModel row = requireModel(id);
        boolean enabled = param != null && Boolean.TRUE.equals(param.getEnabled());
        row.setEnabled(enabled);
        if (!enabled) {
            row.setStatus("off");
        } else if ("off".equalsIgnoreCase(row.getStatus())) {
            row.setStatus("ok");
        }
        modelMapper.updateById(row);
        return toVo(row);
    }

    @Override
    public List<GovAiRouteVo> listRoutes(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovAiRoute> rows = routeMapper.selectList(new QueryWrapper<GovAiRoute>().lambda()
                .eq(GovAiRoute::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAiRoute::getWsScope, workspace)
                        .or().eq(GovAiRoute::getWsScope, "*")
                        .or().eq(GovAiRoute::getWs, workspace))
                .orderByAsc(GovAiRoute::getScene));
        Map<String, String> nameMap = loadModelNames();
        return rows.stream().map(r -> toRouteVo(r, nameMap)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<GovAiRouteVo> saveRoutes(List<GovAiRouteUpsertParam> params) {
        if (params == null || params.isEmpty()) {
            throw new CommonException("路由列表不能为空");
        }
        List<GovAiRouteVo> out = new ArrayList<>();
        Map<String, String> nameMap = loadModelNames();
        for (GovAiRouteUpsertParam p : params) {
            if (StrUtil.isBlank(p.getScene())) {
                throw new CommonException("scene 不能为空");
            }
            GovAiRoute row;
            if (StrUtil.isNotBlank(p.getId())) {
                row = routeMapper.selectById(p.getId());
                if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
                    throw new CommonException("路由不存在: {}", p.getId());
                }
            } else {
                row = new GovAiRoute();
                row.setId(IdUtil.getSnowflakeNextIdStr());
                row.setRevision(1);
                row.setDeleteFlag(NOT_DELETE);
                row.setStatus("ok");
                row.setWs(StrUtil.blankToDefault(p.getWs(), WS_DEFAULT));
            }
            row.setScene(p.getScene().trim());
            row.setWsScope(StrUtil.blankToDefault(p.getWsScope(), "*"));
            row.setPrimaryModelId(p.getPrimaryModelId());
            row.setFallbackModelId(p.getFallbackModelId());
            row.setEnabled(p.getEnabled() == null || Boolean.TRUE.equals(p.getEnabled()));
            row.setRemark(p.getRemark());
            row.setStatus(Boolean.TRUE.equals(row.getEnabled()) ? "ok" : "off");
            row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
            if (routeMapper.selectById(row.getId()) == null) {
                routeMapper.insert(row);
            } else {
                routeMapper.updateById(row);
            }
            out.add(toRouteVo(row, nameMap));
        }
        return out;
    }

    @Override
    public Map<String, Object> usage(String range, String group, String ws) {
        int days = parseRangeDays(range);
        Date from = daysAgo(days);
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovAiUsageDaily> rows = usageMapper.selectList(new QueryWrapper<GovAiUsageDaily>().lambda()
                .ge(GovAiUsageDaily::getDay, from)
                .and(w -> w.eq(GovAiUsageDaily::getWs, workspace).or().eq(GovAiUsageDaily::getWs, "*")));
        String g = StrUtil.blankToDefault(group, "model").toLowerCase(Locale.ROOT);
        Map<String, String> nameMap = loadModelNames();
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (GovAiUsageDaily u : rows) {
            String key = "ws".equals(g) ? StrUtil.blankToDefault(u.getWs(), workspace)
                    : StrUtil.blankToDefault(u.getModelId(), "unknown");
            Map<String, Object> bucket = agg.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("name", "ws".equals(g) ? k : nameMap.getOrDefault(k, k));
                m.put("calls", 0L);
                m.put("promptTokens", 0L);
                m.put("completionTokens", 0L);
                m.put("costAmount", BigDecimal.ZERO);
                return m;
            });
            bucket.put("calls", ((Long) bucket.get("calls")) + (u.getCalls() == null ? 0L : u.getCalls()));
            bucket.put("promptTokens", ((Long) bucket.get("promptTokens"))
                    + (u.getPromptTokens() == null ? 0L : u.getPromptTokens()));
            bucket.put("completionTokens", ((Long) bucket.get("completionTokens"))
                    + (u.getCompletionTokens() == null ? 0L : u.getCompletionTokens()));
            BigDecimal cost = (BigDecimal) bucket.get("costAmount");
            bucket.put("costAmount", cost.add(u.getCostAmount() == null ? BigDecimal.ZERO : u.getCostAmount()));
        }
        long maxCalls = agg.values().stream()
                .mapToLong(m -> (Long) m.get("calls"))
                .max()
                .orElse(1L);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> m : agg.values()) {
            long calls = (Long) m.get("calls");
            m.put("pct", maxCalls <= 0 ? 0 : Math.round(calls * 100.0 / maxCalls));
            items.add(m);
        }
        items.sort((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", days + "d");
        out.put("group", g);
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordUsage(String ws, String modelId, long promptTokens, long completionTokens) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String mid = StrUtil.blankToDefault(modelId, "unknown");
        Date day = truncateDay(new Date());
        GovAiUsageDaily row = usageMapper.selectOne(new QueryWrapper<GovAiUsageDaily>().lambda()
                .eq(GovAiUsageDaily::getDay, day)
                .eq(GovAiUsageDaily::getWs, workspace)
                .eq(GovAiUsageDaily::getModelId, mid)
                .last("LIMIT 1"));
        BigDecimal cost = estimateCost(mid, promptTokens, completionTokens);
        if (row == null) {
            row = new GovAiUsageDaily();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setDay(day);
            row.setWs(workspace);
            row.setModelId(mid);
            row.setCalls(1L);
            row.setPromptTokens(Math.max(0, promptTokens));
            row.setCompletionTokens(Math.max(0, completionTokens));
            row.setCostAmount(cost);
            row.setCurrency("CNY");
            usageMapper.insert(row);
        } else {
            row.setCalls((row.getCalls() == null ? 0L : row.getCalls()) + 1);
            row.setPromptTokens((row.getPromptTokens() == null ? 0L : row.getPromptTokens()) + Math.max(0, promptTokens));
            row.setCompletionTokens((row.getCompletionTokens() == null ? 0L : row.getCompletionTokens())
                    + Math.max(0, completionTokens));
            row.setCostAmount((row.getCostAmount() == null ? BigDecimal.ZERO : row.getCostAmount()).add(cost));
            usageMapper.updateById(row);
        }
        try {
            GovAiModel model = modelMapper.selectById(mid);
            if (model != null) {
                model.setCallsTotal((model.getCallsTotal() == null ? 0L : model.getCallsTotal()) + 1);
                if (model.getCostTotal() == null) {
                    model.setCostTotal(cost);
                } else {
                    model.setCostTotal(model.getCostTotal().add(cost));
                }
                modelMapper.updateById(model);
            }
        } catch (Exception ignored) {
            // 非关键
        }
    }

    private BigDecimal estimateCost(String modelId, long promptTokens, long completionTokens) {
        try {
            GovAiModel m = modelMapper.selectById(modelId);
            if (m == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal in = m.getInputRate() == null ? BigDecimal.ZERO : m.getInputRate();
            BigDecimal out = m.getOutputRate() == null ? BigDecimal.ZERO : m.getOutputRate();
            String unit = StrUtil.blankToDefault(m.getPriceUnit(), "usd_1m");
            BigDecimal scale;
            if ("free".equalsIgnoreCase(unit)) {
                return BigDecimal.ZERO;
            } else if ("cny_1k".equalsIgnoreCase(unit)) {
                scale = BigDecimal.valueOf(1000);
            } else {
                // usd_1m / cny_1m
                scale = BigDecimal.valueOf(1_000_000);
            }
            BigDecimal c = in.multiply(BigDecimal.valueOf(promptTokens))
                    .add(out.multiply(BigDecimal.valueOf(completionTokens)))
                    .divide(scale, 6, RoundingMode.HALF_UP);
            return c;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static Date truncateDay(Date d) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(d);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private GovAiModel requireModel(String id) {
        GovAiModel row = modelMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("模型不存在: {}", id);
        }
        return row;
    }

    private Map<String, String> loadModelNames() {
        return modelMapper.selectList(new QueryWrapper<GovAiModel>().lambda()
                        .eq(GovAiModel::getDeleteFlag, NOT_DELETE))
                .stream()
                .collect(Collectors.toMap(GovAiModel::getId, GovAiModel::getName, (a, b) -> a));
    }

    private GovAiModelVo toVo(GovAiModel row) {
        GovAiModelVo vo = new GovAiModelVo();
        vo.setId(row.getId());
        vo.setName(row.getName());
        vo.setVendor(row.getVendor());
        vo.setKind(row.getKind());
        vo.setModelName(row.getModelName());
        vo.setBaseUrl(row.getBaseUrl());
        vo.setEndpoint(row.getBaseUrl());
        vo.setKeyMask(row.getKeyMask());
        vo.setKey(StrUtil.blankToDefault(row.getKeyMask(), "sk-****（Vault）"));
        String ctxLabel = normalizeContextLabel(row.getContextTokens());
        vo.setContextTokens(ctxLabel);
        vo.setContext(ctxLabel);
        vo.setPriceUnit(row.getPriceUnit());
        vo.setInputRate(row.getInputRate());
        vo.setOutputRate(row.getOutputRate());
        vo.setEnabled(row.getEnabled());
        vo.setStatus(row.getStatus());
        vo.setLatencyMs(row.getLatencyMs());
        vo.setLatency(row.getLatencyMs() == null || row.getLatencyMs() <= 0
                ? "—"
                : String.format(Locale.ROOT, "%.1fs", row.getLatencyMs() / 1000.0));
        vo.setRoleLabel(row.getRoleLabel());
        vo.setRole(row.getRoleLabel());
        vo.setWs(row.getWs());
        vo.setRevision(row.getRevision());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private GovAiRouteVo toRouteVo(GovAiRoute row, Map<String, String> nameMap) {
        GovAiRouteVo vo = new GovAiRouteVo();
        vo.setId(row.getId());
        vo.setScene(row.getScene());
        vo.setWsScope(row.getWsScope());
        vo.setPrimaryModelId(row.getPrimaryModelId());
        vo.setPrimaryModelName(nameMap.getOrDefault(row.getPrimaryModelId(), row.getPrimaryModelId()));
        vo.setFallbackModelId(row.getFallbackModelId());
        vo.setFallbackModelName(nameMap.getOrDefault(row.getFallbackModelId(), row.getFallbackModelId()));
        vo.setEnabled(row.getEnabled());
        vo.setStatus(row.getStatus());
        vo.setRemark(row.getRemark());
        return vo;
    }

    /** 脱敏：sk-****...last4（Vault） */
    static String maskKey(String key) {
        if (StrUtil.isBlank(key)) {
            return "sk-****（Vault）";
        }
        String k = key.trim();
        String last4 = k.length() <= 4 ? k : k.substring(k.length() - 4);
        String prefix = k.startsWith("sk-") ? "sk-" : "";
        return prefix + "****..." + last4 + "（Vault）";
    }

    private static String normalizeKind(String kind) {
        String k = StrUtil.blankToDefault(kind, "chat").trim().toLowerCase(Locale.ROOT);
        return "embed".equals(k) ? "embed" : "chat";
    }

    /** 库字段为展示串（128K）；兼容纯数字入参 */
    private static String normalizeContextLabel(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "128K";
        }
        String s = raw.trim();
        if (s.matches("(?i)\\d+[km]")) {
            return s.substring(0, s.length() - 1) + Character.toUpperCase(s.charAt(s.length() - 1));
        }
        if (s.matches("\\d+")) {
            try {
                int tokens = Integer.parseInt(s);
                return tokens >= 1000 ? (tokens / 1000) + "K" : String.valueOf(tokens);
            } catch (NumberFormatException ignored) {
                return s;
            }
        }
        return s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return vals.length > 0 ? vals[vals.length - 1] : null;
    }

    private static int parseRangeDays(String range) {
        if (StrUtil.isBlank(range)) {
            return 30;
        }
        String r = range.trim().toLowerCase(Locale.ROOT);
        if (r.endsWith("d")) {
            try {
                return Math.max(1, Integer.parseInt(r.substring(0, r.length() - 1)));
            } catch (Exception ignored) {
                return 30;
            }
        }
        try {
            return Math.max(1, Integer.parseInt(r));
        } catch (Exception e) {
            return 30;
        }
    }

    private static Date daysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -Math.max(1, days));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }
}
