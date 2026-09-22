package vip.xiaonuo.lh.modular.catalog.preview;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IcebergGravPreviewAdapterAlignTest {

    @Test
    void alignRows_caseInsensitiveKeys() {
        List<String> cols = List.of("ID", "CATEGORY", "NAME");
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("id", "1");
        src.put("category", "LOGIN");
        src.put("name", "超管");
        List<Map<String, Object>> aligned =
                IcebergGravPreviewAdapter.alignRowsToColumns(cols, List.of(src));
        assertEquals(1, aligned.size());
        assertEquals("1", aligned.get(0).get("ID"));
        assertEquals("LOGIN", aligned.get(0).get("CATEGORY"));
        assertEquals("超管", aligned.get(0).get("NAME"));
    }
}
