package vip.xiaonuo.lh.core.json;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HutoolJsonPlainTest {

    @Test
    void converts_json_null_and_nested() {
        JSONObject jo = JSONUtil.parseObj("{\"a\":null,\"b\":[1,null,{\"c\":null}]}");
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = (Map<String, Object>) HutoolJsonPlain.plain(jo);
        assertNull(plain.get("a"));
        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) plain.get("b");
        assertEquals(1, arr.get(0));
        assertNull(arr.get(1));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) arr.get(2);
        assertNull(nested.get("c"));
        assertTrue(!(plain.get("a") instanceof JSONNull));
    }

    @Test
    void leaves_primitives() {
        assertEquals("x", HutoolJsonPlain.plain("x"));
        assertEquals(3, HutoolJsonPlain.plain(3));
        assertNull(HutoolJsonPlain.plain(null));
        assertNull(HutoolJsonPlain.plain(JSONNull.NULL));
        assertTrue(HutoolJsonPlain.plain(new JSONArray()) instanceof List);
    }
}
