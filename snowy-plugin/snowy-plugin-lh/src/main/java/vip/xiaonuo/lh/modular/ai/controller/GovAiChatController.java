package vip.xiaonuo.lh.modular.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.ai.param.GovAiChatParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiRunSqlParam;
import vip.xiaonuo.lh.modular.ai.param.GovAiSessionCreateParam;
import vip.xiaonuo.lh.modular.ai.service.GovAiChatService;

import java.util.List;
import java.util.Map;

/**
 * AI 助手 Copilot（对齐 doc/AI助手.md P0）
 */
@Tag(name = "AI助手控制器")
@RestController
@Validated
public class GovAiChatController {

    @Resource
    private GovAiChatService govAiChatService;

    @Operation(summary = "空间上下文摘要")
    @GetMapping("/lh/ai/context/summary")
    public CommonResult<Map<String, Object>> contextSummary(@RequestParam(required = false) String ws) {
        return CommonResult.data(govAiChatService.contextSummary(ws));
    }

    @Operation(summary = "最近会话")
    @GetMapping("/lh/ai/sessions")
    public CommonResult<List<Map<String, Object>>> sessions(@RequestParam(required = false) String ws) {
        return CommonResult.data(govAiChatService.listSessions(ws));
    }

    @Operation(summary = "会话轮次")
    @GetMapping("/lh/ai/sessions/{id}/turns")
    public CommonResult<List<Map<String, Object>>> turns(@PathVariable("id") String id) {
        return CommonResult.data(govAiChatService.listTurns(id));
    }

    @Operation(summary = "新建会话")
    @CommonLog("新建AI会话")
    @PostMapping("/lh/ai/sessions")
    public CommonResult<Map<String, Object>> createSession(@RequestBody(required = false) GovAiSessionCreateParam param) {
        return CommonResult.data(govAiChatService.createSession(param == null ? new GovAiSessionCreateParam() : param));
    }

    @Operation(summary = "对话（SSE）")
    @PostMapping(value = "/lh/ai/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody GovAiChatParam param) {
        return govAiChatService.chat(param);
    }

    @Operation(summary = "试跑 SQL（只读校验，P0 深链即席）")
    @CommonLog("AI试跑SQL")
    @PostMapping("/lh/ai/run-sql")
    public CommonResult<Map<String, Object>> runSql(@RequestBody GovAiRunSqlParam param) {
        return CommonResult.data(govAiChatService.runSql(param));
    }
}
