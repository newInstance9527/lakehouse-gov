package vip.xiaonuo.lh.modular.query.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpQueryDatasetObjectKeysTest {

    @Test
    void objectKeyUsesPrefixWsAndDsCode() {
        String key = CpQueryDatasetObjectKeys.objectKey("adhoc/dataset", "default", "ds_123");
        assertEquals("adhoc/dataset/default/ds_123/sample.json", key);
    }

    @Test
    void sanitizeRejectsPathTraversalAndSpecialChars() {
        assertEquals("default", CpQueryDatasetObjectKeys.sanitizeSegment("../x"));
        assertEquals("a_b_c", CpQueryDatasetObjectKeys.sanitizeSegment("a/b c"));
        assertEquals("default", CpQueryDatasetObjectKeys.sanitizeSegment(""));
        assertEquals("default", CpQueryDatasetObjectKeys.sanitizeSegment(null));
    }

    @Test
    void uriIsS3aForm() {
        assertEquals(
                "s3a://lh-portal/adhoc/dataset/default/ds_1/sample.json",
                CpQueryDatasetObjectKeys.uri("lh-portal", "adhoc/dataset/default/ds_1/sample.json"));
        assertEquals(
                "s3a://lh-portal/adhoc/dataset/x/sample.json",
                CpQueryDatasetObjectKeys.uri("lh-portal", "/adhoc/dataset/x/sample.json"));
    }

    @Test
    void storageConstantsStable() {
        assertEquals("object", CpQueryDatasetObjectKeys.STORAGE_OBJECT);
        assertEquals("db", CpQueryDatasetObjectKeys.STORAGE_DB);
        assertEquals("lh-portal", CpQueryDatasetObjectKeys.DEFAULT_BUCKET);
        assertTrue(CpQueryDatasetObjectKeys.DEFAULT_PREFIX.startsWith("adhoc/"));
    }
}
