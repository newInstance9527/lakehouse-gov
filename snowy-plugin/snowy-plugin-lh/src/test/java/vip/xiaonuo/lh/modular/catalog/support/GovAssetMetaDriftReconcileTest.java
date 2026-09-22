package vip.xiaonuo.lh.modular.catalog.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.recon.entity.ReconMetaDrift;
import vip.xiaonuo.lh.modular.recon.mapper.ReconMetaDriftMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbOmAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbOmAssetRefMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovAssetMetaDriftReconcileTest {

    @Mock
    private GovAssetMapper assetMapper;
    @Mock
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Mock
    private CbOmAssetRefMapper omAssetRefMapper;
    @Mock
    private ReconMetaDriftMapper driftMapper;
    @Mock
    private PlatOutboxService platOutboxService;
    @Mock
    private VictoriaMetricsClient victoriaMetricsClient;

    @InjectMocks
    private GovAssetMetaDriftReconcile reconcile;

    private GovAsset asset;

    @BeforeEach
    void setUp() {
        asset = new GovAsset();
        asset.setId("a1");
        asset.setWs("default");
        asset.setAssetCode("dwd_order");
        asset.setLayer("dwd");
        asset.setStatus("active");
        asset.setIsGold(1);
        asset.setDeleteFlag("NOT_DELETE");
        asset.setOmAssetId("om1");
        asset.setOmFqn("lake.dwd.dwd_order");
        asset.setGravAssetId("g1");
    }

    @Test
    void orphanGravPointer_opensDrift_andDelistsGold() {
        when(gravAssetRefMapper.selectById("g1")).thenReturn(null);
        CbOmAssetRef om = new CbOmAssetRef();
        om.setId("om1");
        om.setOmFqn("lake.dwd.dwd_order");
        om.setDeleteFlag("NOT_DELETE");
        when(omAssetRefMapper.selectById("om1")).thenReturn(om);
        when(driftMapper.selectOne(any())).thenReturn(null);
        when(driftMapper.selectList(any())).thenReturn(List.of());
        when(assetMapper.selectById("a1")).thenReturn(asset);
        when(platOutboxService.appendSoft(anyString(), anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn("evt1");

        Map<String, Object> r = reconcile.reconcileAsset(asset);

        assertEquals(1, r.get("opened"));
        assertTrue(Boolean.TRUE.equals(r.get("goldDelisted")));
        assertTrue(Boolean.TRUE.equals(r.get("degraded")));
        ArgumentCaptor<ReconMetaDrift> cap = ArgumentCaptor.forClass(ReconMetaDrift.class);
        verify(driftMapper).insert(cap.capture());
        assertEquals(GovAssetMetaDriftReconcile.TYPE_ORPHAN_GRAV_POINTER, cap.getValue().getDriftType());
        verify(platOutboxService).appendSoft(
                eq("catalog.asset.meta_drift.delisted"),
                eq("gov_asset"),
                eq("a1"),
                anyMap(),
                anyMap());
    }

    @Test
    void alignedPointers_noOpenNoDelist() {
        when(gravAssetRefMapper.selectById("g1")).thenAnswer(inv -> {
            var g = new vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef();
            g.setId("g1");
            g.setDeleteFlag("NOT_DELETE");
            return g;
        });
        CbOmAssetRef om = new CbOmAssetRef();
        om.setId("om1");
        om.setOmFqn("lake.dwd.dwd_order");
        om.setDeleteFlag("NOT_DELETE");
        when(omAssetRefMapper.selectById("om1")).thenReturn(om);
        when(driftMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> r = reconcile.reconcileAsset(asset);

        assertEquals(0, r.get("opened"));
        verify(driftMapper, never()).insert(any());
        verify(platOutboxService, never()).appendSoft(anyString(), anyString(), anyString(), anyMap(), anyMap());
        assertTrue(!Boolean.TRUE.equals(r.get("goldDelisted")));
    }
}
