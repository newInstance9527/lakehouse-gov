package vip.xiaonuo.lh.core.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上游 error JSON 提取：避免把错误信封当成功 completion */
class LhLiteLlmClientErrorExtractTest {

    @Test
    void extractsOpenAiErrorMessage() {
        JSONObject root = JSONUtil.parseObj("""
                {"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}
                """);
        String msg = LhLiteLlmClient.extractUpstreamError(root);
        assertTrue(msg.contains("Incorrect API key"));
        assertTrue(msg.contains("invalid_api_key"));
    }

    @Test
    void extractsDetailWithoutChoices() {
        JSONObject root = JSONUtil.parseObj("{\"detail\":\"model not found\"}");
        assertEquals("model not found", LhLiteLlmClient.extractUpstreamError(root));
    }

    @Test
    void ignoresMessageOnSuccessBody() {
        JSONObject root = JSONUtil.parseObj("""
                {"id":"x","choices":[{"message":{"role":"assistant","content":"hi"}}],"message":"ok"}
                """);
        assertNull(LhLiteLlmClient.extractUpstreamError(root));
    }
}
