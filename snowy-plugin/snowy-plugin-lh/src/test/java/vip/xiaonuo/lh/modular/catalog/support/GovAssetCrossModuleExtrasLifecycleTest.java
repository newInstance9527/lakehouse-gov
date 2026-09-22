package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdDetectResultMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetCrossModuleExtrasLifecycleTest {

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
    @Mock
    private GovLcPolicyMapper lcPolicyMapper;
    @Mock
    private GovLcTableStatMapper lcTableStatMapper;

    @InjectMocks
    private GovAssetCrossModuleExtras extras;

    private GovAsset asset;

    @BeforeEach
    void setUp() {
        asset = new GovAsset();
        asset.setId("a1");
        asset.setWs("default");
        asset.setAssetCode("dwd_order");
        asset.setOmFqn("iceberg.dwd.dwd_order");
    }

    @Test
    void lifecycle_tags_from_policy_and_profile() {
        GovLcPolicy p = new GovLcPolicy();
        p.setTableFqn("iceberg.dwd.dwd_order");
        p.setKeepDays(7);
        p.setCompactLevel("binpack");
        p.setOrphanOlderDays(7);
        when(lcPolicyMapper.selectList(any())).thenReturn(List.of(p));

        GovLcTableStat s = new GovLcTableStat();
        s.setTableFqn("dwd.dwd_order");
        s.setPolicyLabel("默认策略");
        s.setCollectStatus("ok");
        s.setSmallFileRatio(new BigDecimal("0.45"));
        s.setFileCount(120L);
        when(lcTableStatMapper.selectList(any())).thenReturn(List.of(s));

        Map<String, Object> lc = extras.buildLifecycle(asset, "dwd_order");
        assertTrue(Boolean.TRUE.equals(lc.get("available")));
        assertTrue(String.valueOf(lc.get("path")).contains("/lifecycle"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) lc.get("tags");
        assertTrue(tags.stream().anyMatch(t -> t.contains("快照7d")));
        assertTrue(tags.stream().anyMatch(t -> t.contains("compact")));
        assertTrue(tags.contains("默认策略"));
        assertTrue(tags.contains("小文件偏高"));
        assertTrue(tags.contains("画像已采"));
        assertEquals("binpack", ((Map<?, ?>) lc.get("policy")).get("compactLevel"));
    }

    @Test
    void lifecycle_empty_hint() {
        when(lcPolicyMapper.selectList(any())).thenReturn(List.of());
        when(lcTableStatMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> lc = extras.buildLifecycle(asset, "dwd_order");
        assertTrue(Boolean.TRUE.equals(lc.get("available")));
        assertEquals("无生命周期策略/存储画像", lc.get("hint"));
    }
}
