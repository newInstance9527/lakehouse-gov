package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetCrossModuleExtrasTest {

    @Mock
    private GovDqRuleMapper ruleMapper;
    @Mock
    private GovDqRuleRunMapper runMapper;
    @Mock
    private GovLineageService govLineageService;

    @InjectMocks
    private GovAssetCrossModuleExtras extras;

    private GovAsset asset;

    @BeforeEach
    void setUp() {
        asset = new GovAsset();
        asset.setId("a1");
        asset.setWs("default");
        asset.setOmFqn("lake.dwd.dwd_order");
    }

    @Test
    void quality_empty_rules_scoreNull() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> q = extras.buildQuality(asset, "dwd_order");
        assertTrue(Boolean.TRUE.equals(q.get("available")));
        assertNull(q.get("score"));
        assertEquals(0, q.get("ruleCount"));
        assertTrue(String.valueOf(q.get("path")).contains("/quality"));
    }

    @Test
    void quality_with_fail_and_score() {
        GovDqRule rule = new GovDqRule();
        rule.setId("r1");
        rule.setAssetId("a1");
        rule.setRuleCode("PK_UNIQUE");
        rule.setTableName("dwd_order");
        rule.setFieldName("id");
        rule.setSeverity("block");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        GovDqRuleRun run = new GovDqRuleRun();
        run.setPass(0);
        run.setOkPct(new BigDecimal("80.0"));
        run.setMessage("dup");
        when(runMapper.selectOne(any())).thenReturn(run);

        Map<String, Object> q = extras.buildQuality(asset, "dwd_order");
        assertEquals(new BigDecimal("80.0"), q.get("score"));
        List<?> fails = (List<?>) q.get("failRules");
        assertEquals(1, fails.size());
    }

    @Test
    void quality_softFail_on_mapper_error() {
        when(ruleMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        Map<String, Object> q = extras.buildQuality(asset, "dwd_order");
        assertFalse(Boolean.TRUE.equals(q.get("available")));
        assertTrue(Boolean.TRUE.equals(q.get("degraded")));
    }

    @Test
    void lineage_impact_counts() {
        when(govLineageService.impact(isNull(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(Map.of(
                        "up", List.of(Map.of("table", "ods.t")),
                        "down", List.of(Map.of("table", "dws.t"), Map.of("table", "ads.t"))));
        Map<String, Object> lin = extras.buildLineage(asset, "dwd_order");
        assertTrue(Boolean.TRUE.equals(lin.get("available")));
        assertEquals(1, lin.get("upCount"));
        assertEquals(2, lin.get("downCount"));
        assertNotNull(lin.get("path"));
    }

    @Test
    void lineage_softFail() {
        when(govLineageService.impact(isNull(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("om down"));
        Map<String, Object> lin = extras.buildLineage(asset, "dwd_order");
        assertFalse(Boolean.TRUE.equals(lin.get("available")));
        assertTrue(Boolean.TRUE.equals(lin.get("degraded")));
    }
}
