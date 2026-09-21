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
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PreviewAdapterSupport {

    private PreviewAdapterSupport() {
    }

    static String dsType(LhDatasource ds) {
        return StrUtil.blankToDefault(ds == null ? null : ds.getType(), "").toLowerCase(Locale.ROOT);
    }

    static boolean isJdbcSource(LhDatasource ds) {
        if (ds == null) {
            return false;
        }
        String raw = dsType(ds);
        if (raw.contains("iceberg") || raw.contains("hive") || "trino".equals(raw)) {
            return false;
        }
        Optional<LhDatasourceTypeEnum> t = LhDatasourceTypeEnum.of(ds.getType());
        if (t.isPresent()) {
            LhDatasourceTypeEnum e = t.get();
            if (e == LhDatasourceTypeEnum.ICEBERG || e == LhDatasourceTypeEnum.HIVE || e == LhDatasourceTypeEnum.TRINO) {
                return false;
            }
            return e.isJdbc();
        }
        return raw.contains("mysql") || raw.contains("postgres") || raw.contains("pg")
                || raw.contains("oracle") || raw.contains("sqlserver") || raw.contains("clickhouse")
                || raw.contains("doris");
    }

    /**
     * 是否走湖表 Grav 预览。
     * <p>已知非湖源（ES/Kafka/RDB/…）即使误挂 {@code gravAssetId} 也不走 Grav；
     * 仅 Iceberg/Hive（及引擎/指针明确为湖表）才命中。</p>
     */
    public static boolean isLakeSource(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String raw = dsType(ds);
        GovAsset asset = ctx.getAsset();

        // 非关系型形态（index/topic/…）永不走湖表适配器
        if (asset != null && isNonRelationalKind(asset.getAssetKind())) {
            return false;
        }
        if (ctx.getPrimaryLink() != null && isNonRelationalKind(ctx.getPrimaryLink().getObjectKind())) {
            return false;
        }

        // 数据源类型明确为湖
        if (isLakeDsType(ds, raw)) {
            return true;
        }
        // 已知枚举且非湖 → 禁止仅凭 gravAssetId 误路由（ES 等常误挂指针）
        if (ds != null) {
            Optional<LhDatasourceTypeEnum> t = LhDatasourceTypeEnum.of(ds.getType());
            if (t.isPresent() && !isLakeEnum(t.get())) {
                return false;
            }
            if (isJdbcSource(ds)) {
                return false;
            }
            if (isExplicitNonLakeRaw(raw)) {
                return false;
            }
        }

        if (asset != null) {
            if (StrUtil.isNotBlank(asset.getGravAssetId())) {
                return true;
            }
            String eng = StrUtil.blankToDefault(asset.getEngine(), "").toLowerCase(Locale.ROOT);
            if (eng.contains("iceberg") || eng.contains("hive")) {
                return true;
            }
        }
        return false;
    }

    static boolean isLakeDsType(LhDatasource ds, String raw) {
        String t = StrUtil.blankToDefault(raw, dsType(ds));
        if (t.contains("iceberg") || t.contains("hive") || t.contains("gravitino")) {
            return true;
        }
        if (ds == null) {
            return false;
        }
        return LhDatasourceTypeEnum.of(ds.getType()).map(PreviewAdapterSupport::isLakeEnum).orElse(false);
    }

    static boolean isLakeEnum(LhDatasourceTypeEnum e) {
        return e == LhDatasourceTypeEnum.ICEBERG || e == LhDatasourceTypeEnum.HIVE;
    }

    static boolean isNonRelationalKind(String kind) {
        String k = StrUtil.blankToDefault(kind, "").toLowerCase(Locale.ROOT);
        return "index".equals(k) || "topic".equals(k) || "queue".equals(k)
                || "bucket".equals(k) || "path".equals(k) || "api".equals(k)
                || "key".equals(k) || "collection".equals(k);
    }

    /** 无枚举命中时的非湖关键字（含 es 别名） */
    static boolean isExplicitNonLakeRaw(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        return raw.contains("elastic") || "es".equals(raw)
                || raw.contains("kafka") || raw.contains("redis")
                || raw.contains("mongo") || raw.contains("rabbit")
                || raw.contains("pulsar") || raw.contains("hbase")
                || raw.equals("s3") || raw.contains("minio") || raw.contains("hdfs")
                || raw.contains("ftp") || raw.equals("file") || raw.contains("http");
    }

    static String shortName(String objectName) {
        String raw = StrUtil.blankToDefault(objectName, "object");
        String[] parts = raw.split("[./]");
        return parts[parts.length - 1];
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    static Map<String, Object> emptyFail(Map<String, Object> base, String source, String message) {
        Map<String, Object> r = base == null ? new LinkedHashMap<>() : base;
        r.put("ok", false);
        r.put("source", source);
        r.put("message", message);
        r.put("columns", List.of());
        r.put("rows", List.of());
        r.put("rowCount", 0);
        return r;
    }

    static String quoteIdent(String ident) {
        if (StrUtil.isBlank(ident)) {
            return "\"\"";
        }
        String s = ident.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
