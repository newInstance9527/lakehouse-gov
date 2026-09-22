package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetCrossModuleExtrasBatchScoresTest {

    @Mock
    private GovDqRuleMapper ruleMapper;
    @Mock
    private GovDqRuleRunMapper runMapper;
    @Mock
    private vip.xiaonuo.lh.modular.lineage.service.GovLineageService govLineageService;
    @Mock
    private vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper mappingMapper;
    @Mock
    private vip.xiaonuo.lh.modular.standard.mapper.GovStdDetectResultMapper detectMapper;

    @InjectMocks
    private GovAssetCrossModuleExtras extras;

    @Test
    void batchScores_averagesLatestRuns() {
        GovDqRule r1 = new GovDqRule();
        r1.setId("rule-1");
        r1.setAssetId("a1");
        r1.setTableName("dwd_order");
        r1.setEnabled(1);
        r1.setDeleteFlag("NOT_DELETE");
        r1.setWs("default");

        GovDqRule r2 = new GovDqRule();
        r2.setId("rule-2");
        r2.setAssetId("a1");
        r2.setTableName("dwd_order");
        r2.setEnabled(1);
        r2.setDeleteFlag("NOT_DELETE");
        r2.setWs("default");

        when(ruleMapper.selectList(any())).thenReturn(List.of(r1, r2));

        GovDqRuleRun run1 = new GovDqRuleRun();
        run1.setRuleId("rule-1");
        run1.setOkPct(new BigDecimal("90.0"));
        run1.setRanAt(new Date());
        GovDqRuleRun run2 = new GovDqRuleRun();
        run2.setRuleId("rule-2");
        run2.setOkPct(new BigDecimal("80.0"));
        run2.setRanAt(new Date());
        when(runMapper.selectList(any())).thenReturn(List.of(run1, run2));

        Map<String, BigDecimal> scores = extras.batchScores("default", Map.of(
                "a1", new GovAssetCrossModuleExtras.AssetScoreKey("dwd_order", null)));
        assertEquals(new BigDecimal("85.0"), scores.get("a1"));
    }

    @Test
    void batchScores_emptyRules() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        Map<String, BigDecimal> scores = extras.batchScores("default", Map.of(
                "a1", new GovAssetCrossModuleExtras.AssetScoreKey("x", null)));
        assertTrue(scores.isEmpty());
    }
}
