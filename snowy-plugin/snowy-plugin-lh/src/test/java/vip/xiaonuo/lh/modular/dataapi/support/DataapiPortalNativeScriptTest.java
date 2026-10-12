package vip.xiaonuo.lh.modular.dataapi.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.common.exception.CommonException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataapiPortalNativeScriptTest {

    @Test
    void redisExtractParamsPreservesOrder() {
        List<String> names = DataapiRedisKvExecutor.extractParamNames(
                "{\"op\":\"mget\",\"keys\":[\"u:#{userId}\",\"p:#{userId}\",\"x:#{tenant}\"]}");
        assertEquals(List.of("userId", "tenant"), names);
    }

    @Test
    void redisValidateGetAndScan() {
        assertDoesNotThrow(() -> DataapiRedisKvExecutor.validateStructure(
                "{\"op\":\"get\",\"key\":\"user:#{id}\"}"));
        assertDoesNotThrow(() -> DataapiRedisKvExecutor.validateStructure(
                "{\"op\":\"scan\",\"pattern\":\"dim_*\",\"count\":20}"));
        assertDoesNotThrow(() -> DataapiRedisKvExecutor.validateStructure(
                "{\"op\":\"hgetall\",\"key\":\"hash:1\"}"));
        assertDoesNotThrow(() -> DataapiRedisKvExecutor.validateStructure(
                "{\"op\":\"mget\",\"keys\":[\"a\",\"b\"]}"));
    }

    @Test
    void redisValidateRejectsBadOp() {
        CommonException ex = assertThrows(CommonException.class,
                () -> DataapiRedisKvExecutor.validateStructure("{\"op\":\"set\",\"key\":\"k\"}"));
        assertTrue(ex.getMessage().contains("op") || ex.getMessage().contains("Redis"));
    }

    @Test
    void redisValidateRequiresKey() {
        assertThrows(CommonException.class,
                () -> DataapiRedisKvExecutor.validateStructure("{\"op\":\"get\"}"));
        assertThrows(CommonException.class,
                () -> DataapiRedisKvExecutor.validateStructure("not-json"));
    }

    @Test
    void esExtractParams() {
        List<String> names = DataapiEsDslExecutor.extractParamNames(
                "{\"index\":\"orders\",\"query\":{\"term\":{\"status\":\"#{status}\"}},\"size\":#{limit}}");
        assertEquals(List.of("status", "limit"), names);
    }

    @Test
    void esValidateFlatAndNestedBody() {
        assertDoesNotThrow(() -> DataapiEsDslExecutor.validateStructure(
                "{\"index\":\"orders\",\"query\":{\"match_all\":{}},\"size\":10}"));
        assertDoesNotThrow(() -> DataapiEsDslExecutor.validateStructure(
                "{\"_index\":\"orders\",\"body\":{\"query\":{\"match_all\":{}}}}"));
        assertDoesNotThrow(() -> DataapiEsDslExecutor.validateStructure(
                "{\"index\":\"orders\",\"search\":{\"size\":5,\"query\":{\"match_all\":{}}}}"));
    }

    @Test
    void esValidateRejectsMissingIndexOrBody() {
        assertThrows(CommonException.class, () -> DataapiEsDslExecutor.validateStructure(""));
        assertThrows(CommonException.class,
                () -> DataapiEsDslExecutor.validateStructure("{\"query\":{\"match_all\":{}}}"));
        assertThrows(CommonException.class,
                () -> DataapiEsDslExecutor.validateStructure("{\"index\":\"only\"}"));
        assertThrows(CommonException.class,
                () -> DataapiEsDslExecutor.validateStructure("SELECT 1"));
    }
}
