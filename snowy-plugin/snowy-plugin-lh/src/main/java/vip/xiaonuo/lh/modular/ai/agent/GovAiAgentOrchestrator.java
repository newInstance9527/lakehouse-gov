package vip.xiaonuo.lh.modular.ai.agent;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.ai.LhChatTools;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.ai.LhOpenAiCompatClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiModelMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读探索智能体：ReAct 工具循环（最多 5 步），服务端执行白名单工具。
 */
@Slf4j
@Component
public class GovAiAgentOrchestrator {

    public static final int MAX_STEPS = 5;
    private static final String NOT_DELETE = "NOT_DELETE";

    public static final String SYSTEM_PROMPT = """
            你是湖仓治理门户「只读探索智能体」DataLake Copilot。
            规则：
            1. 先调用工具获取事实，再归纳回答；禁止编造表数量、表名、指标口径。
            2. 只能使用提供的工具；工具失败或无权限时明确说明，并提示申请中心 /apply。
            3. 必须区分两平面，禁止混说：
               - 「目录可见 / 空间登记」= 资产目录列表能看见（wsAssetCount / catalogVisible），不等于能查数；
               - 「我可查」= owner、或门户 SELECT 投影、或 Grav/Trino 实测可 SELECT（myReadableCount，与目录预览同口径）。
               当 wsAssetCount>0 而 myReadableCount=0 时：说明「目录里有表，但你尚未获得读授权」，
               可列出 catalogVisibleSamples / needApply 样例，引导去申请中心；禁止说「目录为空」。
               若用户已是拥有者且能预览，myReadableCount 不应为 0；以工具结果为准，勿臆测。
               抽样数据用 preview_asset；表是什么用 get_asset_detail；申请进度用 list_apply_tickets；
               待我审批用 list_pending_approvals；数据源列表用 list_datasources；源连通性用 ds_connectivity；
               源内表清单用 list_source_tables；空间用 workspace_summary；怎么用某模块用 search_docs；
               任务/探活用 ops_job_status；我的查询历史用 query_history_mine；开放 API 用 api_catalog_summary；
               字段/码值标准用 standard_lookup；单表权限解释用 check_my_grant；脱敏列用 list_mask_policies；
               安全概况用 security_overview；元数据漂移用 list_meta_drifts；发布单用 list_releases；
               上版门禁预检用 release_precheck；开发脚本清单用 list_dev_scripts；配额告警用 quota_alerts；
               存储/生命周期用 lifecycle_overview；合规删除积压用 compliance_summary；出湖审计用 export_audit；
               保存的 SQL 用 list_saved_queries；数据契约用 contract_overview；模型概况用 aimodel_overview；
               UDF 用 list_udfs；知识库规模用 kb_overview。
            4. 不生成或执行 SQL；不输出生产口径聚合 SQL；指标仅给元信息。
            5. 用简洁中文回答，优先 Markdown（标题/列表/加粗/代码块）；引用工具结果中的编码与名称。
               站内深链写成 Markdown 链接，例如申请中心用 /apply 路径，便于门户内跳转。
            """;

    @Resource
    private AiToolRegistry toolRegistry;
    @Resource
    private LhLiteLlmClient liteLlmClient;
    @Resource
    private LhOpenAiCompatClient openAiCompatClient;
    @Resource
    private GovAiModelMapper modelMapper;
    @Resource
    private LhVaultClient vaultClient;

    public static class AgentRunResult {
        public boolean success;
        public String answer;
        public String failReason;
        public final List<Map<String, Object>> citations = new ArrayList<>();
        public final List<Map<String, Object>> actions = new ArrayList<>();
        public int steps;
    }

    /**
     * @param onToolStep 每完成一个工具调用回调（用于 SSE citation）
     * @param onToken    最终回答文本增量（真流式）；可空则整段返回
     */
    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep,
                              Consumer<String> onToken) {
        AgentRunResult result = new AgentRunResult();
        List<Map<String, Object>> tools = toolRegistry.openaiToolDefinitions();
        if (tools.isEmpty()) {
            result.failReason = "no_tools";
            return result;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT
                + "\n当前工作空间偏好 ws=`" + StrUtil.blankToDefault(workspace, "default") + "`（软偏好）。"));
        messages.add(Map.of("role", "user", "content", StrUtil.nullToEmpty(userText)));

        List<Map<String, Object>> collectedToolNotes = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            result.steps = step + 1;
            Map<String, Object> llm = chatWithTools(modelId, messages, tools);
            if (!Boolean.TRUE.equals(llm.get("ok"))) {
                result.failReason = StrUtil.blankToDefault(String.valueOf(llm.get("error")), "llm_failed");
                log.warn("Agent LLM step {} failed: {}", step, result.failReason);
                return result;
            }
            String content = String.valueOf(llm.getOrDefault("content", ""));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) llm.get("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                if (StrUtil.isNotBlank(content)) {
                    result.success = true;
                    result.answer = content.trim();
                    emitAnswerTokens(result.answer, onToken);
                    appendDefaultActions(result);
                    return result;
                }
                result.failReason = "empty_final";
                return result;
            }
            messages.add(LhChatTools.assistantToolCallMessage(content, toolCalls));
            for (Map<String, Object> tc : toolCalls) {
                String name = String.valueOf(tc.getOrDefault("name", ""));
                String args = String.valueOf(tc.getOrDefault("arguments", "{}"));
                String callId = String.valueOf(tc.getOrDefault("id", "call"));
                long t0 = System.currentTimeMillis();
                String toolOut = toolRegistry.execute(name, args, workspace);
                int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - t0);
                messages.add(LhChatTools.toolResultMessage(callId, toolOut));

                Map<String, Object> cite = new LinkedHashMap<>();
                cite.put("type", "tool");
                cite.put("tool", name);
                cite.put("title", name);
                cite.put("text", StrUtil.maxLength(toolOut, 240));
                cite.put("latencyMs", latency);
                result.citations.add(cite);
                collectedToolNotes.add(cite);
                if (onToolStep != null) {
                    try {
                        onToolStep.accept(cite);
                    } catch (Exception ignored) {
                        // SSE soft
                    }
                }
            }

            // SSE 场景：工具执行后立刻真流式归纳（同轮可多 tool_calls；跨轮再调工具让位于流式体验）
            if (onToken != null) {
                messages.add(Map.of("role", "user", "content",
                        "请仅基于以上工具返回的 JSON 结果，用中文归纳回答用户问题；不要再调用工具。"
                                + "若信息不足，说明还缺什么，并提示用户补充。"));
                Map<String, Object> streamed = streamFinalAnswer(modelId, messages, onToken);
                if (Boolean.TRUE.equals(streamed.get("ok"))
                        && StrUtil.isNotBlank(String.valueOf(streamed.get("content")))) {
                    result.success = true;
                    result.answer = String.valueOf(streamed.get("content")).trim();
                    appendDefaultActions(result);
                    return result;
                }
                break; // 流式失败 → 循环外启发式 / 再试 streamFinal
            }
        }

        // 步数用尽 / 流式失败：无 tools 再归纳一轮
        Object lastMsg = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        boolean alreadyNudged = false;
        if (lastMsg instanceof Map<?, ?> lm) {
            alreadyNudged = "user".equals(String.valueOf(lm.get("role")))
                    && String.valueOf(lm.get("content") == null ? "" : lm.get("content"))
                    .contains("不要再调用工具");
        }
        if (!alreadyNudged) {
            messages.add(Map.of("role", "user", "content",
                    "工具调用步数已用尽。请仅基于以上工具返回的 JSON 结果，用中文归纳回答用户问题；不要再调用工具。"));
        }
        Map<String, Object> finalLlm = streamFinalAnswer(modelId, messages, onToken);
        if (Boolean.TRUE.equals(finalLlm.get("ok")) && StrUtil.isNotBlank(String.valueOf(finalLlm.get("content")))) {
            result.success = true;
            result.answer = String.valueOf(finalLlm.get("content")).trim();
            appendDefaultActions(result);
            return result;
        }
        // 启发式：拼工具摘要
        if (!collectedToolNotes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("### 探索结果（工具摘要）\n\n");
            for (Map<String, Object> c : collectedToolNotes) {
                sb.append("- **").append(c.get("tool")).append("**：")
                        .append(c.get("text")).append("\n");
            }
            sb.append("\n以上为工具原始摘要；若需写 SQL 请使用「生成 SQL」芯片。\n");
            result.success = true;
            result.answer = sb.toString();
            emitAnswerTokens(result.answer, onToken);
            appendDefaultActions(result);
            return result;
        }
        result.failReason = "max_steps_no_answer";
        return result;
    }

    /** 兼容旧调用：无 token 回调 */
    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep) {
        return run(workspace, userText, modelId, onToolStep, null);
    }

    private static void emitAnswerTokens(String answer, Consumer<String> onToken) {
        if (onToken == null || StrUtil.isBlank(answer)) {
            return;
        }
        int step = 24;
        for (int i = 0; i < answer.length(); i += step) {
            onToken.accept(answer.substring(i, Math.min(answer.length(), i + step)));
        }
    }

    private Map<String, Object> streamFinalAnswer(String modelId, List<Map<String, Object>> messages,
                                                  Consumer<String> onToken) {
        if (onToken == null) {
            return chatWithTools(modelId, messages, null);
        }
        if (isDirectChatReady(modelId)) {
            Map<String, Object> direct = streamDirect(modelId, messages, onToken);
            if (Boolean.TRUE.equals(direct.get("ok"))) {
                return direct;
            }
            log.debug("Agent direct stream failed: {}", direct.get("error"));
        }
        if (liteLlmClient.available()) {
            String useModel = modelId;
            if (StrUtil.isNotBlank(modelId) && !modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)) {
                if (modelMapper.selectById(modelId) != null) {
                    useModel = LhLiteLlmClient.aliasOf(modelId);
                }
            }
            Map<String, Object> streamed = liteLlmClient.chatStream(useModel, messages, onToken);
            if (Boolean.TRUE.equals(streamed.get("ok"))) {
                return streamed;
            }
            log.debug("Agent LiteLLM stream failed: {}", streamed.get("error"));
        }
        // 流式失败：回退整包再切块推送
        Map<String, Object> fallback = chatWithTools(modelId, messages, null);
        if (Boolean.TRUE.equals(fallback.get("ok"))) {
            emitAnswerTokens(String.valueOf(fallback.getOrDefault("content", "")), onToken);
        }
        return fallback;
    }

    private Map<String, Object> streamDirect(String modelId, List<Map<String, Object>> messages,
                                             Consumer<String> onToken) {
        String id = modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)
                ? modelId.substring(LhLiteLlmClient.ALIAS_PREFIX.length())
                : modelId;
        GovAiModel model = modelMapper.selectById(id);
        if (model == null) {
            return Map.of("ok", false, "error", "model_not_found");
        }
        String apiKey = null;
        if (StrUtil.isNotBlank(model.getVaultPath())) {
            apiKey = vaultClient.getString(model.getVaultPath(), "apiKey");
        }
        return openAiCompatClient.chatStream(
                model.getBaseUrl(), apiKey, model.getModelName(), messages, onToken);
    }

    private void appendDefaultActions(AgentRunResult result) {
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("type", "deeplink");
        cat.put("label", "打开资产目录");
        cat.put("href", "/catalog");
        result.actions.add(cat);
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "deeplink");
        apply.put("label", "去申请中心");
        apply.put("href", "/apply");
        result.actions.add(apply);
    }

    private Map<String, Object> chatWithTools(String modelId, List<Map<String, Object>> messages,
                                              List<Map<String, Object>> tools) {
        if (isDirectChatReady(modelId)) {
            Map<String, Object> direct = chatDirectWithTools(modelId, messages, tools);
            if (Boolean.TRUE.equals(direct.get("ok"))) {
                return direct;
            }
            log.debug("Agent direct tools failed: {}", direct.get("error"));
        }
        if (liteLlmClient.available()) {
            String useModel = modelId;
            if (StrUtil.isNotBlank(modelId) && !modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)) {
                // 门户 id 走别名
                if (modelMapper.selectById(modelId) != null) {
                    useModel = LhLiteLlmClient.aliasOf(modelId);
                }
            }
            return liteLlmClient.chatWithTools(useModel, messages, tools);
        }
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("ok", false);
        fail.put("error", "no_upstream_for_tools");
        return fail;
    }

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

    private Map<String, Object> chatDirectWithTools(String modelId, List<Map<String, Object>> messages,
                                                    List<Map<String, Object>> tools) {
        String id = modelId.startsWith(LhLiteLlmClient.ALIAS_PREFIX)
                ? modelId.substring(LhLiteLlmClient.ALIAS_PREFIX.length())
                : modelId;
        GovAiModel model = modelMapper.selectById(id);
        if (model == null) {
            return Map.of("ok", false, "error", "model_not_found");
        }
        String apiKey = null;
        if (StrUtil.isNotBlank(model.getVaultPath())) {
            apiKey = vaultClient.getString(model.getVaultPath(), "apiKey");
        }
        return openAiCompatClient.chatWithTools(
                model.getBaseUrl(), apiKey, model.getModelName(), messages, tools);
    }
}
