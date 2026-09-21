/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceGravitinoProjector;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 湖表预览：<b>先 Gravitino 列结构</b>，行样例再可选走 Trino（用 Grav 坐标，不臆造 catalog）。
 * <p>完整 Iceberg FileIO 扫样例可后续增强；当前门户侧不以 Trino 为湖表唯一入口。</p>
 */
@Slf4j
@Component
@Order(40)
public class IcebergGravPreviewAdapter implements GovAssetPreviewAdapter {

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private LhTrinoPrincipalService principalService;

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        return PreviewAdapterSupport.isLakeSource(ctx);
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        Map<String, Object> r = ctx.newResult();
        GovAsset asset = ctx.getAsset();
        Coord coord = resolveCoord(ctx);
        if (coord == null) {
            Map<String, Object> miss = PreviewAdapterSupport.emptyFail(r, "gravitino",
                    "无法解析 Grav 坐标：请 refresh 挂接 cb_grav_asset_ref");
            miss.put("tryNext", true);
            miss.put("degraded", true);
            return miss;
        }
        String qualified = PreviewAdapterSupport.quoteIdent(coord.catalog) + "."
                + PreviewAdapterSupport.quoteIdent(coord.schema) + "."
                + PreviewAdapterSupport.quoteIdent(coord.table);
        r.put("qualifiedName", qualified);
        r.put("grav", Map.of(
                "metalake", coord.metalake,
                "catalog", coord.catalog,
                "schema", coord.schema,
                "table", coord.table,
                "location", StrUtil.blankToDefault(coord.location, "")));

        List<String> columns = new ArrayList<>();
        String gravMsg = null;
        try {
            GravitinoClient.GravTable gt = gravitinoClient.loadTable(
                    coord.metalake, coord.catalog, coord.schema, coord.table);
            if (gt != null && gt.columns != null) {
                for (GravitinoClient.GravColumn c : gt.columns) {
                    if (c != null && StrUtil.isNotBlank(c.name)) {
                        columns.add(c.name);
                    }
                }
                if (StrUtil.isNotBlank(gt.location)) {
                    coord.location = gt.location;
                    r.put("grav", Map.of(
                            "metalake", coord.metalake,
                            "catalog", coord.catalog,
                            "schema", coord.schema,
                            "table", coord.table,
                            "location", gt.location));
                }
            }
        } catch (Exception e) {
            gravMsg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.warn("Grav loadTable soft-fail {}: {}", qualified, gravMsg);
        }

        // 行样例：Trino 仅作回退（坐标来自 Grav，不臆造 mysql 等）
        List<Map<String, Object>> rows = new ArrayList<>();
        String trinoMsg = null;
        String sql = "SELECT * FROM " + qualified + " LIMIT " + ctx.getLimit();
        r.put("sql", sql);
        try {
            var principal = principalService.requireCurrent();
            TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.human(principal.getTrinoUser(), ctx.getLimit());
            opts.catalog = coord.catalog;
            opts.schema = coord.schema;
            Map<String, Object> exec = trinoClient.execute(sql, opts);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                trinoMsg = PreviewAdapterSupport.str(exec.get("message"));
            } else {
                @SuppressWarnings("unchecked")
                List<String> trinoCols = (List<String>) exec.getOrDefault("columns", List.of());
                if (columns.isEmpty() && trinoCols != null) {
                    columns.addAll(trinoCols);
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> trinoRows =
                        (List<Map<String, Object>>) exec.getOrDefault("rows", List.of());
                if (trinoRows != null) {
                    rows.addAll(trinoRows);
                }
            }
        } catch (Exception e) {
            trinoMsg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        }

        if (!columns.isEmpty() || !rows.isEmpty()) {
            r.put("ok", true);
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            if (!rows.isEmpty() && gravMsg == null) {
                r.put("source", "gravitino+trino");
                r.put("message", "湖表：Gravitino 结构 + Trino 行样例回退");
            } else if (!columns.isEmpty() && rows.isEmpty()) {
                r.put("source", "gravitino");
                r.put("message", "已返回 Gravitino 列结构"
                        + (StrUtil.isBlank(trinoMsg) ? "" : "；行样例 Trino 不可用: " + trinoMsg));
            } else {
                r.put("source", "trino");
                r.put("message", "Gravitino 结构不可用，已用 Trino 行样例回退"
                        + (StrUtil.isBlank(gravMsg) ? "" : "（Grav: " + gravMsg + "）"));
            }
            return r;
        }

        Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "gravitino",
                "湖表预览失败"
                        + (StrUtil.isBlank(gravMsg) ? "" : "；Grav: " + gravMsg)
                        + (StrUtil.isBlank(trinoMsg) ? "" : "；Trino: " + trinoMsg));
        fail.put("degraded", true);
        // Catalog 不存在等：允许后续 MetaOnly 等适配器给出明确「本类型不预览」提示
        boolean noCatalog = StrUtil.containsIgnoreCase(gravMsg, "NoSuchCatalog")
                || StrUtil.containsIgnoreCase(gravMsg, "does not exist");
        fail.put("tryNext", noCatalog);
        return fail;
    }

    private Coord resolveCoord(GovAssetPreviewContext ctx) {
        GovAsset asset = ctx.getAsset();
        String metalake = lhProperties.getGravitino() == null ? "lakehouse"
                : StrUtil.blankToDefault(lhProperties.getGravitino().getMetalake(), "lakehouse");
        String catalog = lhProperties.getGravitino() == null ? "iceberg"
                : StrUtil.blankToDefault(lhProperties.getGravitino().getCatalog(), "iceberg");
        String schema = null;
        String table = null;
        String location = null;

        if (asset != null && StrUtil.isNotBlank(asset.getGravAssetId())) {
            CbGravAssetRef ref = gravAssetRefMapper.selectById(asset.getGravAssetId());
            if (ref != null) {
                if (StrUtil.isNotBlank(ref.getGravCatalog())) {
                    catalog = ref.getGravCatalog();
                }
                schema = ref.getGravSchema();
                table = ref.getGravTable();
                location = ref.getLocationUri();
            }
        }
        LhDatasource ds = ctx.getPrimaryDs();
        if (ds != null && StrUtil.isBlank(schema)) {
            try {
                String cat = gravitinoProjector.catalogNameOf(ds);
                if (StrUtil.isNotBlank(cat)) {
                    catalog = cat;
                }
            } catch (Exception ignored) {
                // soft
            }
            schema = PreviewAdapterSupport.firstNonBlank(ds.getDatabaseName(),
                    buildLayerDomainSchema(asset), "default");
        }
        if (StrUtil.isBlank(table)) {
            table = PreviewAdapterSupport.shortName(ctx.getObjectName());
        }
        if (StrUtil.isBlank(schema)) {
            schema = PreviewAdapterSupport.firstNonBlank(buildLayerDomainSchema(asset), "default");
        }
        if (StrUtil.isBlank(table)) {
            return null;
        }
        Coord c = new Coord();
        c.metalake = metalake;
        c.catalog = catalog;
        c.schema = schema;
        c.table = table;
        c.location = location;
        return c;
    }

    private static String buildLayerDomainSchema(GovAsset asset) {
        if (asset == null || StrUtil.isBlank(asset.getLayer())) {
            return null;
        }
        if (StrUtil.isNotBlank(asset.getDomainCode())) {
            return asset.getLayer() + "_" + asset.getDomainCode();
        }
        return asset.getLayer();
    }

    private static final class Coord {
        String metalake;
        String catalog;
        String schema;
        String table;
        String location;
    }
}
