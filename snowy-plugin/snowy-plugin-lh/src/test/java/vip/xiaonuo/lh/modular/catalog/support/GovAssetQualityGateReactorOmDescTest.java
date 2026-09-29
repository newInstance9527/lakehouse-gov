package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovAssetQualityGateReactorOmDescTest {

    @Test
    void upsertScoreLineAppend() {
        String out = GovAssetQualityGateReactor.upsertDqScoreLine("hello", "[lh-dq] score=99.0 gold=true");
        assertTrue(out.contains("hello"));
        assertTrue(out.contains("[lh-dq] score=99.0 gold=true"));
    }

    @Test
    void upsertScoreLineReplace() {
        String out = GovAssetQualityGateReactor.upsertDqScoreLine(
                "a\n[lh-dq] score=10 gold=false\nb",
                "[lh-dq] score=95.5 gold=true");
        assertTrue(out.contains("[lh-dq] score=95.5 gold=true"));
        assertTrue(!out.contains("score=10"));
        assertTrue(out.contains("a"));
        assertTrue(out.contains("b"));
    }
}
