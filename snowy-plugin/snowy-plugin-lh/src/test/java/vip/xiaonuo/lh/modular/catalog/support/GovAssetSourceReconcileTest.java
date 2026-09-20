package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetSourceReconcileTest {

    @Mock
    private GovAssetSourceLinkMapper linkMapper;
    @Mock
    private GovAssetMapper assetMapper;
    @Mock
    private LhDsTableMapper dsTableMapper;
    @Mock
    private PlatOutboxService platOutboxService;

    @InjectMocks
    private GovAssetSourceReconcile reconcile;

    @Test
    void markOne_stalesLinkAndDegradesAsset() {
        GovAssetSourceLink link = new GovAssetSourceLink();
        link.setId("l1");
        link.setAssetId("a1");
        link.setDsId("ds1");
        link.setObjectName("t_order");
        link.setLinkRole("primary");
        link.setStatus("active");
        link.setRevision(1);

        GovAsset asset = new GovAsset();
        asset.setId("a1");
        asset.setStatus(GovAssetStatusEnum.ACTIVE.getValue());
        asset.setRevision(1);

        when(linkMapper.selectList(any())).thenReturn(List.of(link));
        when(assetMapper.selectById("a1")).thenReturn(asset);
        when(platOutboxService.appendSoft(anyString(), anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn("evt-1");

        Map<String, Object> r = reconcile.markOne("ds1", "t_order", "inventory_removed");
        assertTrue(Boolean.TRUE.equals(r.get("linkUpdated")));
        assertTrue(Boolean.TRUE.equals(r.get("assetDegraded")));
        assertEquals("stale", link.getStatus());
        assertEquals(GovAssetStatusEnum.DEGRADED.getValue(), asset.getStatus());
        verify(linkMapper).updateById(link);
        verify(assetMapper).updateById(asset);
        verify(platOutboxService).appendSoft(
                eq("catalog.asset.source.stale"), eq("gov_asset"), eq("a1"), anyMap(), anyMap());
    }
}
