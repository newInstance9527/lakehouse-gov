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
import vip.xiaonuo.lh.config.LhProperties;
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
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelRotateParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiRouteUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiModelVo;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiRouteVo;
import vip.xiaonuo.lh.modular.ai.support.AiEgressPolicy;
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
    @Resource
    private LhProperties lhProperties;

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
        Map<String, Object> gw = liteLlmClient.probeSync();
        out.put("litellmEnabled", Boolean.TRUE.equals(gw.get("enabled")));
        out.put("litellmReachable", Boolean.TRUE.equals(gw.get("reachable")));
        out.put("litellmSyncCapable", Boolean.TRUE.equals(gw.get("syncCapable")));
        out.put("litellmSyncMode", gw.get("mode"));
        out.put("litellmAliasPrefix", gw.get("aliasPrefix"));
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
        applyEgressFields(row, param, true);
        row.setLatencyMs(0);
        row.setRoleLabel(firstNonBlank(param.getRoleLabel(), param.getRole(), param.getUse(), ""));
        row.setKeyMask(maskKey(param.getKey()));
        row.setKeyExpiresAt(param.getKeyExpiresAt());
        row.setDeleteFlag(NOT_DELETE);
        modelMapper.insert(row);
        GovAiModelVo vo = toVo(row);
        attachSync(vo, syncToLiteLlm(row, true));
        return vo;
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
        if (param.getKeyExpiresAt() != null) {
            row.setKeyExpiresAt(param.getKeyExpiresAt());
        }
        if (StrUtil.isNotBlank(param.getKey())) {
            String vaultPath = StrUtil.blankToDefault(row.getVaultPath(), LhVaultPaths.aiModel(id));
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("apiKey", param.getKey().trim());
            vaultClient.write(vaultPath, secret);
            row.setVaultPath(vaultPath);
            row.setKeyMask(maskKey(param.getKey()));
        }
        applyEgressFields(row, param, false);
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        modelMapper.updateById(row);
        GovAiModelVo vo = toVo(row);
        attachSync(vo, syncToLiteLlm(row, Boolean.TRUE.equals(row.getEnabled())));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAiModelVo rotate(String id, GovAiModelRotateParam param) {
        if (param == null || StrUtil.isBlank(param.getKey())) {
            throw new CommonException("key 不能为空");
        }
        GovAiModel row = requireModel(id);
        String vaultPath = StrUtil.blankToDefault(row.getVaultPath(), LhVaultPaths.aiModel(id));
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("apiKey", param.getKey().trim());
        secret.put("rotatedAt", new Date().toString());
        vaultClient.write(vaultPath, secret);
        row.setVaultPath(vaultPath);
        row.setKeyMask(maskKey(param.getKey()));
        if (param.getKeyExpiresAt() != null) {
            row.setKeyExpiresAt(param.getKeyExpiresAt());
        }
        if ("warn".equalsIgnoreCase(row.getStatus()) || "off".equalsIgnoreCase(row.getStatus())) {
            // 轮换后恢复可探测态；启停 off 仍保持 enabled 语义由 enable 控制
            if (Boolean.TRUE.equals(row.getEnabled())) {
                row.setStatus("ok");
            }
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

        String vaultPath = row.getVaultPath();
        boolean vaultOk = StrUtil.isNotBlank(vaultPath) && vaultClient.exists(vaultPath)
                && StrUtil.isNotBlank(vaultClient.getString(vaultPath, "apiKey"));
        out.put("vaultPath", vaultPath);
        out.put("vaultOk", vaultOk);

        if (!liteLlmClient.available()) {
            // 未配网关：仅验收 Vault；不伪造成功连通
            String status = vaultOk ? "ok" : "warn";
            row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
            row.setStatus(Boolean.TRUE.equals(row.getEnabled()) ? status : "off");
            modelMapper.updateById(row);
            out.put("ok", vaultOk);
            out.put("mock", true);
            out.put("status", row.getStatus());
            out.put("latencyMs", row.getLatencyMs());
            out.put("message", vaultOk
                    ? "LiteLLM 未启用；Vault Key 已存在"
                    : "LiteLLM 未启用且 Vault 无可用 Key");
            return out;
        }

        boolean ok;
        String message;
        String callModel = LhLiteLlmClient.aliasOf(row.getId());
        if ("embed".equalsIgnoreCase(row.getKind())) {
            List<float[]> vecs = liteLlmClient.embed(callModel, List.of("ping"));
            if (vecs == null || vecs.isEmpty()) {
                vecs = liteLlmClient.embed(row.getModelName(), List.of("ping"));
                callModel = row.getModelName();
            }
            ok = vecs != null && !vecs.isEmpty();
            message = ok ? "Embedding 连通成功（" + callModel + "）" : "Embedding 调用失败或空响应";
        } else {
            String reply = liteLlmClient.chatSimple(callModel, "ping", "reply ok");
            if (StrUtil.isBlank(reply)) {
                reply = liteLlmClient.chatSimple(row.getModelName(), "ping", "reply ok");
                callModel = row.getModelName();
            }
            ok = StrUtil.isNotBlank(reply);
            message = ok ? "连通成功（" + callModel + "）" : "调用失败或空响应";
        }
        if (!vaultOk) {
            ok = false;
            message = (message == null ? "" : message + "；") + "Vault 无可用 Key";
        }
        int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
        row.setLatencyMs(latency);
        String status = !Boolean.TRUE.equals(row.getEnabled()) ? "off" : (ok ? "ok" : "warn");
        if (ok && isKeyExpiringSoon(row)) {
            status = "warn";
            message = message + "；Key 即将过期";
        }
        row.setStatus(status);
        modelMapper.updateById(row);
        out.put("ok", ok);
        out.put("mock", false);
        out.put("status", row.getStatus());
        out.put("latencyMs", latency);
        out.put("message", message);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> patrol(String ws) {
        Map<String, Object> litellm = liteLlmClient.health();
        var qw = new QueryWrapper<GovAiModel>().lambda()
                .eq(GovAiModel::getDeleteFlag, NOT_DELETE)
                .eq(GovAiModel::getEnabled, true);
        if (StrUtil.isNotBlank(ws)) {
            String workspace = ws.trim();
            qw.and(w -> w.eq(GovAiModel::getWs, workspace).or().eq(GovAiModel::getWs, "*"));
        }
        List<GovAiModel> models = modelMapper.selectList(qw);
        List<Map<String, Object>> items = new ArrayList<>();
        int warn = 0;
        int fail = 0;
        for (GovAiModel m : models) {
            Map<String, Object> one = test(m.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("name", m.getName());
            item.put("kind", m.getKind());
            item.put("ok", one.get("ok"));
            item.put("status", one.get("status"));
            item.put("vaultOk", one.get("vaultOk"));
            item.put("latencyMs", one.get("latencyMs"));
            item.put("message", one.get("message"));
            items.add(item);
            if (!Boolean.TRUE.equals(one.get("ok"))) {
                fail++;
            } else if ("warn".equalsIgnoreCase(String.valueOf(one.get("status")))) {
                warn++;
            }
        }
        // 网关不可达：已启用模型统一标 warn（test 可能因 mock 路径不同）
        if (!Boolean.TRUE.equals(litellm.get("ok")) && Boolean.TRUE.equals(litellm.get("available"))) {
            for (GovAiModel m : models) {
                if ("ok".equalsIgnoreCase(m.getStatus())) {
                    m.setStatus("warn");
                    modelMapper.updateById(m);
                    warn++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("litellm", litellm);
        out.put("litellmOk", Boolean.TRUE.equals(litellm.get("ok")));
        out.put("checked", models.size());
        out.put("warnCount", warn);
        out.put("failCount", fail);
        out.put("items", items);
        out.put("ws", StrUtil.blankToDefault(ws, "*"));
        return out;
    }

    @Override
    public Map<String, Object> gatewayProbe() {
        return liteLlmClient.probeSync();
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
        GovAiModelVo vo = toVo(row);
        attachSync(vo, liteLlmClient.setAliasEnabled(
                row.getId(), enabled, row.getVendor(), row.getModelName(), row.getBaseUrl(), row.getKind()));
        return vo;
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
            // D5：生产路由绑定外发模型须安全岗标记
            if (Boolean.TRUE.equals(row.getEnabled())) {
                if (StrUtil.isNotBlank(row.getPrimaryModelId())) {
                    AiEgressPolicy.assertAllowed(requireModel(row.getPrimaryModelId()), row.getScene());
                }
                if (StrUtil.isNotBlank(row.getFallbackModelId())) {
                    AiEgressPolicy.assertAllowed(requireModel(row.getFallbackModelId()), row.getScene());
                }
            }
            row.setStatus(Boolean.TRUE.equals(row.getEnabled()) ? "ok" : "off");
            row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
            if (routeMapper.selectById(row.getId()) == null) {
                routeMapper.insert(row);
            } else {
                routeMapper.updateById(row);
            }
            out.add(toRouteVo(row, nameMap));
        }
        // D3：主/备 → LiteLLM fallbacks（软降级）
        try {
            List<Map<String, List<String>>> fallbacks = new ArrayList<>();
            for (GovAiRouteVo r : out) {
                if (r == null || !Boolean.TRUE.equals(r.getEnabled())) {
                    continue;
                }
                if (StrUtil.isBlank(r.getPrimaryModelId()) || StrUtil.isBlank(r.getFallbackModelId())) {
                    continue;
                }
                Map<String, List<String>> one = new LinkedHashMap<>();
                one.put(LhLiteLlmClient.aliasOf(r.getPrimaryModelId()),
                        List.of(LhLiteLlmClient.aliasOf(r.getFallbackModelId())));
                fallbacks.add(one);
            }
            if (!fallbacks.isEmpty()) {
                liteLlmClient.syncFallbacks(fallbacks);
            }
        } catch (Exception ignored) {
            // 门户路由已保存
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
        vo.setKeyExpiresAt(row.getKeyExpiresAt());
        vo.setVaultPath(row.getVaultPath());
        String ctxLabel = normalizeContextLabel(row.getContextTokens());
        vo.setContextTokens(ctxLabel);
        vo.setContext(ctxLabel);
        vo.setPriceUnit(row.getPriceUnit());
        vo.setInputRate(row.getInputRate());
        vo.setOutputRate(row.getOutputRate());
        vo.setEnabled(row.getEnabled());
        String egressKind = StrUtil.blankToDefault(row.getEgressKind(),
                AiEgressPolicy.classify(row.getVendor(), row.getBaseUrl(), row.getPriceUnit()));
        vo.setEgressKind(egressKind);
        vo.setEgressApproved(Boolean.TRUE.equals(row.getEgressApproved())
                || AiEgressPolicy.isLocal(egressKind));
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
        vo.setLitellmAlias(LhLiteLlmClient.aliasOf(row.getId()));
        return vo;
    }

    private Map<String, Object> syncToLiteLlm(GovAiModel row, boolean enabled) {
        return liteLlmClient.upsertAlias(
                row.getId(), row.getVendor(), row.getModelName(), row.getBaseUrl(), row.getKind(), enabled);
    }

    private static void attachSync(GovAiModelVo vo, Map<String, Object> sync) {
        if (vo == null || sync == null) {
            return;
        }
        if (sync.get("alias") != null) {
            vo.setLitellmAlias(String.valueOf(sync.get("alias")));
        }
        vo.setLitellmSyncOk(Boolean.TRUE.equals(sync.get("ok")));
        vo.setLitellmSyncSkipped(Boolean.TRUE.equals(sync.get("skipped")));
        Object msg = sync.get("message");
        if (msg != null) {
            vo.setLitellmSyncMessage(String.valueOf(msg));
        }
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

    /**
     * D5：写入/刷新 egress_kind；外发模型的安全岗标记来自入参（新建默认未评估）。
     */
    private static void applyEgressFields(GovAiModel row, GovAiModelUpsertParam param, boolean creating) {
        String kind = StrUtil.isNotBlank(param.getEgressKind())
                ? param.getEgressKind().trim().toLowerCase(Locale.ROOT)
                : AiEgressPolicy.classify(row.getVendor(), row.getBaseUrl(), row.getPriceUnit());
        if (!AiEgressPolicy.KIND_LOCAL.equals(kind) && !AiEgressPolicy.KIND_EGRESS.equals(kind)) {
            kind = AiEgressPolicy.classify(row.getVendor(), row.getBaseUrl(), row.getPriceUnit());
        }
        row.setEgressKind(kind);
        if (AiEgressPolicy.isLocal(kind)) {
            row.setEgressApproved(true);
            return;
        }
        if (param.getEgressApproved() != null) {
            row.setEgressApproved(Boolean.TRUE.equals(param.getEgressApproved()));
        } else if (creating) {
            row.setEgressApproved(false);
        } else if (row.getEgressApproved() == null) {
            row.setEgressApproved(false);
        }
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

    /** Key 已过期或在 warnDays 内 → 预警 */
    private boolean isKeyExpiringSoon(GovAiModel row) {
        if (row == null || row.getKeyExpiresAt() == null) {
            return false;
        }
        int warnDays = 14;
        try {
            if (lhProperties.getAi() != null && lhProperties.getAi().getPatrolWarnDays() > 0) {
                warnDays = lhProperties.getAi().getPatrolWarnDays();
            }
        } catch (Exception ignored) {
            // 配置缺失用默认
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, warnDays);
        return !row.getKeyExpiresAt().after(cal.getTime());
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
