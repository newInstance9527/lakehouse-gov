package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;
import vip.xiaonuo.lh.modular.standard.entity.GovStdDetectResult;
import vip.xiaonuo.lh.modular.standard.entity.GovStdMapping;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdDetectResultMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetCrossModuleExtrasStandardTest {

    @Mock
    private GovDqRuleMapper ruleMapper;
    @Mock
    private GovDqRuleRunMapper runMapper;
    @Mock
    private GovLineageService govLineageService;
    @Mock
    private GovStdMappingMapper mappingMapper;
    @Mock
    private GovStdDetectResultMapper detectMapper;

    @InjectMocks
    private GovAssetCrossModuleExtras extras;

    private GovAsset asset;

    @BeforeEach
    void setUp() {
        asset = new GovAsset();
        asset.setId("a1");
        asset.setWs("default");
        asset.setAssetCode("dwd.dwd_order");
        asset.setOmFqn("lake.dwd.dwd_order");
    }

    @Test
    void standard_coverage_by_columns() {
        GovStdMapping m = new GovStdMapping();
        m.setAssetId("a1");
        m.setStdFieldName("order_id");
        m.setSrcField("id");
        m.setTargetTable("dwd_order");
        when(mappingMapper.selectList(any())).thenReturn(List.of(m));
        when(detectMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> schema = Map.of("columns", List.of(
                Map.of("name", "order_id", "type", "BIGINT"),
                Map.of("name", "amount", "type", "DECIMAL"),
                Map.of("name", "id", "type", "BIGINT")));

        Map<String, Object> std = extras.buildStandard(asset, "dwd_order", schema);
        assertTrue(Boolean.TRUE.equals(std.get("available")));
        assertEquals(3, std.get("columnCount"));
        assertEquals(2, std.get("coveredCount")); // order_id + id
        assertEquals(67, std.get("coveragePct"));
        assertTrue(String.valueOf(std.get("path")).contains("/standard"));
        List<?> gaps = (List<?>) std.get("gaps");
        assertEquals(1, gaps.size());
        assertEquals("amount", gaps.get(0));
    }

    @Test
    void standard_no_columns_with_mappings() {
        GovStdMapping m = new GovStdMapping();
        m.setTargetTable("dwd_order");
        m.setStdFieldName("x");
        when(mappingMapper.selectList(any())).thenReturn(List.of(m));
        when(detectMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> std = extras.buildStandard(asset, "dwd_order", Map.of("columns", List.of()));
        assertTrue(Boolean.TRUE.equals(std.get("available")));
        assertNull(std.get("coveragePct"));
        assertEquals(1, std.get("mappingCount"));
    }

    @Test
    void standard_softFail() {
        when(mappingMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        Map<String, Object> std = extras.buildStandard(asset, "dwd_order", Map.of());
        assertFalse(Boolean.TRUE.equals(std.get("available")));
        assertTrue(Boolean.TRUE.equals(std.get("degraded")));
    }

    @Test
    void mappingMatches_by_asset_and_table() {
        GovStdMapping byAsset = new GovStdMapping();
        byAsset.setAssetId("a1");
        assertTrue(GovAssetCrossModuleExtras.mappingMatches(byAsset, "a1", "t", "t", null));

        GovStdMapping byTable = new GovStdMapping();
        byTable.setTargetTable("dwd.dwd_order");
        assertTrue(GovAssetCrossModuleExtras.mappingMatches(byTable, null, "dwd_order", "dwd_order", null));
        assertFalse(GovAssetCrossModuleExtras.mappingMatches(byTable, null, "other", "other", null));
    }

    @Test
    void detectMatches_counts_fail() {
        GovStdDetectResult d = new GovStdDetectResult();
        d.setTableName("dwd_order");
        d.setFieldName("amount");
        d.setStatus("fail");
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        when(detectMapper.selectList(any())).thenReturn(List.of(d));

        Map<String, Object> schema = Map.of("columns", List.of(
                Map.of("name", "amount", "type", "DECIMAL"),
                Map.of("name", "x", "type", "INT")));
        Map<String, Object> std = extras.buildStandard(asset, "dwd_order", schema);
        assertEquals(1, std.get("detectFailCount"));
        assertEquals(50, std.get("coveragePct"));
    }
}
