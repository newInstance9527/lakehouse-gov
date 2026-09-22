package vip.xiaonuo.lh.modular.ai.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.ai.entity.GovAiActionLog;
import vip.xiaonuo.lh.modular.ai.entity.GovAiSession;
import vip.xiaonuo.lh.modular.ai.entity.GovAiTurn;
import vip.xiaonuo.lh.modular.ai.mapper.GovAiActionLogMapper;
import vip.xiaonuo.lh.modular.ai.mapper.GovAiSessionMapper;
import vip.xiaonuo.lh.modular.ai.mapper.GovAiTurnMapper;
import vip.xiaonuo.lh.modular.ai.param.GovAiChatParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiRunSqlParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiSessionCreateParam;
import vip.xiaonuo.lh.modular.ai.service.GovAiChatService;
import vip.xiaonuo.lh.modular.ai.support.AiEgressPolicy;
import vip.xiaonuo.lh.modular.ai.support.AiPromptGuard;
import vip.xiaonuo.lh.modular.ai.support.AiSqlGuard;
import vip.xiaonuo.lh.modular.ai.support.IntentRouter;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiModelMapper;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbCiteAckParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 助手 P0：意图路由 + KB/指标工具 + SSE
 */
@Service
public class GovAiChatServiceImpl implements GovAiChatService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final long SSE_TIMEOUT = 120_000L;

    private final ExecutorService ssePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lh-ai-sse");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private GovAiSessionMapper sessionMapper;
    @Resource
    private GovAiTurnMapper turnMapper;
    @Resource
    private GovAiActionLogMapper actionLogMapper;
    @Resource
    private GovKbService govKbService;
    @Resource
    private GovMetricService govMetricService;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovAiModelMapper modelMapper;
    @Resource
    private LhLiteLlmClient liteLlmClient;
    @Resource
    private CpQueryService cpQueryService;
    @Resource
    private GovDqService govDqService;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private GovAiModelService govAiModelService;

    @Override
    public Map<String, Object> contextSummary(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        Long assets = assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getWs, workspace));
        long metrics = 0L;
        try {
            Object total = govMetricService.overview(workspace).get("total");
            if (total instanceof Number n) {
                metrics = n.longValue();
            }
        } catch (Exception ignored) {
            metrics = 0L;
        }
        Long models = modelMapper.selectCount(new QueryWrapper<GovAiModel>().lambda()
                .eq(GovAiModel::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAiModel::getWs, workspace).or().eq(GovAiModel::getWs, "*")));
        Map<String, Object> kb = govKbService.overview(workspace);
        List<Map<String, Object>> preferredAssets = new ArrayList<>();
        try {
            List<GovAsset> sample = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .eq(GovAsset::getWs, workspace)
                    .orderByDesc(GovAsset::getUpdateTime)
                    .last("LIMIT 5"));
            for (GovAsset a : sample) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", a.getId());
                row.put("assetCode", a.getAssetCode());
                row.put("name", StrUtil.blankToDefault(a.getCnName(), a.getName()));
                row.put("ws", a.getWs());
                preferredAssets.add(row);
            }
        } catch (Exception ignored) {
            // soft degrade
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("softPrefer", true);
        out.put("preferNote", "当前空间仅影响上下文偏好与软筛选；执行仍经 Grav/即席，不因空间绕过 ACL");
        out.put("assetCount", assets == null ? 0 : assets);
        out.put("metricCount", metrics);
        out.put("modelCount", models == null ? 0 : models);
        out.put("kbCount", kb.getOrDefault("total", 0));
        out.put("kbCiteCnt", kb.getOrDefault("citeCnt", 0));
        out.put("preferredAssets", preferredAssets);
        return out;
    }

    @Override
    public List<Map<String, Object>> listSessions(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String userId = safeUserId();
        QueryWrapper<GovAiSession> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovAiSession::getDeleteFlag, NOT_DELETE)
                .eq(GovAiSession::getWs, workspace)
                .orderByDesc(GovAiSession::getUpdateTime)
                .last("LIMIT 30");
        if (StrUtil.isNotBlank(userId)) {
            qw.lambda().eq(GovAiSession::getUserId, userId);
        }
        List<GovAiSession> rows = sessionMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovAiSession s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("ws", s.getWs());
            m.put("modelOverride", s.getModelOverride());
            m.put("updateTime", s.getUpdateTime());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSession(GovAiSessionCreateParam param) {
        String workspace = StrUtil.blankToDefault(param == null ? null : param.getWs(), WS_DEFAULT);
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        GovAiSession s = new GovAiSession();
        s.setId(IdUtil.getSnowflakeNextIdStr());
        s.setRevision(1);
        s.setStatus("active");
        s.setWs(workspace);
        s.setUserId(user.getId());
        s.setTitle(StrUtil.blankToDefault(param == null ? null : param.getTitle(), "新对话"));
        s.setModelOverride(param == null ? null : param.getModelOverride());
        s.setDeleteFlag(NOT_DELETE);
        sessionMapper.insert(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("title", s.getTitle());
        out.put("ws", s.getWs());
        out.put("modelOverride", s.getModelOverride());
        return out;
    }

    @Override
    public SseEmitter chat(GovAiChatParam param) {
        if (param == null || StrUtil.isBlank(param.getText())) {
            throw new CommonException("text 不能为空");
        }
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        GovAiSession session = ensureSession(param.getSessionId(), workspace, param.getModelOverride());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        ssePool.execute(() -> runChat(emitter, session, param, workspace));
        return emitter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runSql(GovAiRunSqlParam param) {
        if (param == null || StrUtil.isBlank(param.getSql())) {
            throw new CommonException("sql 不能为空");
        }
        if (!Boolean.TRUE.equals(param.getConfirmed())) {
            throw new CommonException("请先二次确认后再执行 SQL（confirmed=true）");
        }
        String reason = AiSqlGuard.blockReason(param.getSql());
        if (reason != null) {
            throw new CommonException(reason);
        }
        String userId = safeUserId();
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String sql = param.getSql().trim();

        // 转发即席接入层（不直连 Trino）
        CpQueryExecParam execParam = new CpQueryExecParam();
        execParam.setSql(sql);
        execParam.setWs(ws);
        execParam.setMaxRows(1000);
        Map<String, Object> execResult = cpQueryService.exec(execParam);

        GovAiActionLog log = new GovAiActionLog();
        log.setId(IdUtil.getSnowflakeNextIdStr());
        log.setSessionId(param.getSessionId());
        log.setTurnId(param.getTurnId());
        log.setAction("run_sql");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sql", sql);
        detail.put("ws", ws);
        detail.put("confirmed", true);
        detail.put("forwarded", "compute_query");
        detail.put("queryId", execResult.get("queryId"));
        detail.put("status", execResult.get("status"));
        detail.put("rowCount", execResult.get("rowCount"));
        detail.put("scanBytes", execResult.get("scanBytes"));
        log.setDetailJson(JSONUtil.toJsonStr(detail));
        log.setCreateTime(new Date());
        log.setCreateUser(userId);
        actionLogMapper.insert(log);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("forwarded", true);
        out.put("sql", sql);
        out.put("actionLogId", log.getId());
        out.put("queryId", execResult.get("queryId"));
        out.put("status", execResult.get("status"));
        out.put("statusLabel", execResult.get("statusLabel"));
        out.put("columns", execResult.get("columns"));
        out.put("columnMeta", execResult.get("columnMeta"));
        out.put("rows", execResult.get("rows"));
        out.put("rowCount", execResult.get("rowCount"));
        out.put("scan", execResult.get("scan"));
        out.put("scanBytes", execResult.get("scanBytes"));
        out.put("scanOverLimit", execResult.get("scanOverLimit"));
        out.put("duration", execResult.get("duration"));
        out.put("maskCols", execResult.get("maskCols"));
        out.put("message", execResult.get("message"));
        out.put("deeplink", "/query?sql=" + java.net.URLEncoder.encode(sql,
                java.nio.charset.StandardCharsets.UTF_8));
        return out;
    }

    private void runChat(SseEmitter emitter, GovAiSession session, GovAiChatParam param, String workspace) {
        long start = System.currentTimeMillis();
        String userId = safeUserId();
        // D5：Prompt PII/密钥扫描（密钥阻断；PII 脱敏后继续）
        AiPromptGuard.ScanResult promptScan = AiPromptGuard.prepareForLlm(param.getText());
        if (promptScan.isBlocked()) {
            try {
                sendEvent(emitter, "done", Map.of(
                        "error", promptScan.getBlockReason(),
                        "promptBlocked", true,
                        "findings", promptScan.getFindings()));
            } catch (Exception ignored) {
                // emitter 可能已关闭
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            return;
        }
        String safeText = promptScan.getRedacted();
        String intent = IntentRouter.route(safeText, param.getScene());
        String turnId = IdUtil.getSnowflakeNextIdStr();
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        String modelId = StrUtil.blankToDefault(param.getModelOverride(), session.getModelOverride());
        String answer;

        try {
            // D5：选用外发模型须安全岗标记（sandbox 豁免）
            assertEgressForChat(modelId, param.getScene());

            // 落库用户轮次（审计保留原文；下发用脱敏文本）
            persistTurn(session.getId(), turnId + "-u", "user", intent, param.getText(),
                    null, null, null, modelId, null, userId);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("intent", intent);
            meta.put("sessionId", session.getId());
            meta.put("turnId", turnId);
            meta.put("ws", workspace);
            if (promptScan.isRedacted()) {
                meta.put("promptRedacted", true);
                meta.put("promptFindings", promptScan.getFindings());
            }
            sendEvent(emitter, "meta", meta);

            // 工具：知识检索（偏好当前 ws，不足时软补全其他空间；非硬隔离）
            if ("docqa".equals(intent) || "explain".equals(intent) || "manual".equals(intent)
                    || StrUtil.containsIgnoreCase(safeText, "手册")
                    || StrUtil.containsIgnoreCase(safeText, "口径")) {
                List<Map<String, Object>> hits = searchKbPreferWs(workspace, safeText, 5);
                for (Map<String, Object> h : hits) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("type", "knowledge");
                    c.put("entryId", h.get("entryId"));
                    c.put("chunkId", h.get("chunkId"));
                    c.put("title", h.get("title"));
                    c.put("text", h.get("text"));
                    c.put("score", h.get("score"));
                    c.put("ws", h.get("ws"));
                    c.put("preferWs", h.get("preferWs"));
                    citations.add(c);
                    sendEvent(emitter, "citation", c);
                }
            }

            // 工具：诊断 → 质量失败规则 + 血缘上游
            String diagnoseTable = null;
            if ("diagnose".equals(intent)
                    || StrUtil.containsIgnoreCase(safeText, "质量")
                    || StrUtil.containsIgnoreCase(safeText, "阻断")
                    || StrUtil.containsIgnoreCase(safeText, "告警")) {
                diagnoseTable = extractTableName(safeText);
                appendDiagnoseCitations(emitter, citations, actions, workspace, diagnoseTable);
            }

            // 工具：resolve_metric / compile_metric → 指标中心 API（口径类强制 metric_code）
            // D6：工作台 schemaContext 走表/列草案，不按指标拒答
            String metricCode = null;
            String metricName = null;
            String compiledSql = null;
            List<Map<String, Object>> schemaContext = normalizeSchemaContext(param.getSchemaContext());
            boolean hasSchemaLink = !schemaContext.isEmpty();
            boolean metricTopic = containsMetricKeyword(safeText);
            if (metricTopic || ("nl2sql".equals(intent) && !hasSchemaLink)) {
                GovMetricVo metric = resolveMetricTool(safeText, workspace);
                if (metric != null && StrUtil.isNotBlank(metric.getMetricCode())) {
                    metricCode = metric.getMetricCode();
                    metricName = metric.getName();
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("type", "metric");
                    c.put("metricCode", metricCode);
                    c.put("title", metricName);
                    c.put("id", metric.getId());
                    c.put("tool", "resolve_metric");
                    citations.add(c);
                    sendEvent(emitter, "citation", c);
                    compiledSql = compileMetricTool(metricCode, workspace);
                    if (StrUtil.isNotBlank(compiledSql)) {
                        Map<String, Object> compileCite = new LinkedHashMap<>();
                        compileCite.put("type", "metric");
                        compileCite.put("metricCode", metricCode);
                        compileCite.put("title", "compile_metric");
                        compileCite.put("tool", "compile_metric");
                        compileCite.put("text", StrUtil.maxLength(compiledSql, 240));
                        citations.add(compileCite);
                        sendEvent(emitter, "citation", compileCite);
                    }
                }
            }
            if (hasSchemaLink) {
                Map<String, Object> schemaCite = new LinkedHashMap<>();
                schemaCite.put("type", "schema");
                schemaCite.put("title", "schema_link");
                schemaCite.put("tool", "schema_link");
                schemaCite.put("text", formatSchemaContextBrief(schemaContext));
                citations.add(schemaCite);
                sendEvent(emitter, "citation", schemaCite);
            }

            // 生成回答：口径关键词无 metric_code 则拒编造；schema linking / 普通草案放行
            if (metricTopic && StrUtil.isBlank(metricCode)) {
                answer = missingMetricCodeAnswer(safeText);
            } else {
                answer = buildAnswer(intent, safeText, citations, metricCode, metricName,
                        compiledSql, modelId, diagnoseTable, schemaContext);
                answer = ensureMetricCodeInAnswer(answer, metricCode);
            }

            // 流式 token（按块推送）
            streamTokens(emitter, answer);

            // 建议动作（不自动执行；工作台可取 action.sql 写入当前窗口）
            if ("nl2sql".equals(intent) || "sql_opt".equals(intent)) {
                if (StrUtil.isNotBlank(compiledSql)) {
                    emitSqlActions(emitter, actions, compiledSql, metricCode);
                } else if (metricTopic && StrUtil.isBlank(metricCode)) {
                    Map<String, Object> metricsLink = new LinkedHashMap<>();
                    metricsLink.put("type", "deeplink");
                    metricsLink.put("label", "打开指标中心");
                    metricsLink.put("href", "/metrics");
                    actions.add(metricsLink);
                    sendEvent(emitter, "action", metricsLink);
                } else {
                    String sql = heuristicSql(safeText, schemaContext);
                    emitSqlActions(emitter, actions, sql, null);
                }
            }
            if (!citations.isEmpty()) {
                Map<String, Object> kbLink = new LinkedHashMap<>();
                kbLink.put("type", "deeplink");
                kbLink.put("label", "查看知识库引用");
                Object entryId = citations.get(0).get("entryId");
                kbLink.put("href", "/knowledge?entry=" + (entryId == null ? "" : entryId));
                actions.add(kbLink);
                sendEvent(emitter, "action", kbLink);
            }

            int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
            int promptTok = estimateTokens(safeText);
            int completionTok = estimateTokens(answer);
            persistTurn(session.getId(), turnId, "assistant", intent, answer,
                    JSONUtil.toJsonStr(citations),
                    promptTok, completionTok,
                    modelId, latency, userId);
            try {
                govAiModelService.recordUsage(workspace, modelId, promptTok, completionTok);
            } catch (Exception ignored) {
                // soft-fail
            }

            // 引用回写
            if (!citations.isEmpty()) {
                GovKbCiteAckParam ack = new GovKbCiteAckParam();
                ack.setWs(workspace);
                List<String> entryIds = new ArrayList<>();
                List<String> chunkIds = new ArrayList<>();
                for (Map<String, Object> c : citations) {
                    if (c.get("entryId") != null) {
                        entryIds.add(String.valueOf(c.get("entryId")));
                    }
                    if (c.get("chunkId") != null) {
                        chunkIds.add(String.valueOf(c.get("chunkId")));
                    }
                }
                ack.setEntryIds(entryIds);
                ack.setChunkIds(chunkIds);
                try {
                    govKbService.ackCitation(ack);
                } catch (Exception ignored) {
                    // 非关键路径
                }
            }

            // 更新会话标题
            if ("新对话".equals(session.getTitle()) || StrUtil.isBlank(session.getTitle())) {
                session.setTitle(StrUtil.maxLength(safeText.trim(), 40));
                sessionMapper.updateById(session);
            }

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("turnId", turnId);
            done.put("intent", intent);
            done.put("latencyMs", latency);
            done.put("citationCount", citations.size());
            done.put("promptTokens", promptTok);
            done.put("completionTokens", completionTok);
            sendEvent(emitter, "done", done);
            emitter.complete();
        } catch (Exception e) {
            try {
                sendEvent(emitter, "done", Map.of("error", StrUtil.blankToDefault(e.getMessage(), "chat_failed")));
            } catch (Exception ignored) {
                // ignore
            }
            emitter.completeWithError(e);
        }
    }

    /** D5：chat 选用外发模型时校验安全岗标记 */
    private void assertEgressForChat(String modelId, String scene) {
        if (StrUtil.isBlank(modelId)) {
            return;
        }
        GovAiModel model = modelMapper.selectById(modelId);
        if (model == null || !NOT_DELETE.equals(model.getDeleteFlag())) {
            return;
        }
        AiEgressPolicy.assertAllowed(model, scene);
    }

    private String buildAnswer(String intent, String text, List<Map<String, Object>> citations,
                               String metricCode, String metricName, String compiledSql,
                               String modelId, String diagnoseTable,
                               List<Map<String, Object>> schemaContext) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("意图=").append(intent).append('\n');
        if (StrUtil.isNotBlank(diagnoseTable)) {
            ctx.append("诊断表=").append(diagnoseTable).append('\n');
        }
        if (StrUtil.isNotBlank(metricCode)) {
            ctx.append("metric_code=").append(metricCode);
            if (StrUtil.isNotBlank(metricName)) {
                ctx.append(" · ").append(metricName);
            }
            ctx.append('\n');
        }
        if (StrUtil.isNotBlank(compiledSql)) {
            ctx.append("编译SQL=\n").append(compiledSql).append('\n');
        }
        if (schemaContext != null && !schemaContext.isEmpty()) {
            ctx.append("schema_link=\n").append(formatSchemaContextBrief(schemaContext)).append('\n');
        }
        for (Map<String, Object> c : citations) {
            String citeText = String.valueOf(c.getOrDefault("text", ""));
            AiPromptGuard.ScanResult citeScan = AiPromptGuard.prepareForLlm(citeText);
            if (citeScan.isBlocked()) {
                citeText = "***REDACTED_SECRET***";
            } else {
                citeText = citeScan.getRedacted();
            }
            ctx.append("- [").append(c.get("type")).append("] ")
                    .append(c.getOrDefault("title", "")).append(": ")
                    .append(StrUtil.maxLength(citeText, 200))
                    .append('\n');
        }
        if (liteLlmClient.available()) {
            String llm = liteLlmClient.chatSimple(modelId,
                    "你是湖仓治理助手 DataLake Copilot。基于给定上下文回答；"
                            + "引用业务口径必须给出 metric_code；禁止编造 GMV 等生产口径或直出生产 SQL；"
                            + "有编译 SQL 时原样引用并标注 metric_code；"
                            + "有 schema_link 时可生成只读 SELECT 草案（LIMIT≤100），禁止 DDL/DML；"
                            + "禁止复述用户明文手机号/证件/密钥。",
                    "用户问题：\n" + text + "\n\n上下文：\n" + ctx);
            if (StrUtil.isNotBlank(llm)) {
                return llm;
            }
        }
        return heuristicAnswer(intent, text, citations, metricCode, metricName, compiledSql,
                diagnoseTable, schemaContext);
    }

    private String heuristicAnswer(String intent, String text, List<Map<String, Object>> citations,
                                   String metricCode, String metricName, String compiledSql,
                                   String diagnoseTable, List<Map<String, Object>> schemaContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(intentLabel(intent)).append("\n\n");
        switch (intent) {
            case "nl2sql" -> {
                if (StrUtil.isNotBlank(metricCode) && StrUtil.isNotBlank(compiledSql)) {
                    sb.append("已按指标 `metric_code`=**").append(metricCode).append("**");
                    if (StrUtil.isNotBlank(metricName)) {
                        sb.append("（").append(metricName).append("）");
                    }
                    sb.append(" 经编译器生成 SQL：\n\n```sql\n");
                    sb.append(compiledSql);
                    sb.append("\n```\n\n请写入工作台或在即席查询打开后二次确认再执行。\n");
                } else if (StrUtil.isNotBlank(metricCode)) {
                    sb.append("已解析到 `metric_code`=**").append(metricCode).append("**");
                    if (StrUtil.isNotBlank(metricName)) {
                        sb.append("（").append(metricName).append("）");
                    }
                    sb.append("，但编译失败。请到指标中心检查定义后重试，禁止手写生产口径。\n");
                } else {
                    sb.append("已按只读约束生成 schema 草案 SQL");
                    if (schemaContext != null && !schemaContext.isEmpty()) {
                        sb.append("（左树 schema linking）");
                    } else {
                        sb.append("（非指标口径）");
                    }
                    sb.append("：\n\n```sql\n");
                    sb.append(heuristicSql(text, schemaContext));
                    sb.append("\n```\n\n不会自动执行；请确认后写入数据服务当前 SQL 窗口或在即席二次确认。\n");
                }
            }
            case "gen_script" -> sb.append("Flink 脚本建议：从数据源登记表选择输入/输出，使用门户 ETL 模板生成 FlinkSQL；"
                    + "本 P0 返回示意：\n\n```sql\n-- FlinkSQL draft\n"
                    + "CREATE TEMPORARY VIEW src AS SELECT * FROM iceberg.default.dwd_order_detail WHERE dt = '${dt}';\n"
                    + "INSERT INTO iceberg.default.ads_demo SELECT * FROM src;\n```\n");
            case "diagnose" -> appendDiagnoseAnswer(sb, citations, diagnoseTable);
            case "sql_opt" -> sb.append("优化建议：补充分区谓词（如 `dt`）、避免 SELECT *、加 LIMIT；"
                    + "可用 EXPLAIN 评估扫描量。\n");
            case "explain" -> {
                sb.append("解释：基于知识库与资产元数据给出说明。");
                if (StrUtil.isNotBlank(metricCode)) {
                    sb.append(" 口径绑定 `metric_code`=**").append(metricCode).append("**。");
                }
            }
            default -> {
                if (citations.stream().anyMatch(c -> "rule".equals(c.get("type")) || "lineage".equals(c.get("type")))) {
                    appendDiagnoseAnswer(sb, citations, diagnoseTable);
                } else if (StrUtil.isNotBlank(metricCode)) {
                    sb.append("口径说明见指标 `metric_code`=**").append(metricCode).append("**");
                    if (StrUtil.isNotBlank(metricName)) {
                        sb.append("（").append(metricName).append("）");
                    }
                    sb.append("；请以指标中心定义为准，勿手写聚合口径。");
                } else {
                    sb.append("根据知识库检索结果整理如下。");
                }
            }
        }
        if (!citations.isEmpty()) {
            sb.append("\n\n**引用**\n");
            int i = 1;
            for (Map<String, Object> c : citations) {
                sb.append(i++).append(". ");
                String type = String.valueOf(c.getOrDefault("type", ""));
                switch (type) {
                    case "metric" -> sb.append("[指标 ").append(c.get("metricCode")).append("] ").append(c.get("title"));
                    case "schema" -> sb.append("[Schema] ").append(c.get("title"));
                    case "rule" -> sb.append("[质量] ").append(c.get("title"));
                    case "lineage" -> sb.append("[血缘] ").append(c.get("title"));
                    default -> sb.append("[知识] ").append(c.get("title"));
                }
                sb.append('\n');
            }
        } else if ("docqa".equals(intent) || "explain".equals(intent)) {
            sb.append("\n\n未命中知识库条目，请补充关键词或先在知识库录入口径说明。\n");
        }
        return sb.toString();
    }

    private void appendDiagnoseAnswer(StringBuilder sb, List<Map<String, Object>> citations, String table) {
        sb.append("诊断结果");
        if (StrUtil.isNotBlank(table)) {
            sb.append("（焦点表 `").append(table).append("`）");
        }
        sb.append("：\n\n");
        List<Map<String, Object>> rules = citations.stream().filter(c -> "rule".equals(c.get("type"))).toList();
        List<Map<String, Object>> lineage = citations.stream().filter(c -> "lineage".equals(c.get("type"))).toList();
        if (rules.isEmpty() && lineage.isEmpty()) {
            sb.append("1. 未检索到失败/告警质量规则（质量服务可能降级或表名未识别）\n");
            sb.append("2. 建议打开质量中心与血缘影响分析继续排查\n");
            return;
        }
        if (!rules.isEmpty()) {
            sb.append("**失败/告警规则**\n");
            int i = 1;
            for (Map<String, Object> r : rules) {
                sb.append(i++).append(". ").append(r.getOrDefault("title", r.get("ruleCode")))
                        .append(" — ").append(StrUtil.blankToDefault(String.valueOf(r.getOrDefault("text", "")), "fail/warn"))
                        .append('\n');
            }
            sb.append('\n');
        }
        if (!lineage.isEmpty()) {
            sb.append("**上游影响**\n");
            int i = 1;
            for (Map<String, Object> l : lineage) {
                sb.append(i++).append(". ").append(l.getOrDefault("title", "")).append('\n');
            }
            sb.append('\n');
        }
        sb.append("建议：先修复阻断级规则，再沿血缘上游核对产出作业。\n");
    }

    /**
     * 诊断工具：质量 pageRules + 血缘 impact；任一失败则软降级。
     */
    private void appendDiagnoseCitations(SseEmitter emitter, List<Map<String, Object>> citations,
                                         List<Map<String, Object>> actions, String workspace, String table)
            throws IOException {
        // 质量规则
        try {
            GovDqPageParam qp = new GovDqPageParam();
            qp.setWs(workspace);
            if (StrUtil.isNotBlank(table)) {
                qp.setTableName(table);
            }
            qp.setStatus("fail");
            Page<GovDqRuleVo> failPage = govDqService.pageRules(qp);
            List<GovDqRuleVo> rules = new ArrayList<>();
            if (failPage != null && failPage.getRecords() != null) {
                rules.addAll(failPage.getRecords());
            }
            qp.setStatus("warn");
            Page<GovDqRuleVo> warnPage = govDqService.pageRules(qp);
            if (warnPage != null && warnPage.getRecords() != null) {
                for (GovDqRuleVo v : warnPage.getRecords()) {
                    boolean dup = rules.stream().anyMatch(r -> StrUtil.equals(r.getId(), v.getId()));
                    if (!dup) {
                        rules.add(v);
                    }
                }
            }
            // tableName 精确无命中时，降级为 q 模糊
            if (rules.isEmpty() && StrUtil.isNotBlank(table)) {
                GovDqPageParam loose = new GovDqPageParam();
                loose.setWs(workspace);
                loose.setQ(table);
                Page<GovDqRuleVo> all = govDqService.pageRules(loose);
                if (all != null && all.getRecords() != null) {
                    for (GovDqRuleVo v : all.getRecords()) {
                        if (v.getTableName() != null && StrUtil.containsIgnoreCase(v.getTableName(), table)
                                && (Boolean.FALSE.equals(v.getPass()) || Boolean.TRUE.equals(v.getBlocked()))) {
                            rules.add(v);
                        }
                    }
                }
            }
            int n = 0;
            for (GovDqRuleVo v : rules) {
                if (n++ >= 8) {
                    break;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("type", "rule");
                c.put("id", v.getId());
                c.put("ruleCode", v.getRuleCode());
                c.put("title", StrUtil.blankToDefault(v.getRuleCode(), "规则") + " @ "
                        + StrUtil.blankToDefault(v.getTableName(), "?"));
                c.put("text", StrUtil.blankToDefault(v.getStatusText(),
                        Boolean.FALSE.equals(v.getPass()) ? "fail" : "warn")
                        + (StrUtil.isNotBlank(v.getMessage()) ? (" · " + v.getMessage()) : ""));
                c.put("tableName", v.getTableName());
                c.put("pass", v.getPass());
                c.put("blocked", v.getBlocked());
                citations.add(c);
                sendEvent(emitter, "citation", c);
            }
        } catch (Exception ignored) {
            // soft-fail quality
        }

        // 血缘上游
        try {
            if (StrUtil.isNotBlank(table)) {
                Map<String, Object> impact = govLineageService.impact(null, table, null, 3, 1, workspace);
                if (impact != null) {
                    Object upObj = impact.get("up");
                    if (upObj instanceof List<?> upList) {
                        int n = 0;
                        for (Object item : upList) {
                            if (n++ >= 8) {
                                break;
                            }
                            String title;
                            String text;
                            if (item instanceof Map<?, ?> m) {
                                Object titleObj = m.get("table");
                                if (titleObj == null) {
                                    titleObj = m.get("name");
                                }
                                if (titleObj == null) {
                                    titleObj = m.get("label");
                                }
                                if (titleObj == null) {
                                    titleObj = item;
                                }
                                title = String.valueOf(titleObj);
                                Object textObj = m.get("role");
                                if (textObj == null) {
                                    textObj = m.get("dir");
                                }
                                text = textObj == null ? "上游" : String.valueOf(textObj);
                            } else {
                                title = String.valueOf(item);
                                text = "上游";
                            }
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("type", "lineage");
                            c.put("title", title);
                            c.put("text", text);
                            c.put("focus", impact.get("focus"));
                            citations.add(c);
                            sendEvent(emitter, "citation", c);
                        }
                    }
                    Map<String, Object> linCite = new LinkedHashMap<>();
                    linCite.put("type", "lineage");
                    linCite.put("title", "影响分析 · " + impact.getOrDefault("focus", table));
                    linCite.put("text", "上游 " + impact.getOrDefault("upCount", 0)
                            + " / 下游 " + impact.getOrDefault("downCount", 0));
                    // 若已有明细则不重复塞汇总；无上游时仍给一条汇总
                    boolean hasDetail = citations.stream().anyMatch(c ->
                            "lineage".equals(c.get("type")) && !StrUtil.startWith(String.valueOf(c.get("title")), "影响分析"));
                    if (!hasDetail) {
                        citations.add(linCite);
                        sendEvent(emitter, "citation", linCite);
                    }
                }
            }
        } catch (Exception ignored) {
            // soft-fail lineage
        }

        // deeplink
        Map<String, Object> qLink = new LinkedHashMap<>();
        qLink.put("type", "deeplink");
        qLink.put("label", "打开质量中心");
        qLink.put("href", StrUtil.isNotBlank(table) ? "/quality?table=" + table : "/quality");
        actions.add(qLink);
        sendEvent(emitter, "action", qLink);

        if (StrUtil.isNotBlank(table)) {
            Map<String, Object> lLink = new LinkedHashMap<>();
            lLink.put("type", "deeplink");
            lLink.put("label", "打开血缘影响");
            lLink.put("href", "/lineage?focus=" + java.net.URLEncoder.encode(table,
                    java.nio.charset.StandardCharsets.UTF_8));
            actions.add(lLink);
            sendEvent(emitter, "action", lLink);
        }
    }

    /** 启发式抽表名：ods_/dwd_/ads_/dim_/dws_ 或反引号/标识符 */
    private static String extractTableName(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        Matcher layer = Pattern.compile("(?i)\\b((?:ods|dwd|ads|dim|dws)_[a-z0-9_]+)\\b").matcher(text);
        if (layer.find()) {
            return layer.group(1);
        }
        Matcher tick = Pattern.compile("`([a-zA-Z_][a-zA-Z0-9_]{2,})`").matcher(text);
        if (tick.find()) {
            return tick.group(1);
        }
        Matcher fqn = Pattern.compile("(?i)\\b([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]+)\\b").matcher(text);
        if (fqn.find()) {
            String full = fqn.group(1);
            int dot = full.lastIndexOf('.');
            return dot >= 0 ? full.substring(dot + 1) : full;
        }
        return null;
    }

    private String heuristicSql(String text, List<Map<String, Object>> schemaContext) {
        if (schemaContext != null && !schemaContext.isEmpty()) {
            Map<String, Object> first = schemaContext.get(0);
            String schema = String.valueOf(first.getOrDefault("schema", "")).trim();
            String table = String.valueOf(first.getOrDefault("table",
                    first.getOrDefault("name", ""))).trim();
            if (StrUtil.isNotBlank(table)) {
                String selectList = "*";
                Object colsObj = first.get("columns");
                if (colsObj instanceof List<?> cols && !cols.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (Object c : cols) {
                        if (c instanceof Map<?, ?> m) {
                            Object n = m.get("name");
                            if (n != null && StrUtil.isNotBlank(String.valueOf(n))) {
                                names.add(String.valueOf(n).trim());
                            }
                        } else if (c != null && StrUtil.isNotBlank(String.valueOf(c))) {
                            names.add(String.valueOf(c).trim());
                        }
                    }
                    if (!names.isEmpty()) {
                        selectList = String.join(", ", names);
                    }
                }
                String fqn = StrUtil.isNotBlank(schema) ? schema + "." + table : table;
                return "SELECT " + selectList + "\nFROM " + fqn + "\nWHERE 1 = 1\nLIMIT 100";
            }
        }
        String table = extractTableName(text);
        if (StrUtil.isNotBlank(table)) {
            return "SELECT *\nFROM iceberg.default." + table + "\n"
                    + "WHERE dt = date_format(date_add('day', -1, current_date), '%Y-%m-%d')\n"
                    + "LIMIT 100";
        }
        return "SELECT *\nFROM iceberg.default.dwd_order_detail\n"
                + "WHERE dt = date_format(date_add('day', -1, current_date), '%Y-%m-%d')\n"
                + "LIMIT 100";
    }

    private void emitSqlActions(SseEmitter emitter, List<Map<String, Object>> actions,
                                String sql, String metricCode) throws IOException {
        if (StrUtil.isBlank(sql)) {
            return;
        }
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "apply_sql");
        apply.put("label", "写入数据服务 SQL 窗口");
        apply.put("sql", sql);
        apply.put("autoExecute", false);
        if (StrUtil.isNotBlank(metricCode)) {
            apply.put("metricCode", metricCode);
        }
        actions.add(apply);
        sendEvent(emitter, "action", apply);

        Map<String, Object> open = new LinkedHashMap<>();
        open.put("type", "deeplink");
        open.put("label", "在即席查询打开");
        open.put("href", "/query?sql=" + java.net.URLEncoder.encode(sql,
                java.nio.charset.StandardCharsets.UTF_8));
        open.put("sql", sql);
        if (StrUtil.isNotBlank(metricCode)) {
            open.put("metricCode", metricCode);
        }
        actions.add(open);
        sendEvent(emitter, "action", open);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("type", "run_sql");
        run.put("label", "试跑（需二次确认）");
        run.put("sql", sql);
        if (StrUtil.isNotBlank(metricCode)) {
            run.put("metricCode", metricCode);
        }
        actions.add(run);
        sendEvent(emitter, "action", run);
    }

    private static List<Map<String, Object>> normalizeSchemaContext(List<Map<String, Object>> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Map<String, Object> item : raw) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String schema = String.valueOf(item.getOrDefault("schema", "")).trim();
            String table = String.valueOf(item.getOrDefault("table",
                    item.getOrDefault("name", ""))).trim();
            if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema", schema);
            row.put("table", table);
            List<Map<String, Object>> cols = new ArrayList<>();
            Object colsObj = item.get("columns");
            if (colsObj instanceof List<?> list) {
                for (Object c : list) {
                    if (c instanceof Map<?, ?> m) {
                        Object n = m.get("name");
                        if (n == null || StrUtil.isBlank(String.valueOf(n))) {
                            continue;
                        }
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", String.valueOf(n).trim());
                        if (m.get("type") != null) {
                            col.put("type", String.valueOf(m.get("type")));
                        }
                        cols.add(col);
                    } else if (c != null && StrUtil.isNotBlank(String.valueOf(c))) {
                        cols.add(Map.of("name", String.valueOf(c).trim()));
                    }
                }
            }
            row.put("columns", cols);
            out.add(row);
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private static String formatSchemaContextBrief(List<Map<String, Object>> schemaContext) {
        if (schemaContext == null || schemaContext.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : schemaContext) {
            String schema = String.valueOf(t.getOrDefault("schema", "")).trim();
            String table = String.valueOf(t.getOrDefault("table", "")).trim();
            String fqn = StrUtil.isNotBlank(schema) ? schema + "." + table : table;
            sb.append(fqn);
            Object colsObj = t.get("columns");
            if (colsObj instanceof List<?> cols && !cols.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object c : cols) {
                    if (c instanceof Map<?, ?> m && m.get("name") != null) {
                        names.add(String.valueOf(m.get("name")));
                    }
                }
                if (!names.isEmpty()) {
                    sb.append("(").append(String.join(",", names)).append(")");
                }
            }
            sb.append("; ");
        }
        return sb.toString().trim();
    }

    /**
     * resolve_metric：优先当前 ws，未命中则全局软回退（不硬隔离）。
     */
    private GovMetricVo resolveMetricTool(String text, String ws) {
        String code = extractMetricCode(text);
        if (StrUtil.isNotBlank(code)) {
            try {
                return govMetricService.detail(code, ws);
            } catch (Exception ignored) {
                try {
                    return govMetricService.detail(code, null);
                } catch (Exception ignored2) {
                    // 编码未命中则关键词检索
                }
            }
        }
        String kw = extractMetricKeyword(text);
        List<GovMetricVo> hits = searchMetricsViaApi(ws, kw);
        if (hits.isEmpty()) {
            hits = searchMetricsViaApi(null, kw);
        }
        if (hits.isEmpty() && !"GMV".equalsIgnoreCase(kw)) {
            if (containsMetricKeyword(text) && StrUtil.containsIgnoreCase(text, "GMV")) {
                hits = searchMetricsViaApi(ws, "GMV");
                if (hits.isEmpty()) {
                    hits = searchMetricsViaApi(null, "GMV");
                }
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        String q = StrUtil.nullToEmpty(text);
        for (GovMetricVo m : hits) {
            if (StrUtil.containsIgnoreCase(q, m.getMetricCode())
                    || StrUtil.containsIgnoreCase(q, StrUtil.nullToEmpty(m.getName()))) {
                return m;
            }
        }
        for (GovMetricVo m : hits) {
            if ("active".equalsIgnoreCase(m.getStatus())) {
                return m;
            }
        }
        return hits.get(0);
    }

    /**
     * 知识检索软偏好：先当前 ws，不足 topK 时从全空间补全（标记 preferWs）。
     */
    private List<Map<String, Object>> searchKbPreferWs(String preferWs, String query, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        GovKbSearchParam preferred = new GovKbSearchParam();
        preferred.setWs(preferWs);
        preferred.setQuery(query);
        preferred.setTopK(topK);
        try {
            for (Map<String, Object> h : govKbService.search(preferred)) {
                String key = String.valueOf(h.getOrDefault("chunkId", h.get("entryId")));
                if (!seen.add(key)) {
                    continue;
                }
                h.put("preferWs", true);
                h.put("ws", preferWs);
                out.add(h);
            }
        } catch (Exception ignored) {
            // soft degrade
        }
        if (out.size() >= topK) {
            return out.subList(0, topK);
        }
        GovKbSearchParam all = new GovKbSearchParam();
        all.setWs(null);
        all.setQuery(query);
        all.setTopK(topK * 2);
        try {
            for (Map<String, Object> h : govKbService.search(all)) {
                String key = String.valueOf(h.getOrDefault("chunkId", h.get("entryId")));
                if (!seen.add(key)) {
                    continue;
                }
                h.putIfAbsent("preferWs", false);
                out.add(h);
                if (out.size() >= topK) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // soft degrade
        }
        return out;
    }

    /** compile_metric：POST /lh/metric/compile */
    private String compileMetricTool(String metricCode, String ws) {
        if (StrUtil.isBlank(metricCode)) {
            return null;
        }
        try {
            GovMetricCompileParam cp = new GovMetricCompileParam();
            cp.setMetricCode(metricCode);
            cp.setWs(ws);
            cp.setDialect("trino");
            Map<String, Object> compiled = govMetricService.compile(cp);
            if (compiled == null) {
                return null;
            }
            Object sqlObj = compiled.get("sqlText");
            if (sqlObj == null) {
                sqlObj = compiled.get("sql");
            }
            if (sqlObj == null) {
                sqlObj = compiled.get("compiledSql");
            }
            return sqlObj == null ? null : String.valueOf(sqlObj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<GovMetricVo> searchMetricsViaApi(String ws, String q) {
        if (StrUtil.isBlank(q)) {
            return List.of();
        }
        try {
            GovMetricPageParam param = new GovMetricPageParam();
            param.setWs(ws);
            param.setQ(q.trim());
            Page<GovMetricVo> page = govMetricService.page(param);
            if (page == null || page.getRecords() == null) {
                return List.of();
            }
            return page.getRecords();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String missingMetricCodeAnswer(String text) {
        return "### 口径 / SQL\n\n"
                + "未能解析到 `metric_code`。问 GMV 等业务口径时必须绑定指标中心编码"
                + "（如 `M-0001`），禁止模型直出生产聚合 SQL。\n\n"
                + "请补充指标编码，或先在指标中心登记后再提问"
                + (StrUtil.isNotBlank(text) ? "：「" + StrUtil.maxLength(text.trim(), 40) + "」" : "")
                + "。\n";
    }

    private static String ensureMetricCodeInAnswer(String answer, String metricCode) {
        if (StrUtil.isBlank(metricCode) || StrUtil.isBlank(answer)) {
            return answer;
        }
        if (StrUtil.containsIgnoreCase(answer, metricCode)) {
            return answer;
        }
        return answer + "\n\n`metric_code`: **" + metricCode + "**\n";
    }

    /** 抽取 A-/M-/C- 形态编码 */
    private static String extractMetricCode(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)\\b([AMC]-\\d{4,})\\b").matcher(text);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String extractMetricKeyword(String text) {
        if (StrUtil.containsIgnoreCase(text, "GMV")) {
            return "GMV";
        }
        String code = extractMetricCode(text);
        if (StrUtil.isNotBlank(code)) {
            return code;
        }
        String t = StrUtil.nullToEmpty(text).replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (t.length() > 24) {
            t = t.substring(0, 24);
        }
        return StrUtil.blankToDefault(t, "指标");
    }

    private static boolean containsMetricKeyword(String text) {
        String t = StrUtil.nullToEmpty(text).toLowerCase(Locale.ROOT);
        return t.contains("gmv") || t.contains("指标") || t.contains("口径")
                || t.contains("metric") || Pattern.compile("(?i)\\b[amc]-\\d{4,}\\b").matcher(t).find();
    }

    private GovAiSession ensureSession(String sessionId, String ws, String modelOverride) {
        if (StrUtil.isNotBlank(sessionId)) {
            GovAiSession s = sessionMapper.selectById(sessionId);
            if (s != null && NOT_DELETE.equals(s.getDeleteFlag())) {
                return s;
            }
        }
        GovAiSessionCreateParam p = new GovAiSessionCreateParam();
        p.setWs(ws);
        p.setModelOverride(modelOverride);
        p.setTitle("新对话");
        Map<String, Object> created = createSession(p);
        return sessionMapper.selectById(String.valueOf(created.get("id")));
    }

    private void persistTurn(String sessionId, String id, String role, String intent, String content,
                             String citationsJson, Integer promptTokens, Integer completionTokens,
                             String modelId, Integer latencyMs, String userId) {
        GovAiTurn turn = new GovAiTurn();
        turn.setId(id);
        turn.setSessionId(sessionId);
        turn.setRole(role);
        turn.setIntent(intent);
        turn.setContent(content);
        turn.setCitationsJson(citationsJson);
        turn.setPromptTokens(promptTokens);
        turn.setCompletionTokens(completionTokens);
        turn.setModelId(modelId);
        turn.setLatencyMs(latencyMs);
        turn.setCreateTime(new Date());
        turn.setCreateUser(userId);
        turnMapper.insert(turn);
    }

    private void streamTokens(SseEmitter emitter, String answer) throws IOException {
        if (StrUtil.isBlank(answer)) {
            return;
        }
        int step = 48;
        for (int i = 0; i < answer.length(); i += step) {
            String part = answer.substring(i, Math.min(answer.length(), i + step));
            sendEvent(emitter, "token", Map.of("text", part));
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(JSONUtil.toJsonStr(data)));
    }

    private static int estimateTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 1.5));
    }

    private static String intentLabel(String intent) {
        return switch (StrUtil.nullToEmpty(intent)) {
            case "nl2sql" -> "生成 SQL";
            case "gen_script" -> "生成脚本";
            case "docqa" -> "知识问答";
            case "diagnose" -> "诊断建议";
            case "explain" -> "解释说明";
            case "sql_opt" -> "SQL 优化";
            default -> "助手回复";
        };
    }

    private static String safeUserId() {
        try {
            return LhLoginUsers.requireUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
