package vip.xiaonuo.lh.modular.ai.service.impl;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.ai.LhOpenAiCompatClient;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
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
import vip.xiaonuo.lh.modular.ai.support.AiSchemaContextAcl;
import vip.xiaonuo.lh.modular.ai.support.AiSqlDraftHelper;
import vip.xiaonuo.lh.modular.ai.support.AiSqlGuard;
import vip.xiaonuo.lh.modular.ai.support.IntentRouter;
import vip.xiaonuo.lh.modular.ai.agent.GovAiAgentOrchestrator;
import vip.xiaonuo.lh.modular.ai.support.AiAssetReadAccess;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiModelMapper;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiRouteVo;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetIdParam;
import vip.xiaonuo.lh.modular.catalog.service.GovAssetService;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbCiteAckParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

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
@Slf4j
@Service
public class GovAiChatServiceImpl implements GovAiChatService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final long SSE_TIMEOUT = 120_000L;
    private static final String DEFAULT_CHAT_MODEL = "aim_datagoo_dsflash";

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
    private GovAssetSourceLinkMapper assetSourceLinkMapper;
    @Resource
    private GovAssetService govAssetService;
    @Resource
    private GovAiModelMapper modelMapper;
    @Resource
    private LhLiteLlmClient liteLlmClient;
    @Resource
    private LhOpenAiCompatClient openAiCompatClient;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private CpQueryService cpQueryService;
    @Resource
    private GovDqService govDqService;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private GovAiModelService govAiModelService;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private AiAssetReadAccess aiAssetReadAccess;
    @Resource
    private GovAiAgentOrchestrator agentOrchestrator;

    @Override
    public Map<String, Object> contextSummary(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String userId = safeUserId();
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
        List<Map<String, Object>> preferredAssets = listMyAssets(workspace, 8);
        String routeModel = resolveModelFromRoute("nl2sql", null, null);
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
        out.put("myAssetCount", preferredAssets.size());
        out.put("routeModelId", routeModel);
        out.put("routeModelName", modelDisplayName(routeModel));
        out.put("userId", userId);
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
    public List<Map<String, Object>> listTurns(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return List.of();
        }
        GovAiSession session = sessionMapper.selectById(sessionId);
        if (session == null || !NOT_DELETE.equals(session.getDeleteFlag())) {
            throw new CommonException("会话不存在");
        }
        String userId = safeUserId();
        if (StrUtil.isNotBlank(userId) && StrUtil.isNotBlank(session.getUserId())
                && !userId.equals(session.getUserId())) {
            throw new CommonException("无权查看该会话");
        }
        List<GovAiTurn> rows = turnMapper.selectList(new QueryWrapper<GovAiTurn>().lambda()
                .eq(GovAiTurn::getSessionId, sessionId)
                .orderByAsc(GovAiTurn::getCreateTime)
                .last("LIMIT 200"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovAiTurn t : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("role", t.getRole());
            m.put("intent", t.getIntent());
            m.put("content", t.getContent());
            m.put("citationsJson", t.getCitationsJson());
            m.put("modelId", t.getModelId());
            m.put("createTime", t.getCreateTime());
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
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new CommonException("sessionId 不能为空");
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        GovAiSession session = sessionMapper.selectById(sessionId);
        if (session == null || !NOT_DELETE.equals(session.getDeleteFlag())) {
            throw new CommonException("会话不存在或已删除");
        }
        if (StrUtil.isNotBlank(session.getUserId()) && !user.getId().equals(session.getUserId())
                && !LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("无权删除该会话");
        }
        session.setDeleteFlag("DELETED");
        session.setStatus("deleted");
        session.setUpdateTime(new Date());
        session.setRevision(session.getRevision() == null ? 1 : session.getRevision() + 1);
        sessionMapper.updateById(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", sessionId);
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
        // 请求线程捕获登录态；SSE 异步线程无 Servlet 上下文
        String tokenValue = null;
        try {
            tokenValue = StpUtil.getTokenValue();
        } catch (Exception ignored) {
            // ignore
        }
        final String token = tokenValue;
        SaBaseLoginUser loginUser = LhLoginUsers.currentUserOrNull();
        ssePool.execute(() -> {
            SaTokenContextMockUtil.setMockContext();
            try {
                if (StrUtil.isNotBlank(token)) {
                    try {
                        StpUtil.setTokenValue(token);
                    } catch (Exception ignored) {
                        // token 绑定失败时仍可用 runAs 覆盖
                    }
                }
                if (loginUser != null) {
                    LhLoginUsers.runAs(loginUser, () -> runChat(emitter, session, param, workspace));
                } else {
                    runChat(emitter, session, param, workspace);
                }
            } finally {
                SaTokenContextMockUtil.clearContext();
            }
        });
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
        // 2B：默认只读探索智能体；生成类 scene 或 Agent 失败 → 固定意图链兜底
        if (!IntentRouter.isGenerationBypassScene(param.getScene())) {
            if (tryRunAgentChat(emitter, session, param, workspace, safeText, promptScan, start, userId)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // ignore
                }
                return;
            }
        }
        String intent = IntentRouter.route(safeText, param.getScene());
        // 雪花 id 约 19 位，须各自独立生成以适配 gov_ai_turn.id varchar(20)；勿用 turnId+"-u" 后缀（会超长截断）
        String userTurnId = IdUtil.getSnowflakeNextIdStr();
        String turnId = IdUtil.getSnowflakeNextIdStr();
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();
        String modelId = resolveModel(param, session, intent);
        String answer;

        try {
            // D5：选用外发模型须安全岗标记（sandbox 豁免）
            assertEgressForChat(modelId, IntentRouter.modelScene(intent));

            // 落库用户轮次（审计保留原文；下发用脱敏文本）
            persistTurn(session.getId(), userTurnId, "user", intent, param.getText(),
                    null, null, null, modelId, null, userId);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("intent", intent);
            meta.put("sessionId", session.getId());
            meta.put("turnId", turnId);
            meta.put("ws", workspace);
            meta.put("modelId", modelId);
            meta.put("modelName", modelDisplayName(modelId));
            if (promptScan.isRedacted()) {
                meta.put("promptRedacted", true);
                meta.put("promptFindings", promptScan.getFindings());
            }
            sendEvent(emitter, "meta", meta);

            // 工具：知识检索（docqa/explain/manual；nl2sql/ask_data 涉及口径时也开）
            boolean needKb = "docqa".equals(intent) || "explain".equals(intent) || "manual".equals(intent)
                    || StrUtil.containsIgnoreCase(safeText, "手册")
                    || IntentRouter.needsKbForCaliber(safeText)
                    || (("nl2sql".equals(intent) || "ask_data".equals(intent))
                        && IntentRouter.needsKbForCaliber(safeText));
            if (needKb) {
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
                    c.put("scope", h.get("scope"));
                    c.put("platform", h.get("platform"));
                    c.put("preferWs", h.get("preferWs"));
                    c.put("href", "/knowledge?entry=" + (h.get("entryId") == null ? "" : h.get("entryId")));
                    citations.add(c);
                    // citation SSE 延后到 token 流之后（见 flushCitations）
                }
            }

            // 工具：list_my_assets（显式意图或无 schema 时的 SQL/脚本草案前置）
            List<Map<String, Object>> myAssets = List.of();
            if ("list_assets".equals(intent)
                    || "nl2sql".equals(intent)
                    || "api_script".equals(intent)
                    || "ask_data".equals(intent)
                    || "explain".equals(intent)) {
                myAssets = listMyAssets(workspace, 12);
                if (!myAssets.isEmpty()) {
                    Map<String, Object> listCite = new LinkedHashMap<>();
                    listCite.put("type", "assets");
                    listCite.put("tool", "list_my_assets");
                    listCite.put("title", "我的可查资源");
                    listCite.put("text", formatMyAssetsBrief(myAssets));
                    listCite.put("items", myAssets);
                    citations.add(listCite);
                }
            }

            // 工具：get_schema / schema_link（客户端 schemaContext 先 ACL 软剥离，再回退 myAssets）
            List<Map<String, Object>> schemaContext = authorizeClientSchemaContext(param.getSchemaContext());
            String schemaTool = schemaContext.isEmpty() ? null : "schema_link";
            if (schemaContext.isEmpty()
                    && ("nl2sql".equals(intent) || "api_script".equals(intent)
                        || "ask_data".equals(intent) || "list_assets".equals(intent))
                    && !myAssets.isEmpty()) {
                schemaContext = loadSchemasForAssets(myAssets, 3);
                schemaTool = schemaContext.isEmpty() ? null : "get_schema";
            }
            if (!schemaContext.isEmpty() && schemaTool != null) {
                Map<String, Object> schemaCite = new LinkedHashMap<>();
                schemaCite.put("type", "schema");
                schemaCite.put("title", schemaTool);
                schemaCite.put("tool", schemaTool);
                schemaCite.put("text", formatSchemaContextBrief(schemaContext));
                citations.add(schemaCite);
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

            // 工具：resolve_metric / compile_metric → 指标中心 API（citation 与 trial 同级读权）
            String metricCode = null;
            String metricName = null;
            String compiledSql = null;
            String askDataSummary = null;
            boolean metricReadDenied = false;
            boolean hasSchemaLink = !schemaContext.isEmpty();
            boolean metricTopic = containsMetricKeyword(safeText) || "ask_data".equals(intent);
            if (metricTopic || (("nl2sql".equals(intent) || "ask_data".equals(intent)) && !hasSchemaLink)) {
                GovMetricVo metric = resolveMetricTool(safeText, workspace);
                if (metric != null && StrUtil.isNotBlank(metric.getMetricCode())) {
                    metricCode = metric.getMetricCode();
                    metricName = metric.getName();
                    boolean canReadMetric = false;
                    try {
                        canReadMetric = secAuthGrantService.canReadMetric(metric.getId());
                    } catch (Exception ignored) {
                        canReadMetric = false;
                    }
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("type", "metric");
                    c.put("metricCode", metricCode);
                    c.put("title", metricName);
                    c.put("id", metric.getId());
                    c.put("tool", "resolve_metric");
                    if (!canReadMetric) {
                        metricReadDenied = true;
                        c.put("text", "无读权限，请到申请中心申请后再试跑/查看编译 SQL");
                        citations.add(c);
                        // 不可读：不 compile、不 trial，避免 SQL 正文泄漏
                    } else {
                        citations.add(c);
                        compiledSql = compileMetricTool(metricCode, workspace);
                        if (StrUtil.isNotBlank(compiledSql)) {
                            Map<String, Object> compileCite = new LinkedHashMap<>();
                            compileCite.put("type", "metric");
                            compileCite.put("metricCode", metricCode);
                            compileCite.put("title", "compile_metric");
                            compileCite.put("tool", "compile_metric");
                            compileCite.put("text", StrUtil.maxLength(compiledSql, 240));
                            citations.add(compileCite);
                        }
                        if ("ask_data".equals(intent)) {
                            askDataSummary = trialMetricSummary(metricCode, workspace);
                            if (StrUtil.isNotBlank(askDataSummary)) {
                                Map<String, Object> trialCite = new LinkedHashMap<>();
                                trialCite.put("type", "metric");
                                trialCite.put("metricCode", metricCode);
                                trialCite.put("title", "metric_query_summary");
                                trialCite.put("tool", "ask_data");
                                trialCite.put("text", askDataSummary);
                                citations.add(trialCite);
                            }
                        }
                    }
                }
            }

            // 生成回答
            boolean intentTokensAlreadyStreamed = false;
            if ("list_assets".equals(intent)) {
                answer = listAssetsAnswer(myAssets, workspace);
            } else if (metricReadDenied) {
                answer = metricDeniedAnswer(metricCode, metricName);
                String explore = askDataFallbackSql(schemaContext, myAssets);
                if (StrUtil.isNotBlank(explore)) {
                    answer = answer
                            + "\n可基于已授权表做**探索查询**（非该指标官方口径，试跑需二次确认）：\n\n```sql\n"
                            + explore + "\n```\n";
                }
            } else if (metricTopic && StrUtil.isBlank(metricCode)
                    && !"list_assets".equals(intent) && !"api_script".equals(intent)) {
                answer = missingMetricCodeAnswer(safeText);
                if ("ask_data".equals(intent)) {
                    String explore = askDataFallbackSql(schemaContext, myAssets);
                    if (StrUtil.isNotBlank(explore)) {
                        answer = answer
                                + "\n未绑定指标时，可基于已授权表做**探索查询**（非官方口径，试跑需二次确认）：\n\n```sql\n"
                                + explore + "\n```\n";
                    } else {
                        answer = answer
                                + "\n当前无可查表可生成探索 SQL。请到指标中心登记口径，或到申请中心申请读权限。\n";
                    }
                }
            } else {
                final boolean[] tokensStarted = {false};
                answer = buildAnswer(intent, safeText, citations, metricCode, metricName,
                        compiledSql, modelId, diagnoseTable, schemaContext, askDataSummary,
                        workspace, param, myAssets,
                        token -> {
                            if (StrUtil.isBlank(token)) {
                                return;
                            }
                            tokensStarted[0] = true;
                            try {
                                sendEvent(emitter, "token", Map.of("text", token));
                            } catch (Exception ignored) {
                                // SSE soft
                            }
                        });
                answer = ensureMetricCodeInAnswer(answer, metricCode);
                // 防空：有引用但正文为空时，用知识/工具上下文生成可读回退（非 Mock）
                if (StrUtil.isBlank(answer)) {
                    answer = heuristicAnswer(intent, safeText, citations, metricCode, metricName, compiledSql,
                            diagnoseTable, schemaContext, askDataSummary, param, myAssets);
                    tokensStarted[0] = false;
                }
                // SSE 顺序：meta → token* → citation* → action* → done
                if (!tokensStarted[0]) {
                    streamTokens(emitter, answer);
                }
                flushCitations(emitter, citations);
                // 工具阶段已收集的动作（如诊断深链）在引用之后下发
                for (Map<String, Object> earlyAct : new ArrayList<>(actions)) {
                    sendEvent(emitter, "action", earlyAct);
                }

                // 建议动作 — 跳到原动作块（下方统一处理）
                // NOTE: 下列动作逻辑与原先一致，故不在此重复；用标记避免二次 stream
                intentTokensAlreadyStreamed = true;
            }
            if (!intentTokensAlreadyStreamed) {
                // 非 LLM 路径（list_assets / denied / missing metric）仍切块推送
                if (StrUtil.isBlank(answer)) {
                    answer = heuristicAnswer(intent, safeText, citations, metricCode, metricName, compiledSql,
                            diagnoseTable, schemaContext, askDataSummary, param, myAssets);
                }
                streamTokens(emitter, answer);
                flushCitations(emitter, citations);
                for (Map<String, Object> earlyAct : new ArrayList<>(actions)) {
                    sendEvent(emitter, "action", earlyAct);
                }
            }

            // 建议动作
            if ("api_script".equals(intent)) {
                boolean groovy = IntentRouter.wantsGroovy(param.getScriptType(), safeText);
                if (groovy) {
                    String script = extractFencedBlock(answer, "groovy");
                    if (StrUtil.isBlank(script)) {
                        script = extractFencedBlock(answer, "java");
                    }
                    if (StrUtil.isBlank(script)) {
                        script = heuristicGroovy(safeText, schemaContext);
                    }
                    emitApiScriptActions(emitter, actions, script, true);
                } else {
                    String sql = extractFencedBlock(answer, "sql");
                    if (StrUtil.isBlank(sql) && StrUtil.isNotBlank(compiledSql)) {
                        sql = compiledSql;
                    }
                    if (StrUtil.isBlank(sql)) {
                        sql = heuristicSql(schemaContext, myAssets);
                    }
                    emitApiScriptActions(emitter, actions, sql, false);
                }
            } else if ("nl2sql".equals(intent) || "sql_opt".equals(intent) || "ask_data".equals(intent)) {
                if (StrUtil.isNotBlank(compiledSql)) {
                    emitSqlActions(emitter, actions, compiledSql, metricCode);
                } else if (metricReadDenied) {
                    Map<String, Object> applyLink = new LinkedHashMap<>();
                    applyLink.put("type", "deeplink");
                    applyLink.put("label", "去申请中心");
                    applyLink.put("href", "/apply");
                    actions.add(applyLink);
                    sendEvent(emitter, "action", applyLink);
                    String draftSql = extractFencedBlock(answer, "sql");
                    if (StrUtil.isBlank(draftSql)) {
                        draftSql = askDataFallbackSql(schemaContext, myAssets);
                    }
                    if (StrUtil.isNotBlank(draftSql)) {
                        emitSqlActions(emitter, actions, draftSql, null);
                    }
                } else if (metricTopic && StrUtil.isBlank(metricCode)) {
                    Map<String, Object> metricsLink = new LinkedHashMap<>();
                    metricsLink.put("type", "deeplink");
                    metricsLink.put("label", "打开指标中心");
                    metricsLink.put("href", "/metrics");
                    actions.add(metricsLink);
                    sendEvent(emitter, "action", metricsLink);
                    Map<String, Object> applyLink = new LinkedHashMap<>();
                    applyLink.put("type", "deeplink");
                    applyLink.put("label", "去申请中心");
                    applyLink.put("href", "/apply");
                    actions.add(applyLink);
                    sendEvent(emitter, "action", applyLink);
                    // 未命中指标：仍尽量给出可探索 SQL（答案中的 fence / schema / 已授权表），避免只有 deeplink
                    String draftSql = extractFencedBlock(answer, "sql");
                    if (StrUtil.isBlank(draftSql)) {
                        draftSql = askDataFallbackSql(schemaContext, myAssets);
                    }
                    if (StrUtil.isNotBlank(draftSql)) {
                        emitSqlActions(emitter, actions, draftSql, null);
                    }
                } else {
                    String sql = extractFencedBlock(answer, "sql");
                    if (StrUtil.isBlank(sql)) {
                        sql = heuristicSql(schemaContext, myAssets);
                    }
                    emitSqlActions(emitter, actions, sql, null);
                }
            }
            if ("list_assets".equals(intent)) {
                emitListAssetsActions(emitter, actions, myAssets);
            }
            if (!citations.isEmpty()) {
                Map<String, Object> kbLink = new LinkedHashMap<>();
                kbLink.put("type", "deeplink");
                kbLink.put("label", "查看知识库引用");
                Object entryId = citations.stream()
                        .filter(c -> c.get("entryId") != null)
                        .map(c -> c.get("entryId"))
                        .findFirst()
                        .orElse("");
                kbLink.put("href", "/knowledge?entry=" + entryId);
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
                govAiModelService.recordUsage(workspace, modelId, promptTok, completionTok, latency);
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
                               List<Map<String, Object>> schemaContext, String askDataSummary,
                               String workspace, GovAiChatParam param,
                               List<Map<String, Object>> myAssets) {
        return buildAnswer(intent, text, citations, metricCode, metricName, compiledSql, modelId,
                diagnoseTable, schemaContext, askDataSummary, workspace, param, myAssets, null);
    }

    private String buildAnswer(String intent, String text, List<Map<String, Object>> citations,
                               String metricCode, String metricName, String compiledSql,
                               String modelId, String diagnoseTable,
                               List<Map<String, Object>> schemaContext, String askDataSummary,
                               String workspace, GovAiChatParam param,
                               List<Map<String, Object>> myAssets,
                               java.util.function.Consumer<String> onToken) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("意图=").append(intent).append('\n');
        if (StrUtil.isNotBlank(diagnoseTable)) {
            ctx.append("诊断表=").append(diagnoseTable).append('\n');
        }
        if (param != null && "api_script".equals(intent)) {
            boolean groovy = IntentRouter.wantsGroovy(param.getScriptType(), text);
            ctx.append("构建API脚本类型=").append(groovy ? "GROOVY" : "SQL").append('\n');
            if (StrUtil.isNotBlank(param.getDatasourceId())) {
                ctx.append("datasourceId=").append(param.getDatasourceId().trim()).append('\n');
            }
            if (StrUtil.isNotBlank(param.getDatasourceType())) {
                ctx.append("datasourceType=").append(param.getDatasourceType().trim()).append('\n');
            }
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
        if (StrUtil.isNotBlank(askDataSummary)) {
            ctx.append("问数摘要=\n").append(askDataSummary).append('\n');
        }
        if (schemaContext != null && !schemaContext.isEmpty()) {
            ctx.append("schema_link=\n").append(formatSchemaContextBrief(schemaContext)).append('\n');
        }
        boolean hasKb = false;
        for (Map<String, Object> c : citations) {
            if ("knowledge".equals(String.valueOf(c.get("type")))) {
                hasKb = true;
            }
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
        String systemPrompt = "你是湖仓治理助手 DataLake Copilot。基于给定上下文回答；"
                + "引用业务口径必须给出 metric_code；禁止编造 GMV 等生产口径或直出生产 SQL；"
                + "有编译 SQL 时原样引用并标注 metric_code；"
                + "有 schema_link 时可生成只读 SELECT 草案（LIMIT≤100），禁止 DDL/DML；"
                + "知识结论必须标注引用条目；无知识命中时明确说明未检索到，禁止臆造手册内容；"
                + "问数摘要仅作参考，须提示用户确认后试跑，禁止假装已写入生产；"
                + "禁止复述用户明文手机号/证件/密钥。";
        if ("api_script".equals(intent)) {
            boolean groovy = param != null && IntentRouter.wantsGroovy(param.getScriptType(), text);
            if (groovy) {
                systemPrompt += "本轮为数据服务「构建 API」Groovy 脚本生成："
                        + "输出可直接粘贴的 Groovy（可用 ```groovy 代码块），"
                        + "须 return 行集合或 Map；禁止写生产、禁止 DDL/DML；"
                        + "可用入参约定与 SQLREST 脚本一致；须提示用户确认后再写入编辑器。";
            } else {
                systemPrompt += "本轮为数据服务「构建 API」SQL 生成："
                        + "输出只读 SELECT，占位符用 #{paramName}（SQLREST 风格），"
                        + "可用 ```sql 代码块；禁止 DDL/DML；LIMIT≤100；"
                        + "须提示用户确认后再写入 API 脚本窗口，禁止假装已保存或已发布。";
            }
        }
        if (hasKb) {
            systemPrompt += "本轮已有知识库命中，回答中须点明引用了哪些条目标题。";
        }
        String userPrompt = "用户问题：\n" + text + "\n\n上下文：\n" + ctx;
        // P0 硬门禁：超配额不调用上游 LLM（启发式降级仍可用，避免「仅有 citation 无正文」）
        try {
            boolean tryUpstream = liteLlmClient.available() || isDirectChatReady(modelId);
            if (tryUpstream) {
                govAiModelService.assertDailyQuota(workspace, modelId);
            }
            if (onToken != null) {
                String streamed = chatUpstreamStream(modelId, systemPrompt, userPrompt, onToken);
                if (StrUtil.isNotBlank(streamed)) {
                    return streamed;
                }
            } else {
                // 有登记 baseUrl 时优先直连（与模型测试一致）；否则走 LiteLLM
                if (isDirectChatReady(modelId)) {
                    String direct = chatDirect(modelId, systemPrompt, userPrompt);
                    if (StrUtil.isNotBlank(direct)) {
                        return direct;
                    }
                }
                if (liteLlmClient.available()) {
                    String llm = liteLlmClient.chatSimple(modelId, systemPrompt, userPrompt);
                    if (StrUtil.isNotBlank(llm)) {
                        return llm;
                    }
                }
                // LiteLLM 失败后再试一次直连
                String direct = chatDirect(modelId, systemPrompt, userPrompt);
                if (StrUtil.isNotBlank(direct)) {
                    return direct;
                }
            }
        } catch (CommonException e) {
            log.warn("AI upstream skipped ({}), falling back to heuristic", e.getMessage());
        } catch (Exception e) {
            log.warn("AI upstream failed, falling back to heuristic: {}", e.getMessage());
        }
        return heuristicAnswer(intent, text, citations, metricCode, metricName, compiledSql,
                diagnoseTable, schemaContext, askDataSummary, param, myAssets);
    }

    /** 真流式上游：直连优先，失败再 LiteLLM；都失败返回 null（由调用方启发式）。 */
    private String chatUpstreamStream(String modelId, String system, String user,
                                      java.util.function.Consumer<String> onToken) {
        if (isDirectChatReady(modelId)) {
            String direct = chatDirectStream(modelId, system, user, onToken);
            if (StrUtil.isNotBlank(direct)) {
                return direct;
            }
        }
        if (liteLlmClient.available()) {
            Map<String, Object> streamed = liteLlmClient.chatStreamSimple(modelId, system, user, onToken);
            if (Boolean.TRUE.equals(streamed.get("ok"))) {
                return String.valueOf(streamed.get("content"));
            }
            log.debug("Intent LiteLLM stream failed: {}", streamed.get("error"));
        }
        String direct = chatDirectStream(modelId, system, user, onToken);
        if (StrUtil.isNotBlank(direct)) {
            return direct;
        }
        // 流式全失败：整包再切块推送
        String fallback = null;
        if (isDirectChatReady(modelId)) {
            fallback = chatDirect(modelId, system, user);
        }
        if (StrUtil.isBlank(fallback) && liteLlmClient.available()) {
            fallback = liteLlmClient.chatSimple(modelId, system, user);
        }
        if (StrUtil.isBlank(fallback)) {
            fallback = chatDirect(modelId, system, user);
        }
        if (StrUtil.isNotBlank(fallback) && onToken != null) {
            int step = 48;
            for (int i = 0; i < fallback.length(); i += step) {
                onToken.accept(fallback.substring(i, Math.min(fallback.length(), i + step)));
            }
        }
        return fallback;
    }

    private String chatDirectStream(String modelId, String system, String user,
                                    java.util.function.Consumer<String> onToken) {
        if (StrUtil.isBlank(modelId)) {
            return null;
        }
        String id = modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)
                ? modelId.substring(LhLiteLlmClient.ALIAS_PREFIX.length())
                : modelId;
        GovAiModel model = modelMapper.selectById(id);
        if (model == null || !NOT_DELETE.equals(model.getDeleteFlag())) {
            return null;
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            return null;
        }
        if (StrUtil.isBlank(model.getBaseUrl()) || StrUtil.isBlank(model.getModelName())) {
            return null;
        }
        String apiKey = null;
        if (StrUtil.isNotBlank(model.getVaultPath())) {
            apiKey = vaultClient.getString(model.getVaultPath(), "apiKey");
        }
        Map<String, Object> streamed = openAiCompatClient.chatStreamSimple(
                model.getBaseUrl(), apiKey, model.getModelName(), system, user, onToken);
        if (Boolean.TRUE.equals(streamed.get("ok"))) {
            return String.valueOf(streamed.get("content"));
        }
        return null;
    }

    /** 是否具备直连 OpenAI 兼容上游的条件（与 chatDirect 前置一致） */
    private boolean isDirectChatReady(String modelId) {
        if (StrUtil.isBlank(modelId)) {
            return false;
        }
        String id = modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)
                ? modelId.substring(LhLiteLlmClient.ALIAS_PREFIX.length())
                : modelId;
        GovAiModel model = modelMapper.selectById(id);
        if (model == null || !NOT_DELETE.equals(model.getDeleteFlag())) {
            return false;
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            return false;
        }
        return StrUtil.isNotBlank(model.getBaseUrl()) && StrUtil.isNotBlank(model.getModelName());
    }

    /** 直连登记模型的 OpenAI 兼容端点 */
    private String chatDirect(String modelId, String system, String user) {
        if (StrUtil.isBlank(modelId)) {
            return null;
        }
        String id = modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)
                ? modelId.substring(LhLiteLlmClient.ALIAS_PREFIX.length())
                : modelId;
        GovAiModel model = modelMapper.selectById(id);
        if (model == null || !NOT_DELETE.equals(model.getDeleteFlag())) {
            return null;
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            return null;
        }
        if (StrUtil.isBlank(model.getBaseUrl()) || StrUtil.isBlank(model.getModelName())) {
            return null;
        }
        String apiKey = null;
        if (StrUtil.isNotBlank(model.getVaultPath())) {
            apiKey = vaultClient.getString(model.getVaultPath(), "apiKey");
        }
        return openAiCompatClient.chatSimple(
                model.getBaseUrl(), apiKey, model.getModelName(), system, user);
    }

    private String heuristicAnswer(String intent, String text, List<Map<String, Object>> citations,
                                   String metricCode, String metricName, String compiledSql,
                                   String diagnoseTable, List<Map<String, Object>> schemaContext,
                                   String askDataSummary, GovAiChatParam param,
                                   List<Map<String, Object>> myAssets) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(intentLabel(intent)).append("\n\n");
        if (StrUtil.isNotBlank(askDataSummary)) {
            sb.append("问数摘要（确认后可试跑，未自动执行）：\n\n");
            sb.append(askDataSummary).append("\n\n");
            if (StrUtil.isNotBlank(metricCode)) {
                sb.append("`metric_code`=**").append(metricCode).append("**\n\n");
            }
        }
        long kbHits = citations == null ? 0 : citations.stream()
                .filter(c -> "knowledge".equals(String.valueOf(c.get("type")))).count();
        if (kbHits > 0) {
            sb.append("知识库命中 ").append(kbHits).append(" 条，根据检索片段整理如下：\n\n");
            int i = 1;
            for (Map<String, Object> c : citations) {
                if (!"knowledge".equals(String.valueOf(c.get("type")))) {
                    continue;
                }
                String title = StrUtil.blankToDefault(String.valueOf(c.getOrDefault("title", "")), "未命名条目");
                String excerpt = StrUtil.maxLength(
                        StrUtil.blankToDefault(String.valueOf(c.getOrDefault("text", "")), ""), 420);
                sb.append(i++).append(". **").append(title).append("**\n");
                if (StrUtil.isNotBlank(excerpt)) {
                    sb.append(excerpt).append("\n\n");
                } else {
                    sb.append("（无正文摘要，请打开引用条目查看）\n\n");
                }
            }
            sb.append("以上为检索原文摘要；完整条目见侧栏「知识库引用」。\n\n");
        } else if ("docqa".equals(intent) || "explain".equals(intent)) {
            sb.append("未检索到知识库命中，以下为通用指引（非手册原文）。");
        }
        switch (intent) {
            case "ask_data" -> {
                if (StrUtil.isBlank(askDataSummary) && StrUtil.isNotBlank(compiledSql)) {
                    sb.append("已编译指标 SQL，请确认后试跑：\n\n```sql\n").append(compiledSql).append("\n```\n");
                } else if (StrUtil.isBlank(askDataSummary) && StrUtil.isBlank(metricCode)) {
                    sb.append("未解析到可问数指标。请到指标中心确认口径，或前往申请中心申请读权限。\n");
                }
            }
            case "api_script" -> {
                boolean groovy = IntentRouter.wantsGroovy(
                        param == null ? null : param.getScriptType(), text);
                if (groovy) {
                    sb.append("已生成构建 API Groovy 草案（未写入编辑器，请确认后 Apply）：\n\n```groovy\n");
                    sb.append(heuristicGroovy(text, schemaContext));
                    sb.append("\n```\n");
                } else {
                    sb.append("已生成构建 API SQL 草案（`#{param}` 占位；未写入编辑器，请确认后 Apply）");
                    if (schemaContext != null && !schemaContext.isEmpty()) {
                        sb.append("（基于 schema_link）");
                    }
                    String draft = heuristicSql(schemaContext, myAssets);
                    if (StrUtil.isNotBlank(draft)) {
                        sb.append("：\n\n```sql\n").append(draft).append("\n```\n");
                    } else {
                        sb.append("。\n\n当前无已授权表/schema，无法生成草案。请到申请中心申请读权限，或在左侧选中可查表后再试。\n");
                    }
                }
            }
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
                    String draft = heuristicSql(schemaContext, myAssets);
                    if (StrUtil.isNotBlank(draft)) {
                        sb.append("已按只读约束生成 schema 草案 SQL");
                        if (schemaContext != null && !schemaContext.isEmpty()) {
                            sb.append("（基于我的可查表 schema）");
                        }
                        sb.append("：\n\n```sql\n").append(draft).append("\n```\n");
                    } else {
                        sb.append("当前无可查表/schema，无法生成 SQL 草案。")
                                .append("请先问「我可以查询哪些资源」，或到申请中心申请读权限。\n");
                    }
                }
            }
            case "list_assets" -> sb.append(listAssetsAnswer(
                    citations == null ? List.of() : citations.stream()
                            .filter(c -> "assets".equals(String.valueOf(c.get("type"))))
                            .findFirst()
                            .map(c -> {
                                Object items = c.get("items");
                                if (items instanceof List<?> list) {
                                    List<Map<String, Object>> rows = new ArrayList<>();
                                    for (Object o : list) {
                                        if (o instanceof Map<?, ?> m) {
                                            Map<String, Object> row = new LinkedHashMap<>();
                                            m.forEach((k, v) -> row.put(String.valueOf(k), v));
                                            rows.add(row);
                                        }
                                    }
                                    return rows;
                                }
                                return List.<Map<String, Object>>of();
                            })
                            .orElse(List.of()),
                    WS_DEFAULT));
            default -> {
                if ("diagnose".equals(intent)) {
                    sb.append("请结合质量规则与血缘上游排查");
                    if (StrUtil.isNotBlank(diagnoseTable)) {
                        sb.append("：").append(diagnoseTable);
                    }
                    sb.append("。\n");
                } else if ("gen_script".equals(intent)) {
                    sb.append("建议在开发中心基于 CDC 模板生成 Flink 作业，勿直接改生产。\n");
                } else if ("sql_opt".equals(intent)) {
                    sb.append("优先加分区过滤、避免 SELECT *，必要时走查询治理限额。\n");
                } else if (kbHits == 0) {
                    sb.append("可根据侧栏知识引用与资产上下文继续追问。\n");
                }
            }
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
                    }
                }
            }
        } catch (Exception ignored) {
            // soft-fail lineage
        }

        // deeplink（仅收集；SSE 在 token/citation 之后统一下发）
        Map<String, Object> qLink = new LinkedHashMap<>();
        qLink.put("type", "deeplink");
        qLink.put("label", "打开质量中心");
        qLink.put("href", StrUtil.isNotBlank(table) ? "/quality?table=" + table : "/quality");
        actions.add(qLink);

        if (StrUtil.isNotBlank(table)) {
            Map<String, Object> lLink = new LinkedHashMap<>();
            lLink.put("type", "deeplink");
            lLink.put("label", "打开血缘影响");
            lLink.put("href", "/lineage?focus=" + java.net.URLEncoder.encode(table,
                    java.nio.charset.StandardCharsets.UTF_8));
            actions.add(lLink);
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

    /**
     * 仅允许已 ACL 的 schemaContext / myAssets；禁止演示表与未绑定自由表名。
     */
    private String heuristicSql(List<Map<String, Object>> schemaContext,
                                List<Map<String, Object>> myAssets) {
        return AiSqlDraftHelper.draftSelect(schemaContext, myAssets);
    }

    /**
     * ask_data 探索草案：仅 schemaContext / 已授权表。
     */
    private String askDataFallbackSql(List<Map<String, Object>> schemaContext,
                                      List<Map<String, Object>> myAssets) {
        return AiSqlDraftHelper.draftSelect(schemaContext, myAssets);
    }

    private void emitListAssetsActions(SseEmitter emitter, List<Map<String, Object>> actions,
                                       List<Map<String, Object>> myAssets) throws IOException {
        boolean empty = myAssets == null || myAssets.isEmpty();
        if (empty) {
            Map<String, Object> applyLink = new LinkedHashMap<>();
            applyLink.put("type", "deeplink");
            applyLink.put("label", "去申请中心");
            applyLink.put("href", "/apply");
            actions.add(applyLink);
            sendEvent(emitter, "action", applyLink);
        }
        Map<String, Object> catLink = new LinkedHashMap<>();
        catLink.put("type", "deeplink");
        catLink.put("label", "打开资产目录");
        catLink.put("href", "/catalog");
        actions.add(catLink);
        sendEvent(emitter, "action", catLink);
    }

    private static String metricDeniedAnswer(String metricCode, String metricName) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 指标无读权限\n\n");
        sb.append("已解析到指标");
        if (StrUtil.isNotBlank(metricCode)) {
            sb.append(" `metric_code`=**").append(metricCode).append("**");
        }
        if (StrUtil.isNotBlank(metricName)) {
            sb.append("（").append(metricName).append("）");
        }
        sb.append("，但当前账号**无读权限**，不展示编译 SQL / 试跑结果。\n\n");
        sb.append("请到申请中心申请该指标读权限后再问数。\n");
        return sb.toString();
    }

    private void emitSqlActions(SseEmitter emitter, List<Map<String, Object>> actions,
                                String sql, String metricCode) throws IOException {
        if (StrUtil.isBlank(sql)) {
            return;
        }
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "apply_sql");
        apply.put("label", "在即席查询打开");
        apply.put("sql", sql);
        apply.put("autoExecute", false);
        if (StrUtil.isNotBlank(metricCode)) {
            apply.put("metricCode", metricCode);
        }
        actions.add(apply);
        sendEvent(emitter, "action", apply);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("type", "run_sql");
        run.put("label", "聊天内试跑（需二次确认）");
        run.put("sql", sql);
        if (StrUtil.isNotBlank(metricCode)) {
            run.put("metricCode", metricCode);
        }
        actions.add(run);
        sendEvent(emitter, "action", run);
    }

    /**
     * 构建 API：只发 Apply 动作（不自动写入、不旁路试跑）。
     * groovy=true → apply_script；否则 apply_sql（标签面向 API 窗口）。
     */
    private void emitApiScriptActions(SseEmitter emitter, List<Map<String, Object>> actions,
                                      String content, boolean groovy) throws IOException {
        if (StrUtil.isBlank(content)) {
            return;
        }
        Map<String, Object> apply = new LinkedHashMap<>();
        if (groovy) {
            apply.put("type", "apply_script");
            apply.put("label", "写入 API Groovy 窗口");
            apply.put("script", content);
            apply.put("scriptType", "GROOVY");
        } else {
            apply.put("type", "apply_sql");
            apply.put("label", "写入 API SQL 窗口");
            apply.put("sql", content);
            apply.put("scriptType", "SQL");
        }
        apply.put("autoExecute", false);
        apply.put("autoApply", false);
        actions.add(apply);
        sendEvent(emitter, "action", apply);
    }

    /** 从助手正文提取 ```lang ... ``` 代码块。 */
    private static String extractFencedBlock(String answer, String lang) {
        if (StrUtil.isBlank(answer) || StrUtil.isBlank(lang)) {
            return null;
        }
        String pattern = "(?is)```" + java.util.regex.Pattern.quote(lang.trim()) + "\\s*([\\s\\S]*?)```";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(answer);
        if (m.find()) {
            return StrUtil.trim(m.group(1));
        }
        return null;
    }

    private String heuristicGroovy(String text, List<Map<String, Object>> schemaContext) {
        String tableHint = "";
        if (schemaContext != null && !schemaContext.isEmpty()) {
            Map<String, Object> first = schemaContext.get(0);
            String schema = StrUtil.blankToDefault(String.valueOf(first.getOrDefault("schema", "")), "");
            String table = StrUtil.blankToDefault(String.valueOf(
                    first.getOrDefault("table", first.getOrDefault("name", ""))), "demo_table");
            if ("null".equals(table)) {
                table = "demo_table";
            }
            tableHint = StrUtil.isNotBlank(schema) && !"null".equals(schema)
                    ? schema + "." + table : table;
        } else {
            String extracted = extractTableName(text);
            tableHint = StrUtil.blankToDefault(extracted, "demo_table");
        }
        return "// 构建 API Groovy 草案（未执行）\n"
                + "// 上下文表: " + tableHint + "\n"
                + "def rows = []\n"
                + "// TODO: 按业务填充 rows，入参可用 params.xxx\n"
                + "return rows";
    }

    /**
     * 客户端 schemaContext / schema_link：规范化后按 owned ∪ hasTableReadGrant 软剥离。
     * 未挂目录或无权条目丢弃，不阻断整轮对话；空上下文可回退 get_schema(myAssets)。
     */
    private List<Map<String, Object>> authorizeClientSchemaContext(List<Map<String, Object>> raw) {
        List<Map<String, Object>> normalized = AiSchemaContextAcl.normalize(raw);
        if (normalized.isEmpty()) {
            return normalized;
        }
        int[] dropped = new int[1];
        List<Map<String, Object>> allowed = AiSchemaContextAcl.retainReadable(
                normalized,
                this::resolveSchemaContextAssetId,
                this::canReadAssetForSchema,
                dropped);
        if (dropped[0] > 0) {
            log.warn("AI schemaContext ACL stripped {} unauthorized/unresolved table(s); retained={}",
                    dropped[0], allowed.size());
        }
        return allowed;
    }

    /** 与目录预览 / grants/check 一致：owner ∪ 门户 SELECT 投影 ∪ Grav 实测。 */
    private boolean canReadAssetForSchema(String assetId) {
        return aiAssetReadAccess.canRead(assetId);
    }

    /**
     * 解析条目资产：优先 assetId；否则按 schema.table 匹配源绑定 objectName / omFqn / assetCode / name。
     */
    private String resolveSchemaContextAssetId(Map<String, Object> entry) {
        String assetId = AiSchemaContextAcl.firstAssetId(entry);
        if (assetId != null) {
            return assetId;
        }
        String schema = String.valueOf(entry.getOrDefault("schema", "")).trim();
        String table = String.valueOf(entry.getOrDefault("table", "")).trim();
        if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
            return null;
        }
        return lookupAssetIdByTableFqn(schema, table);
    }

    private String lookupAssetIdByTableFqn(String schema, String table) {
        String fqn = StrUtil.isNotBlank(schema) ? schema + "." + table : table;
        try {
            List<GovAssetSourceLink> links = assetSourceLinkMapper.selectList(
                    new QueryWrapper<GovAssetSourceLink>().lambda()
                            .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                            .and(w -> w.eq(GovAssetSourceLink::getObjectName, table)
                                    .or().eq(GovAssetSourceLink::getObjectName, fqn)
                                    .or().likeLeft(GovAssetSourceLink::getObjectName, "." + table))
                            .last("LIMIT 40"));
            for (GovAssetSourceLink link : links) {
                if (link == null || StrUtil.isBlank(link.getAssetId()) || StrUtil.isBlank(link.getObjectName())) {
                    continue;
                }
                if (AiSchemaContextAcl.matchesTableRef(schema, table, link.getObjectName())) {
                    return link.getAssetId();
                }
            }
            List<GovAsset> assets = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovAsset::getOmFqn, fqn)
                            .or().eq(GovAsset::getOmFqn, table)
                            .or().eq(GovAsset::getAssetCode, fqn)
                            .or().eq(GovAsset::getAssetCode, table)
                            .or().eq(GovAsset::getName, table)
                            .or().likeLeft(GovAsset::getOmFqn, "." + table))
                    .last("LIMIT 40"));
            for (GovAsset a : assets) {
                if (a == null || StrUtil.isBlank(a.getId())) {
                    continue;
                }
                if (AiSchemaContextAcl.matchesTableRef(schema, table,
                        a.getOmFqn(), a.getAssetCode(), a.getName())) {
                    return a.getId();
                }
            }
        } catch (Exception e) {
            log.debug("schemaContext FQN resolve soft-fail {}: {}", fqn, e.toString());
        }
        return null;
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
     * 知识检索：优先当前 ws（含 platform 条目），不足再全局补齐；hit.preferWs 如实标记。
     */
    private List<Map<String, Object>> searchKbPreferWs(String preferWs, String query, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.max(1, topK);

        if (StrUtil.isNotBlank(preferWs)) {
            GovKbSearchParam preferred = new GovKbSearchParam();
            preferred.setQuery(query);
            preferred.setWs(preferWs);
            preferred.setIncludePlatform(true);
            preferred.setTopK(limit);
            appendKbHits(out, seen, preferred, preferWs, true);
        }
        if (out.size() < limit) {
            GovKbSearchParam global = new GovKbSearchParam();
            global.setQuery(query);
            global.setTopK(Math.max(limit * 2, 10));
            appendKbHits(out, seen, global, preferWs, false);
        }
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    private void appendKbHits(List<Map<String, Object>> out, Set<String> seen,
                              GovKbSearchParam param, String preferWs, boolean forcePrefer) {
        try {
            for (Map<String, Object> h : govKbService.search(param)) {
                String key = String.valueOf(h.getOrDefault("chunkId", h.get("entryId")));
                if (!seen.add(key)) {
                    continue;
                }
                String hitWs = String.valueOf(h.getOrDefault("ws", "")).trim();
                boolean prefer = forcePrefer
                        || (StrUtil.isNotBlank(preferWs) && preferWs.equals(hitWs));
                h.put("preferWs", prefer);
                h.put("platform", "platform".equalsIgnoreCase(String.valueOf(h.get("scope")))
                        || "_platform".equals(hitWs));
                out.add(h);
            }
        } catch (Exception ignored) {
            // soft degrade
        }
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

    /**
     * 默认只读探索智能体。成功则写完 SSE 并返回 true；失败返回 false 由固定意图链兜底。
     * <p>尽早推 meta；工具进度以 citation 实时下发；最终回答走真 token 流（或回退切块）。</p>
     */
    private boolean tryRunAgentChat(SseEmitter emitter, GovAiSession session, GovAiChatParam param,
                                    String workspace, String safeText, AiPromptGuard.ScanResult promptScan,
                                    long start, String userId) {
        String intent = "agent";
        String modelId = resolveModel(param, session, intent);
        String userTurnId = IdUtil.getSnowflakeNextIdStr();
        String turnId = IdUtil.getSnowflakeNextIdStr();
        boolean metaSent = false;
        boolean userPersisted = false;
        try {
            assertEgressForChat(modelId, IntentRouter.modelScene(intent));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("intent", intent);
            meta.put("mode", "agent");
            meta.put("sessionId", session.getId());
            meta.put("turnId", turnId);
            meta.put("ws", workspace);
            meta.put("modelId", modelId);
            meta.put("modelName", modelDisplayName(modelId));
            meta.put("status", "exploring");
            if (promptScan.isRedacted()) {
                meta.put("promptRedacted", true);
                meta.put("promptFindings", promptScan.getFindings());
            }

            // 尽早推 meta，前端可立刻离开「空泡」；用户轮次仍成功后再落库，避免 fallback 污染
            sendEvent(emitter, "meta", meta);
            metaSent = true;

            List<Map<String, Object>> liveCitations = new ArrayList<>();
            final boolean[] tokensStarted = {false};
            GovAiAgentOrchestrator.AgentRunResult agentResult = agentOrchestrator.run(
                    workspace, safeText, modelId,
                    cite -> {
                        liveCitations.add(cite);
                        try {
                            sendEvent(emitter, "citation", cite);
                        } catch (Exception ignored) {
                            // SSE soft
                        }
                    },
                    token -> {
                        if (StrUtil.isBlank(token)) {
                            return;
                        }
                        tokensStarted[0] = true;
                        try {
                            sendEvent(emitter, "token", Map.of("text", token));
                        } catch (Exception ignored) {
                            // SSE soft
                        }
                    });
            if (!agentResult.success || StrUtil.isBlank(agentResult.answer)) {
                log.info("Agent fallback to IntentRouter: {}", agentResult.failReason);
                return false;
            }

            persistTurn(session.getId(), userTurnId, "user", intent, param.getText(),
                    null, null, null, modelId, null, userId);
            userPersisted = true;

            List<Map<String, Object>> citations = new ArrayList<>(agentResult.citations);
            if (citations.isEmpty()) {
                citations.addAll(liveCitations);
            }
            List<Map<String, Object>> actions = new ArrayList<>(agentResult.actions);

            // 真流未推送时（无 onToken 增量）回退切块，避免空白
            if (!tokensStarted[0]) {
                streamTokens(emitter, agentResult.answer);
            }
            // 工具 citation 已实时推过；补推知识类等遗漏
            for (Map<String, Object> c : citations) {
                if (liveCitations.contains(c)) {
                    continue;
                }
                sendEvent(emitter, "citation", c);
            }
            for (Map<String, Object> act : actions) {
                sendEvent(emitter, "action", act);
            }

            int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start);
            int promptTok = estimateTokens(safeText);
            int completionTok = estimateTokens(agentResult.answer);
            persistTurn(session.getId(), turnId, "assistant", intent, agentResult.answer,
                    JSONUtil.toJsonStr(citations), promptTok, completionTok, modelId, latency, userId);
            try {
                govAiModelService.recordUsage(workspace, modelId, promptTok, completionTok, latency);
            } catch (Exception ignored) {
                // soft
            }
            if ("新对话".equals(session.getTitle()) || StrUtil.isBlank(session.getTitle())) {
                session.setTitle(StrUtil.maxLength(safeText.trim(), 40));
                sessionMapper.updateById(session);
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("turnId", turnId);
            done.put("intent", intent);
            done.put("mode", "agent");
            done.put("steps", agentResult.steps);
            done.put("promptTokens", promptTok);
            done.put("completionTokens", completionTok);
            done.put("citations", citations);
            done.put("actions", actions);
            sendEvent(emitter, "done", done);
            return true;
        } catch (Exception e) {
            log.warn("Agent chat failed, fallback: {}", e.getMessage());
            if (metaSent || userPersisted) {
                log.debug("Agent had early SSE/persist before fallback");
            }
            return false;
        }
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

    /** 在 token 流之后下发 citation，保证回答优先于引用侧栏 */
    private void flushCitations(SseEmitter emitter, List<Map<String, Object>> citations) throws IOException {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        for (Map<String, Object> c : citations) {
            sendEvent(emitter, "citation", c);
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
            case "api_script" -> "构建 API 脚本";
            case "ask_data" -> "问数";
            case "list_assets" -> "我的可查资源";
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

    /** 显式 override → 会话 override → gov_ai_route.primary → DataGoo 兜底 */
    private String resolveModel(GovAiChatParam param, GovAiSession session, String intent) {
        if (param != null && StrUtil.isNotBlank(param.getModelOverride())) {
            return param.getModelOverride().trim();
        }
        if (session != null && StrUtil.isNotBlank(session.getModelOverride())) {
            return session.getModelOverride().trim();
        }
        return resolveModelFromRoute(intent, param == null ? null : param.getWs(),
                session == null ? null : session.getWs());
    }

    private String resolveModelFromRoute(String intent, String ws, String sessionWs) {
        String scene = IntentRouter.modelScene(intent);
        String workspace = StrUtil.blankToDefault(ws, StrUtil.blankToDefault(sessionWs, WS_DEFAULT));
        try {
            List<GovAiRouteVo> routes = govAiModelService.listRoutes(workspace);
            for (GovAiRouteVo r : routes) {
                if (r == null || Boolean.FALSE.equals(r.getEnabled())) {
                    continue;
                }
                if (scene.equalsIgnoreCase(StrUtil.blankToDefault(r.getScene(), ""))) {
                    if (StrUtil.isNotBlank(r.getPrimaryModelId())) {
                        return r.getPrimaryModelId().trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // soft degrade
        }
        return DEFAULT_CHAT_MODEL;
    }

    private String modelDisplayName(String modelId) {
        if (StrUtil.isBlank(modelId)) {
            return "";
        }
        try {
            GovAiModel m = modelMapper.selectById(modelId);
            if (m != null) {
                return StrUtil.blankToDefault(m.getName(), m.getModelName());
            }
        } catch (Exception ignored) {
            // ignore
        }
        return modelId;
    }

    private List<Map<String, Object>> listMyAssets(String preferWs, int topN) {
        return aiAssetReadAccess.listMyAssets(preferWs, topN);
    }

    private String formatMyAssetsBrief(List<Map<String, Object>> assets) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : assets) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(a.get("assetCode")).append('(').append(a.get("access")).append(')');
        }
        return sb.toString();
    }

    private String listAssetsAnswer(List<Map<String, Object>> assets, String ws) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 我的可查资源\n\n");
        sb.append("当前空间偏好：`").append(StrUtil.blankToDefault(ws, WS_DEFAULT)).append("`（软排序，不绕过 ACL）\n\n");
        if (assets == null || assets.isEmpty()) {
            sb.append("暂无 **owned / granted** 表。\n\n");
            sb.append("- 到 **申请中心** 申请表读权限（须 Grav 投影成功才计入可查）\n");
            sb.append("- 或确认你是资产的 createUser / techOwner / bizOwner\n");
            sb.append("- 也可打开 **资产目录** 浏览后发起申请\n");
            return sb.toString();
        }
        sb.append("| 访问 | 编码 | 名称 | 空间 |\n|---|---|---|---|\n");
        for (Map<String, Object> a : assets) {
            sb.append("| ").append(a.get("access"))
                    .append(" | `").append(a.get("assetCode")).append("`")
                    .append(" | ").append(a.get("name"))
                    .append(" | ").append(a.get("ws")).append(" |\n");
        }
        sb.append("\n未授权表不会出现在此列表。\n");
        return sb.toString();
    }

    private List<Map<String, Object>> loadSchemasForAssets(List<Map<String, Object>> myAssets, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (myAssets == null) {
            return out;
        }
        int n = 0;
        for (Map<String, Object> a : myAssets) {
            if (n >= limit) {
                break;
            }
            String id = String.valueOf(a.get("id"));
            if (StrUtil.isBlank(id) || "null".equals(id)) {
                continue;
            }
            try {
                if (!aiAssetReadAccess.canRead(id)) {
                    continue;
                }
                GovAssetIdParam p = new GovAssetIdParam();
                p.setId(id);
                Map<String, Object> schema = govAssetService.schema(p);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("assetId", id);
                row.put("assetCode", a.get("assetCode"));
                row.put("name", a.get("name"));
                row.put("columns", schema == null ? List.of() : schema.getOrDefault("columns",
                        schema.getOrDefault("fields", List.of())));
                out.add(row);
                n++;
            } catch (Exception ignored) {
                // soft：无 schema 权限或服务降级
            }
        }
        return out;
    }

    private String trialMetricSummary(String metricCode, String ws) {
        if (StrUtil.isBlank(metricCode)) {
            return null;
        }
        try {
            GovMetricQueryParam qp = new GovMetricQueryParam();
            qp.setMetricCode(metricCode);
            qp.setWs(ws);
            qp.setMaxRows(20);
            Map<String, Object> res = govMetricService.query(qp);
            if (res == null) {
                return null;
            }
            Object rows = res.get("rows");
            Object rowCount = res.get("rowCount");
            if (rowCount == null && rows instanceof List<?> list) {
                rowCount = list.size();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("指标 ").append(metricCode).append(" 试查返回 ");
            sb.append(rowCount == null ? "—" : rowCount).append(" 行");
            if (res.get("message") != null) {
                sb.append(" · ").append(res.get("message"));
            }
            sb.append("（摘要，非自动落库）");
            return sb.toString();
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), "metric_query_failed");
            if (msg.contains("权限") || msg.contains("授权") || msg.contains("grant")) {
                return "指标 " + metricCode + " 无读权限：" + msg + "。请前往申请中心。";
            }
            return "指标 " + metricCode + " 试查不可用：" + msg;
        }
    }
}

