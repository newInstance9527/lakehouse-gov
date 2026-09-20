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
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RDB / JDBC 源探查预览（非 Trino 联邦）。
 */
@Slf4j
@Component
@Order(10)
public class JdbcPreviewAdapter implements GovAssetPreviewAdapter {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        return PreviewAdapterSupport.isJdbcSource(ctx.getPrimaryDs())
                && StrUtil.isNotBlank(ctx.getObjectName());
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String objectName = ctx.getObjectName();
        int limit = ctx.getLimit();
        Map<String, Object> r = ctx.newResult();
        String tableRef = jdbcTableRef(objectName, ds);
        String type = PreviewAdapterSupport.dsType(ds);
        String sql;
        if (type.contains("postgres") || "pg".equals(type)) {
            sql = "SELECT * FROM " + tableRef + " LIMIT " + limit;
        } else if (type.contains("sqlserver")) {
            sql = "SELECT TOP " + limit + " * FROM " + tableRef;
        } else if (type.contains("oracle")) {
            sql = "SELECT * FROM " + tableRef + " FETCH FIRST " + limit + " ROWS ONLY";
        } else {
            sql = "SELECT * FROM " + tableRef + " LIMIT " + limit;
        }
        r.put("qualifiedName", StrUtil.blankToDefault(ds.getDatabaseName(), ds.getDsCode())
                + "." + PreviewAdapterSupport.shortName(objectName));
        r.put("sql", sql);
        try {
            if (StrUtil.isBlank(ds.getVaultPath())) {
                return PreviewAdapterSupport.emptyFail(r, "jdbc", "数据源无 vaultPath，无法直连预览");
            }
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String url = PreviewAdapterSupport.firstNonBlank(PreviewAdapterSupport.str(secret.get("jdbcUrl")), null);
            String user = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("username")),
                    PreviewAdapterSupport.str(secret.get("user")), null);
            String pwd = PreviewAdapterSupport.firstNonBlank(PreviewAdapterSupport.str(secret.get("password")), "");
            if (StrUtil.isBlank(url)) {
                return PreviewAdapterSupport.emptyFail(r, "jdbc", "Vault 中无 jdbcUrl");
            }
            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(url, user, pwd);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cc = md.getColumnCount();
                for (int i = 1; i <= cc; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cc; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            r.put("ok", true);
            r.put("source", "jdbc");
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("message", "数据源 JDBC 探查预览（非分析联邦路径）");
            return r;
        } catch (Exception e) {
            log.warn("JDBC preview fail ds={}: {}", ds.getId(), e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "jdbc",
                    "JDBC 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            fail.put("tryNext", false);
            return fail;
        }
    }

    private static String jdbcTableRef(String objectName, LhDatasource ds) {
        String type = PreviewAdapterSupport.dsType(ds);
        String[] parts = StrUtil.blankToDefault(objectName, "object").trim().split("\\.");
        String schema;
        String table;
        if (parts.length >= 3) {
            String cat = parts[parts.length - 3].toLowerCase(Locale.ROOT);
            if (isRdbInventedCatalog(cat)) {
                schema = parts[parts.length - 2];
                table = parts[parts.length - 1];
            } else {
                schema = parts[parts.length - 2];
                table = parts[parts.length - 1];
            }
        } else if (parts.length == 2) {
            schema = parts[0];
            table = parts[1];
        } else {
            table = parts[0];
            schema = ds == null ? null : ds.getDatabaseName();
        }
        if (type.contains("postgres") || "pg".equals(type) || type.contains("oracle")) {
            if (StrUtil.isNotBlank(schema)) {
                return "\"" + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + "\"";
            }
            return "\"" + table.replace("\"", "\"\"") + "\"";
        }
        if (type.contains("sqlserver")) {
            if (StrUtil.isNotBlank(schema)) {
                return "[" + schema.replace("]", "]]") + "].[" + table.replace("]", "]]") + "]";
            }
            return "[" + table.replace("]", "]]") + "]";
        }
        if (StrUtil.isNotBlank(schema) && (ds == null || !schema.equalsIgnoreCase(ds.getDatabaseName()))) {
            return "`" + schema.replace("`", "``") + "`.`" + table.replace("`", "``") + "`";
        }
        return "`" + table.replace("`", "``") + "`";
    }

    private static boolean isRdbInventedCatalog(String cat) {
        if (StrUtil.isBlank(cat)) {
            return false;
        }
        String c = cat.toLowerCase(Locale.ROOT);
        return "mysql".equals(c) || "postgresql".equals(c) || "postgres".equals(c) || "pg".equals(c)
                || "oracle".equals(c) || "sqlserver".equals(c) || "clickhouse".equals(c) || "doris".equals(c);
    }
}
