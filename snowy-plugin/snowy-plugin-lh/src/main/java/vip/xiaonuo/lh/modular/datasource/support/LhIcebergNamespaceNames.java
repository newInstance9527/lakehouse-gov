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
package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iceberg Grav schema（namespace）名：来自数据源 database / 命名空间清单 / 表清单对象名。
 */
public final class LhIcebergNamespaceNames {

    private static final Set<String> SKIP = Set.of(
            "information_schema", "sys", "performance_schema");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private LhIcebergNamespaceNames() {
    }

    public static List<String> resolve(LhDatasource ds, Collection<String> extraObjectNames) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ds != null) {
            addIdent(out, ds.getDatabaseName());
            for (String raw : split(ds.getSchemaSummary())) {
                addObject(out, raw);
            }
        }
        if (extraObjectNames != null) {
            for (String raw : extraObjectNames) {
                addObject(out, raw);
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> split(String summary) {
        if (StrUtil.isBlank(summary)) {
            return Collections.emptyList();
        }
        return Arrays.stream(summary.split("[,;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 从清单/资产 objectName 解析 Grav schema + table。
     * <p>{@code log.ods_xxx} → schema=log, table=ods_xxx；
     * {@code iceberg.log.ods_xxx} → schema=log, table=ods_xxx；
     * 无点号时用 fallbackSchema（勿把 HMS 占位 {@code hms} 当成真实 Iceberg NS——有点号时一律以 objectName 为准）。</p>
     */
    public static SchemaTable parseSchemaTable(String objectName, String fallbackSchema) {
        String fb = StrUtil.blankToDefault(StrUtil.trim(fallbackSchema), "default");
        String s = StrUtil.trim(objectName);
        if (StrUtil.isBlank(s) || s.contains("://") || s.contains("/")
                || s.toLowerCase(Locale.ROOT).startsWith("s3")) {
            return new SchemaTable(fb, null);
        }
        String[] parts = s.split("\\.");
        if (parts.length >= 3 && "iceberg".equalsIgnoreCase(parts[0])) {
            return new SchemaTable(parts[1], parts[parts.length - 1]);
        }
        if (parts.length >= 2) {
            return new SchemaTable(parts[0], parts[parts.length - 1]);
        }
        return new SchemaTable(fb, parts[0]);
    }

    /**
     * 按数据源类型选择默认 Grav schema，再解析 objectName。
     * <ul>
     *   <li>PostgreSQL：库名 ≠ schema；裸表名默认 {@code public}</li>
     *   <li>MySQL / Doris：库名即 schema</li>
     *   <li>Iceberg：默认命名空间用 dataSource.databaseName</li>
     * </ul>
     */
    public static SchemaTable resolveSchemaTable(LhDatasource ds, String objectName) {
        String type = ds == null ? "" : StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        String fallback;
        if (type.contains("postgres")) {
            fallback = "public";
        } else if ("iceberg".equals(type)) {
            fallback = StrUtil.blankToDefault(ds.getDatabaseName(), "default");
        } else {
            // mysql / doris / hive / 其它：库名常即 Grav schema
            fallback = StrUtil.blankToDefault(ds == null ? null : ds.getDatabaseName(), "default");
        }
        return parseSchemaTable(objectName, fallback);
    }

    /** Grav 坐标：schema（namespace）+ table 短名。 */
    public record SchemaTable(String schema, String table) {
    }

    static void addObject(Set<String> out, String raw) {
        String s = StrUtil.trim(raw);
        if (StrUtil.isBlank(s) || s.contains("://") || s.contains("/")
                || s.toLowerCase(Locale.ROOT).startsWith("s3")) {
            return;
        }
        if (!s.contains(".")) {
            addIdent(out, s);
            return;
        }
        SchemaTable st = parseSchemaTable(s, null);
        if (st != null && StrUtil.isNotBlank(st.schema())) {
            addIdent(out, st.schema());
        }
    }

    static void addIdent(Set<String> out, String ns) {
        if (StrUtil.isBlank(ns)) {
            return;
        }
        String t = ns.trim();
        if (SKIP.contains(t.toLowerCase(Locale.ROOT)) || !IDENT.matcher(t).matches()) {
            return;
        }
        out.add(t);
    }
}
