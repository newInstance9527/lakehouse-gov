package vip.xiaonuo.lh.modular.compliance.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDelEvidenceObjectKeysTest {

    @Test
    void objectKeyUsesPrefixReqNoAndSha() {
        String key = GovDelEvidenceObjectKeys.objectKey("compliance/evidence", "DEL-2026-0001",
                "abcdef0123456789");
        assertEquals("compliance/evidence/DEL-2026-0001/abcdef0123456789.zip", key);
    }

    @Test
    void sanitizeRejectsPathTraversal() {
        assertEquals("unknown", GovDelEvidenceObjectKeys.sanitizeSegment("../x"));
        assertEquals("a_b_c", GovDelEvidenceObjectKeys.sanitizeSegment("a/b c"));
        assertEquals("unknown", GovDelEvidenceObjectKeys.sanitizeSegment(""));
    }

    @Test
    void uriIsS3aForm() {
        assertEquals(
                "s3a://lh-audit/compliance/evidence/DEL-1/abc.zip",
                GovDelEvidenceObjectKeys.uri("lh-audit", "compliance/evidence/DEL-1/abc.zip"));
        assertEquals(
                "s3a://lh-audit/compliance/evidence/x.zip",
                GovDelEvidenceObjectKeys.uri("lh-audit", "/compliance/evidence/x.zip"));
    }

    @Test
    void defaultsStable() {
        assertEquals("lh-audit", GovDelEvidenceObjectKeys.DEFAULT_BUCKET);
        assertTrue(GovDelEvidenceObjectKeys.DEFAULT_PREFIX.startsWith("compliance/"));
        assertEquals("application/zip", GovDelEvidenceObjectKeys.CONTENT_TYPE_ZIP);
    }
}
