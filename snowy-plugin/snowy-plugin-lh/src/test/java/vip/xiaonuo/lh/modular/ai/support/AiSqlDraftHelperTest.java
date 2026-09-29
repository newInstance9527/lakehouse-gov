package vip.xiaonuo.lh.modular.ai.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSqlDraftHelperTest {

    @Test
    void emptySourcesReturnNull() {
        assertNull(AiSqlDraftHelper.draftSelect(null, null));
        assertNull(AiSqlDraftHelper.draftSelect(List.of(), List.of()));
        assertNull(AiSqlDraftHelper.fromMyAssets(List.of()));
        assertNull(AiSqlDraftHelper.fromSchema(List.of()));
    }

    @Test
    void prefersSchemaOverAssets() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schema", "iceberg.ods");
        schema.put("table", "orders");
        schema.put("columns", List.of(Map.of("name", "id"), Map.of("name", "amt")));
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetCode", "other_table");
        String sql = AiSqlDraftHelper.draftSelect(List.of(schema), List.of(asset));
        assertTrue(sql.contains("orders"));
        assertTrue(sql.contains("id"));
        assertFalse(sql.contains("other_table"));
        assertFalse(sql.contains("dwd_order_detail"));
    }

    @Test
    void myAssetsOnlyWhenNoSchema() {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetCode", "dwd_trade_order");
        String sql = AiSqlDraftHelper.draftSelect(List.of(), List.of(asset));
        assertTrue(sql.contains("dwd_trade_order"));
        assertFalse(sql.contains("dwd_order_detail"));
    }
}
