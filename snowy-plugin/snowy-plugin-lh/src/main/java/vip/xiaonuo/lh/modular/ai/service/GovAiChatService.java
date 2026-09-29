package vip.xiaonuo.lh.modular.ai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.xiaonuo.lh.modular.ai.param.GovAiChatParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiRunSqlParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiSessionCreateParam;

import java.util.List;
import java.util.Map;

/**
 * AI 助手 Copilot
 */
public interface GovAiChatService {

    Map<String, Object> contextSummary(String ws);

    List<Map<String, Object>> listSessions(String ws);

    /** 会话轮次（按时间升序） */
    List<Map<String, Object>> listTurns(String sessionId);

    Map<String, Object> createSession(GovAiSessionCreateParam param);

    /** 软删会话（仅本人；turns 保留审计） */
    Map<String, Object> deleteSession(String sessionId);

    SseEmitter chat(GovAiChatParam param);

    Map<String, Object> runSql(GovAiRunSqlParam param);
}
