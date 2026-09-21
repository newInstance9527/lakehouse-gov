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
package vip.xiaonuo.lh.modular.datasource.service;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.LhOmCatalogClassifier;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;
import vip.xiaonuo.lh.modular.schemasync.param.LhSchemaSyncRunParam;
import vip.xiaonuo.lh.modular.schemasync.service.LhSchemaSyncService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;

/**
 * 数据源登记后编排：Grav Catalog（可映射）→ SQLREST 投影 → 门户表清单 → JDBC/Grav→OM 或 门户清单→OM 直通
 * <p>各步独立 soft-fail，不回滚门户登记；不写引擎 ACL（权限 SoT 仍在平台）。</p>
 * <p>SQLREST：可投影类型在登记/连接变更时写入 Manager，保证构建 API 左栏可选「全部已投影源」。</p>
 * <p>P0 门户+Vault；P1 库表/Hive→Grav+OM；P2 RMQ/Redis/ES/Kafka/MinIO 等门户→OM（不经 Grav）。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Service
public class LhDatasourcePostRegisterBridge {

    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private LhDatasourceSqlrestProjector sqlrestProjector;
    @Resource
    private LhSchemaSyncService schemaSyncService;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhPortalInventoryOmBridge portalInventoryOmBridge;
    @Lazy
    @Resource
    private LhDatasourceService datasourceService;

    /**
     * 登记或连接变更后的投影与同步
     */
    public Map<String, Object> afterPersist(LhDatasource ds) {
        Map<String, Object> out = new LinkedHashMap<>();
        String catalog = gravitinoProjector.catalogNameOf(ds);
        out.put("catalog", catalog);

        Map<String, Object> grav = gravitinoProjector.project(ds);
        out.put("gravitino", grav);

        // 数据服务：登记即投影到 SQLREST（不可投影类型 skipped；失败 soft-fail）
        Map<String, Object> sqlrest = projectSqlrest(ds);
        out.put("sqlrest", sqlrest);

        Map<String, Object> tables = syncPortalTables(ds);
        out.put("tables", tables);

        // JDBC / Grav→OM：不支持 Grav 的类型或投影已 skipped 时，禁止再调 Grav listSchemas（否则 500 被当成同步失败）
        Map<String, Object> om = syncOmPreferJdbc(ds, catalog, tables, grav);
        out.put("openmetadata", om);
        log.info("Post-register bridge done dsId={} catalog={} gravSkipped={} sqlrestOk={} sqlrestSkipped={} tablesOk={} omOk={}",
                ds.getId(), catalog, grav.get("skipped"), sqlrest.get("ok"), sqlrest.get("skipped"),
                tables.get("ok"), om.get("ok"));
        return out;
    }

    private Map<String, Object> projectSqlrest(LhDatasource ds) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Map<String, Object> one = sqlrestProjector.project(ds);
            r.putAll(one);
            if (!r.containsKey("ok")) {
                r.put("ok", Boolean.TRUE.equals(one.get("ok")));
            }
        } catch (Exception e) {
            log.warn("SQLREST project failed for {}: {}", ds.getId(), e.getMessage());
            r.put("ok", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> syncPortalTables(LhDatasource ds) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            LhDatasourceIdParam p = new LhDatasourceIdParam();
            p.setId(ds.getId());
            Map<String, Object> sync = datasourceService.tableSync(p);
            r.putAll(sync);
            r.put("ok", true);
        } catch (Exception e) {
            log.warn("Portal table sync failed for {}: {}", ds.getId(), e.getMessage());
            r.put("ok", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> syncOmPreferJdbc(LhDatasource ds, String catalog,
                                                 Map<String, Object> tableResult,
                                                 Map<String, Object> gravResult) {
        Map<String, Object> r = new LinkedHashMap<>();
        // 1) JDBC → OM（业务数据源真实表）
        try {
            Map<String, Object> jdbc = pushJdbcStructureToOm(ds, catalog);
            r.put("jdbc", jdbc);
            Object upserted = jdbc.get("upserted");
            Object errors = jdbc.get("errors");
            if (upserted instanceof Number n && n.intValue() > 0) {
                r.put("ok", true);
                r.put("path", "jdbc");
            } else if (errors instanceof Number e && e.intValue() > 0) {
                r.put("jdbcPartialFail", true);
            }
        } catch (Exception e) {
            log.warn("JDBC→OM failed for {}: {}", ds.getId(), e.getMessage());
            r.put("jdbcError", e.getMessage());
        }

        if (!shouldRunGravOmSync(ds, gravResult)) {
            r.put("gravSync", Map.of(
                    "skipped", true,
                    "reason", gravSkipReason(ds, gravResult)));
            // P2：门户清单 → OM 直通（与 Grav 解耦）
            if (portalInventoryOmBridge.shouldPush(ds)) {
                try {
                    Map<String, Object> portalOm = portalInventoryOmBridge.pushInventory(ds, catalog);
                    r.put("portalOm", portalOm);
                    int upserted = portalOm.get("upserted") instanceof Number n ? n.intValue() : 0;
                    if (upserted > 0) {
                        r.put("ok", true);
                        r.put("path", "portal_om");
                        r.putIfAbsent("omService", portalOm.get("omService"));
                    } else if (!Boolean.TRUE.equals(r.get("ok"))) {
                        boolean tablesOk = tableResult != null && Boolean.TRUE.equals(tableResult.get("ok"));
                        r.put("ok", true);
                        r.put("path", tablesOk ? "portal_inventory" : "portal_only");
                        r.put("hint", String.valueOf(portalOm.getOrDefault("hint",
                                gravSkipReason(ds, gravResult))));
                    }
                } catch (Exception e) {
                    log.warn("Portal→OM failed for {}: {}", ds.getId(), e.getMessage());
                    r.put("portalOmError", e.getMessage());
                    if (!Boolean.TRUE.equals(r.get("ok"))) {
                        r.put("ok", true);
                        r.put("path", "portal_only");
                        r.put("hint", "门户已保存；OM 直通失败：" + e.getMessage());
                    }
                }
            } else if (!Boolean.TRUE.equals(r.get("ok"))) {
                boolean tablesOk = tableResult != null && Boolean.TRUE.equals(tableResult.get("ok"));
                r.put("ok", true);
                r.put("path", tablesOk ? "portal_inventory" : "portal_only");
                r.put("hint", gravSkipReason(ds, gravResult));
            }
            return r;
        }

        // 2) Grav→OM（仅已投影且具备 relational 表语义的类型）
        try {
            LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, catalog);
            LhSchemaSyncRunParam param = new LhSchemaSyncRunParam();
            param.setCatalog(catalog);
            param.setForce(true);
            param.setSchemas(classify.schemaName);
            param.setLakeService(classify.serviceName);
            param.setLakeDatabase(classify.databaseName);
            param.setOmServiceType(classify.serviceType);
            param.setOmServiceDisplayName(classify.serviceDisplayName);
            param.setOmServiceDescription(classify.serviceDescription);
            param.setOmDatabaseDisplayName(classify.databaseDisplayName);
            param.setOmDatabaseDescription(classify.databaseDescription);
            Map<String, Object> sync = schemaSyncService.run(param);
            r.put("gravSync", sync);
            r.putIfAbsent("omService", classify.serviceName);
            r.putIfAbsent("omCategory", classify.category);
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                int upserted = sync.get("upserted") instanceof Number n ? n.intValue() : 0;
                boolean skipped = Boolean.TRUE.equals(sync.get("skipped"));
                r.put("ok", upserted > 0 || skipped);
                if (upserted > 0) {
                    r.put("path", "grav");
                } else if (skipped) {
                    r.put("path", "grav_skipped");
                }
            }
        } catch (Exception e) {
            log.warn("Grav→OM failed for {}: {}", ds.getId(), e.getMessage());
            r.put("gravSyncError", e.getMessage());
            // Grav 失败不覆盖门户清单成功
            if (!r.containsKey("ok")) {
                boolean tablesOk = tableResult != null && Boolean.TRUE.equals(tableResult.get("ok"));
                r.put("ok", tablesOk);
                if (!tablesOk) {
                    r.put("error", e.getMessage());
                } else {
                    r.put("hint", "门户清单已同步；Grav→OM 失败（可忽略或不支持）：" + e.getMessage());
                }
            }
        }
        if (!r.containsKey("ok")) {
            r.put("ok", false);
            r.put("error", "no tables pushed to OM");
        }
        return r;
    }

    /** Grav→OM 仅对「已投影且有表级 listSchemas/tables」的类型执行 */
    private boolean shouldRunGravOmSync(LhDatasource ds, Map<String, Object> gravResult) {
        if (ds == null) {
            return false;
        }
        if (gravResult != null && Boolean.TRUE.equals(gravResult.get("skipped"))) {
            return false;
        }
        if (!gravitinoProjector.supportsGravitino(ds.getType())) {
            return false;
        }
        String t = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        // Fileset / messaging：Catalog 有，但不走 relational Schema Sync（listTables 会 500）
        return switch (t) {
            case "s3", "minio", "hdfs", "kafka" -> false;
            default -> true;
        };
    }

    private static String gravSkipReason(LhDatasource ds, Map<String, Object> gravResult) {
        if (gravResult != null && gravResult.get("message") != null) {
            return String.valueOf(gravResult.get("message"));
        }
        if (gravResult != null && Boolean.TRUE.equals(gravResult.get("skipped"))) {
            return String.valueOf(gravResult.getOrDefault("reason", "grav_skipped"));
        }
        String t = ds == null ? "" : StrUtil.blankToDefault(ds.getType(), "");
        return "type_no_grav_om:" + t;
    }

    @Deprecated
    @SuppressWarnings("unused")
    private Map<String, Object> syncOm(LhDatasource ds, String catalog, Map<String, Object> tableResult) {
        return syncOmPreferJdbc(ds, catalog, tableResult, Map.of("skipped", true));
    }

    /**
     * Grav 尚未列出表时：用 JDBC 列信息直接 upsert OM（仍挂在登记投影的 catalog 名下）
     */
    private Map<String, Object> pushJdbcStructureToOm(LhDatasource ds, String catalog) {
        Map<String, Object> r = new LinkedHashMap<>();
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        if (typeEnum == null || !typeEnum.isJdbc()) {
            r.put("skipped", true);
            r.put("reason", "not_jdbc");
            return r;
        }
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String url = str(secret.get("jdbcUrl"));
        String user = first(secret, "username", "user");
        String pwd = first(secret, "password");
        if (StrUtil.isBlank(url)) {
            r.put("skipped", true);
            r.put("reason", "no_jdbc_url");
            return r;
        }
        LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, catalog);
        // 按类型分类挂载：lh_mysql / lh_postgresql …
        String omService = classify.serviceName;
        String omDatabase = classify.databaseName;
        String omSchema = classify.schemaName;
        r.put("omService", omService);
        r.put("omServiceType", classify.serviceType);
        r.put("omCategory", classify.category);
        int upserted = 0;
        int errors = 0;
        List<String> msgs = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
            List<String> tables = listJdbcTableNames(conn, ds, secret);
            for (String table : tables) {
                try {
                    GravitinoClient.GravTable gt = loadJdbcAsGrav(conn, catalog, omSchema, table);
                    openMetadataClient.upsertTableStructure(omService, omDatabase, omSchema, gt, classify);
                    upserted++;
                } catch (Exception e) {
                    errors++;
                    if (msgs.size() < 10) {
                        msgs.add(table + ": " + e.getMessage());
                    }
                }
            }
            r.put("tables", tables.size());
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getMessage());
            return r;
        }
        r.put("ok", true);
        r.put("upserted", upserted);
        r.put("errors", errors);
        r.put("errorMsgs", msgs);
        r.put("fqnPrefix", omService + "." + omDatabase + "." + omSchema);
        return r;
    }

    private List<String> listJdbcTableNames(Connection conn, LhDatasource ds, Map<String, Object> secret)
            throws Exception {
        List<String> names = new ArrayList<>();
        String schema = firstNonBlank(str(secret.get("database")), ds.getDatabaseName(), conn.getCatalog());
        String type = ds.getType();
        if ("mysql".equals(type) || "doris".equals(type)) {
            try (var ps = conn.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            return names;
        }
        String schemaPattern = "postgresql".equals(type) || "pg".equals(type) ? "public" : null;
        try (ResultSet rs = conn.getMetaData().getTables(
                conn.getCatalog(), schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private GravitinoClient.GravTable loadJdbcAsGrav(Connection conn, String catalog, String schema, String table)
            throws Exception {
        GravitinoClient.GravTable gt = new GravitinoClient.GravTable();
        gt.metalake = lhProperties.getGravitino().getMetalake();
        gt.catalog = catalog;
        gt.schema = schema;
        gt.name = table;
        gt.auditVersion = System.currentTimeMillis();
        // MySQL：information_schema 比 DatabaseMetaData 更稳
        try (var ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, "
                        + "IS_NULLABLE, COLUMN_COMMENT, COLUMN_TYPE "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GravitinoClient.GravColumn col = new GravitinoClient.GravColumn();
                    col.name = rs.getString("COLUMN_NAME");
                    String colType = rs.getString("COLUMN_TYPE");
                    if (StrUtil.isBlank(colType)) {
                        String dt = rs.getString("DATA_TYPE");
                        Long len = rs.getObject("CHARACTER_MAXIMUM_LENGTH") == null
                                ? null : rs.getLong("CHARACTER_MAXIMUM_LENGTH");
                        col.type = len != null && len > 0 ? dt + "(" + len + ")" : dt;
                    } else {
                        col.type = colType;
                    }
                    col.nullable = !"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    col.comment = rs.getString("COLUMN_COMMENT");
                    gt.columns.add(col);
                }
            }
        } catch (Exception ignored) {
            try (ResultSet rs = conn.getMetaData().getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    if (!table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        continue;
                    }
                    GravitinoClient.GravColumn col = new GravitinoClient.GravColumn();
                    col.name = rs.getString("COLUMN_NAME");
                    col.type = rs.getString("TYPE_NAME");
                    col.nullable = rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
                    col.comment = rs.getString("REMARKS");
                    gt.columns.add(col);
                }
            }
        }
        return gt;
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return "";
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
