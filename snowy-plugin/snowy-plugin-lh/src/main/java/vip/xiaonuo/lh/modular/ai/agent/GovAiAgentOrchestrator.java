package vip.xiaonuo.lh.modular.ai.agent;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.ai.LhChatTools;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.ai.LhOpenAiCompatClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.ai.support.AiSkillDenyHelper;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiModelMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 探索智能体：ReAct 工具循环（最多 {@link #MAX_STEPS} 步），服务端执行白名单 skill。
 */
@Slf4j
@Component
public class GovAiAgentOrchestrator {

    public static final int MAX_STEPS = 5;
    private static final String NOT_DELETE = "NOT_DELETE";

    public static final int HISTORY_MAX_TURNS = 8;
    public static final int HISTORY_MAX_CHARS_PER_MSG = 1200;
    /** 有会话摘要时只再带最近若干轮原文 */
    public static final int HISTORY_WITH_SUMMARY_TURNS = 4;

    public static final String SYSTEM_PROMPT = """
            你是湖仓治理门户「DataLake Copilot」探索智能体，按 ReAct 工作：思考 → 选工具 → 观察结果 → 再思考/再调工具 → 最终回答。
            规则：
            0. 每一步可先用简短中文说明「打算做什么」（会作为思考过程展示），再发起 tool_calls；无需工具时可直接最终回答。
            1. 问「有几个 skill / 有哪些工具 / 你会什么」时：必须先调用 list_skills，禁止用 kb_search 或知识库条目代替。
               手册/FAQ 才用 search_docs 或 kb_search；平台事实（表、源、指标）用对应 list/get 工具。
               跨会话项目要点用 memory_recall；沉淀本轮结论用 memory_save（草稿纪要）。
            2. 只能使用提供的工具。若工具 JSON 含 error=`forbidden`：必须原样转述 message / applyHint / howTo，
               并给出 Markdown 链接到 applyHref；禁止声称「已有权限」。可用 create_apply_ticket 代填申请。
            3. 区分「目录可见」与「我可查」两平面，禁止混说；以工具 JSON 为准，禁止编造表数量/口径/授权。
            4. 以会话摘要与近期对话轮次为准；勿编造未出现过的表名、权限或结论。
            5. 不自动执行 SQL；不做系统管理；不代建数据源/发布/删除。
            6. 最终用简洁中文 Markdown 回答；站内深链如 [申请表读权](/apply?type=perm&assetId=...)。
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
     * ReAct：多步 tool loop；思考过程经 {@code onThinking}，工具经 {@code onToolStep}，最终答案经 {@code onToken}。
     */
    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep,
                              Consumer<String> onToken) {
        return run(workspace, userText, modelId, onToolStep, onToken, null, null, null);
    }

    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep,
                              Consumer<String> onToken,
                              Consumer<Map<String, Object>> onThinking) {
        return run(workspace, userText, modelId, onToolStep, onToken, onThinking, null, null);
    }

    /**
     * @param history        本会话历史（role=user|assistant, content），不含当前 user 句
     * @param sessionSummary 滚动工作记忆摘要，可空
     */
    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep,
                              Consumer<String> onToken,
                              Consumer<Map<String, Object>> onThinking,
                              List<Map<String, Object>> history,
                              String sessionSummary) {
        AgentRunResult result = new AgentRunResult();
        List<Map<String, Object>> tools = toolRegistry.openaiToolDefinitions();
        if (tools.isEmpty()) {
            result.failReason = "no_tools";
            return result;
        }
        List<Map<String, Object>> messages = buildMessages(workspace, userText, history, sessionSummary);

        List<Map<String, Object>> collectedToolNotes = new ArrayList<>();
        emitThinking(onThinking, 0, "analyzing", "正在分析问题，决定是否调用工具…");

        for (int step = 0; step < MAX_STEPS; step++) {
            result.steps = step + 1;
            Map<String, Object> llm = chatWithTools(modelId, messages, tools);
            if (!Boolean.TRUE.equals(llm.get("ok"))) {
                result.failReason = StrUtil.blankToDefault(String.valueOf(llm.get("error")), "llm_failed");
                log.warn("Agent LLM step {} failed: {}", step, result.failReason);
                return result;
            }
            String content = String.valueOf(llm.getOrDefault("content", ""));
            if ("null".equals(content)) {
                content = "";
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) llm.get("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                if (StrUtil.isNotBlank(content)) {
                    emitThinking(onThinking, step + 1, "answer", "已得到足够信息，正在整理回答…");
                    result.success = true;
                    result.answer = content.trim();
                    emitAnswerTokens(result.answer, onToken);
                    appendDefaultActions(result);
                    return result;
                }
                result.failReason = "empty_final";
                return result;
            }

            if (StrUtil.isNotBlank(content)) {
                emitThinking(onThinking, step + 1, "thought", content.trim());
            } else {
                List<String> names = new ArrayList<>();
                for (Map<String, Object> tc : toolCalls) {
                    names.add(String.valueOf(tc.getOrDefault("name", "tool")));
                }
                emitThinking(onThinking, step + 1, "plan",
                        "准备调用工具：" + String.join("、", names));
            }

            messages.add(LhChatTools.assistantToolCallMessage(content, toolCalls));
            for (Map<String, Object> tc : toolCalls) {
                String name = String.valueOf(tc.getOrDefault("name", ""));
                String args = String.valueOf(tc.getOrDefault("arguments", "{}"));
                String callId = String.valueOf(tc.getOrDefault("id", "call"));
                emitThinking(onThinking, step + 1, "acting", "正在执行 `" + name + "` …");
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
                if (toolOut != null && toolOut.contains("\"error\":\"forbidden\"")) {
                    String applyHref = AiSkillDenyHelper.extractApplyHref(toolOut);
                    boolean hasApply = result.actions.stream()
                            .anyMatch(a -> applyHref.equals(String.valueOf(a.get("href"))));
                    if (!hasApply) {
                        for (Map<String, Object> a : AiSkillDenyHelper.deeplinkActions(applyHref, null)) {
                            result.actions.add(a);
                        }
                    }
                }
                if (onToolStep != null) {
                    try {
                        onToolStep.accept(cite);
                    } catch (Exception ignored) {
                        // SSE soft
                    }
                }
            }
            // 继续下一轮：模型可再调工具或给出最终答案（真 ReAct，不在首轮强制收束）
        }

        emitThinking(onThinking, MAX_STEPS, "finalize", "步数用尽，基于已有工具结果归纳回答…");
        messages.add(Map.of("role", "user", "content",
                "工具调用步数已用尽。请仅基于以上工具返回的 JSON 结果，用中文归纳回答用户问题；不要再调用工具。"));
        Map<String, Object> finalLlm = streamFinalAnswer(modelId, messages, onToken);
        if (Boolean.TRUE.equals(finalLlm.get("ok")) && StrUtil.isNotBlank(String.valueOf(finalLlm.get("content")))) {
            result.success = true;
            result.answer = String.valueOf(finalLlm.get("content")).trim();
            appendDefaultActions(result);
            return result;
        }
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

    private static void emitThinking(Consumer<Map<String, Object>> onThinking, int step, String phase, String text) {
        if (onThinking == null || StrUtil.isBlank(text)) {
            return;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("step", step);
        ev.put("phase", phase);
        ev.put("text", text);
        try {
            onThinking.accept(ev);
        } catch (Exception ignored) {
            // SSE soft
        }
    }

    /** 兼容旧调用：无 token 回调 */
    public AgentRunResult run(String workspace, String userText, String modelId,
                              Consumer<Map<String, Object>> onToolStep) {
        return run(workspace, userText, modelId, onToolStep, null, null, null, null);
    }

    static List<Map<String, Object>> buildMessages(String workspace, String userText,
                                                   List<Map<String, Object>> history,
                                                   String sessionSummary) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT
                + "\n当前工作空间偏好 ws=`" + StrUtil.blankToDefault(workspace, "default") + "`（软偏好）。"));
        if (StrUtil.isNotBlank(sessionSummary)) {
            messages.add(Map.of("role", "system", "content",
                    "【会话工作记忆摘要】\n" + StrUtil.maxLength(sessionSummary.trim(), 2000)));
        }
        int maxTurns = StrUtil.isNotBlank(sessionSummary) ? HISTORY_WITH_SUMMARY_TURNS : HISTORY_MAX_TURNS;
        List<Map<String, Object>> hist = trimHistory(history, maxTurns);
        for (Map<String, Object> h : hist) {
            String role = String.valueOf(h.getOrDefault("role", "")).trim().toLowerCase();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String content = StrUtil.maxLength(StrUtil.nullToEmpty(String.valueOf(h.get("content"))), HISTORY_MAX_CHARS_PER_MSG);
            if (StrUtil.isBlank(content)) {
                continue;
            }
            messages.add(Map.of("role", role, "content", content));
        }
        messages.add(Map.of("role", "user", "content", StrUtil.nullToEmpty(userText)));
        return messages;
    }

    /** 取末尾 maxTurns 条 user/assistant（按条计，非「一轮」pair）。 */
    static List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int maxTurns) {
        if (history == null || history.isEmpty() || maxTurns <= 0) {
            return List.of();
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> h : history) {
            if (h == null) {
                continue;
            }
            String role = String.valueOf(h.getOrDefault("role", "")).trim().toLowerCase();
            if ("user".equals(role) || "assistant".equals(role)) {
                filtered.add(h);
            }
        }
        if (filtered.size() <= maxTurns) {
            return filtered;
        }
        return filtered.subList(filtered.size() - maxTurns, filtered.size());
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
