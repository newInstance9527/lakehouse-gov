package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.core.engine.GravitinoClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpQueryColumnMaskResolverTest {

    @Test
    void parseColumnMaskPayload_arrayAndObjectAndCsv() {
        assertEquals(Set.of("mobile", "id_card"),
                CpQueryColumnMaskResolver.parseColumnMaskPayload("[\"mobile\",\"id_card\"]"));
        assertEquals(Set.of("buyer_mobile"),
                CpQueryColumnMaskResolver.parseColumnMaskPayload(
                        "[{\"column\":\"buyer_mobile\",\"algo\":\"mask_middle\"}]"));
        assertEquals(Set.of("email", "phone"),
                CpQueryColumnMaskResolver.parseColumnMaskPayload(
                        "{\"columns\":[\"email\",\"phone\"]}"));
        assertEquals(Set.of("ssn", "token"),
                CpQueryColumnMaskResolver.parseColumnMaskPayload("ssn, token"));
    }

    @Test
    void intersectPreserveOrder_keepsResultColumnCasing() {
        List<String> hit = CpQueryColumnMaskResolver.intersectPreserveOrder(
                List.of("Order_Id", "Buyer_Mobile", "gmv"),
                Set.of("buyer_mobile", "email"));
        assertEquals(List.of("Buyer_Mobile"), hit);
    }

    @Test
    void extractTableRefs_catalogSchemaTable() {
        List<CpQueryColumnMaskResolver.TableRef> refs = CpQueryColumnMaskResolver.extractTableRefs(
                "SELECT a.mobile FROM iceberg.ods_trade.s_order a JOIN dwd.user u ON 1=1");
        assertEquals(2, refs.size());
        assertEquals("iceberg", refs.get(0).catalog);
        assertEquals("ods_trade", refs.get(0).schema);
        assertEquals("s_order", refs.get(0).table);
        assertEquals("dwd", refs.get(1).schema);
        assertEquals("user", refs.get(1).table);
    }

    @Test
    void engineMaskColsFromExec_readsExplicitList() {
        Map<String, Object> exec = Map.of("maskCols", List.of("mobile", "email"));
        assertEquals(List.of("mobile", "email"),
                CpQueryColumnMaskResolver.engineMaskColsFromExec(exec));
        assertEquals(null, CpQueryColumnMaskResolver.engineMaskColsFromExec(Map.of()));
    }

    @Test
    void gravDetectColumnMasked_fromPropertiesNotName() {
        JSONObject col = JSONUtil.createObj()
                .set("name", "amount")
                .set("properties", JSONUtil.createObj().set("lh.mask", "mask_middle"));
        assertTrue(GravitinoClient.detectColumnMasked(col, Map.of("lh.mask", "mask_middle")));

        JSONObject plain = JSONUtil.createObj().set("name", "mobile");
        assertFalse(GravitinoClient.detectColumnMasked(plain, Map.of()));
    }

    @Test
    void resolveDegradesWithoutPolicies_noNameHeuristic() {
        // 纯静态路径：无引擎回传时，intersect 空策略 → 调用方应得到 none；
        // 这里验证启发式列名不会单独进入 intersect。
        List<String> cols = List.of("mobile", "email", "gmv");
        List<String> hit = CpQueryColumnMaskResolver.intersectPreserveOrder(cols, Set.of());
        assertTrue(hit.isEmpty());
        // 有策略时才命中
        assertEquals(List.of("mobile"),
                CpQueryColumnMaskResolver.intersectPreserveOrder(cols, Set.of("mobile")));
    }
}
