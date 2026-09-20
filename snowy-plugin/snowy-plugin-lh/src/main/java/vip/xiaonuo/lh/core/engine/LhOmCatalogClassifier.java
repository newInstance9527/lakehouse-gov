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
package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceCategoryEnum;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;

/**
 * 门户数据源 → OpenMetadata 官方服务族挂载点
 * <p>优先使用 OM 官方枚举：Database / Messaging / Search / Storage / Pipeline / Dashboard / API。
 * 无官方类型时才回退 Custom*（如 Redis→CustomDatabase）。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public final class LhOmCatalogClassifier {

    public static final String FAMILY_DATABASE = "DATABASE";
    public static final String FAMILY_MESSAGING = "MESSAGING";
    public static final String FAMILY_SEARCH = "SEARCH";
    public static final String FAMILY_STORAGE = "STORAGE";
    public static final String FAMILY_PIPELINE = "PIPELINE";
    public static final String FAMILY_DASHBOARD = "DASHBOARD";
    public static final String FAMILY_API = "API";

    private LhOmCatalogClassifier() {
    }

    /**
     * 解析 OM 挂载分类
     *
     * @param ds      门户数据源
     * @param catalog Grav catalog / 门户 dsCode 清洗名
     * @return 分类规格
     */
    public static Spec resolve(LhDatasource ds, String catalog) {
        String typeCode = ds == null ? "" : StrUtil.blankToDefault(ds.getType(), "");
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(typeCode).orElse(null);
        String category = ds != null && StrUtil.isNotBlank(ds.getCategory())
                ? ds.getCategory()
                : (typeEnum != null ? typeEnum.getCategory().getValue() : LhDatasourceCategoryEnum.RDB.getValue());
        String typeKey = sanitize(StrUtil.blankToDefault(typeCode, "custom"));
        if ("pg".equals(typeKey)) {
            typeKey = "postgresql";
        }
        if ("es".equals(typeKey)) {
            typeKey = "elasticsearch";
        }
        if ("minio".equals(typeKey)) {
            typeKey = "s3";
        }

        OmType om = mapOmType(typeKey);
        String cat = StrUtil.blankToDefault(catalog, "ds_unknown");

        Spec s = new Spec();
        s.omFamily = om.family;
        s.serviceType = om.serviceType;
        s.serviceDisplayName = om.serviceType;
        s.typeCode = typeKey;
        s.category = category;
        s.databaseName = cat;
        s.databaseDisplayName = asciiDisplayName(ds != null ? ds.getName() : null, cat);
        s.schemaName = StrUtil.blankToDefault(ds == null ? null : ds.getDatabaseName(), "default");
        s.serviceDescription = buildServiceDesc(ds, typeCode, om);
        s.databaseDescription = buildDbDesc(ds, typeCode, om.serviceType);

        // Database：按类型共用 service（lh_mysql），库名=数据源
        // Messaging/Search/Storage/…：每个数据源一个 service（避免 Topic/Index 跨集群撞名）
        if (FAMILY_DATABASE.equals(om.family)) {
            s.serviceName = "lh_" + typeKey;
        } else {
            s.serviceName = sanitize("lh_" + typeKey + "_" + cat);
            // 非库表族：displayName 用数据源名更直观
            s.serviceDisplayName = asciiDisplayName(ds != null ? ds.getName() : null, om.serviceType + ":" + cat);
        }
        return s;
    }

    private static OmType mapOmType(String typeKey) {
        return switch (typeKey) {
            // —— DatabaseServiceType ——
            case "mysql" -> OmType.db("Mysql");
            case "postgresql", "pg" -> OmType.db("Postgres");
            case "oracle" -> OmType.db("Oracle");
            case "sqlserver" -> OmType.db("Mssql");
            case "clickhouse" -> OmType.db("Clickhouse");
            case "doris" -> OmType.db("Doris");
            case "trino" -> OmType.db("Trino");
            case "hive" -> OmType.db("Hive");
            case "iceberg" -> OmType.db("Iceberg");
            case "mongodb" -> OmType.db("MongoDB");
            // —— MessagingServiceType ——
            case "kafka" -> OmType.of(FAMILY_MESSAGING, "Kafka");
            case "pulsar", "rabbitmq" -> OmType.of(FAMILY_MESSAGING, "CustomMessaging");
            // —— SearchServiceType ——
            case "elasticsearch", "es" -> OmType.of(FAMILY_SEARCH, "ElasticSearch");
            // —— StorageServiceType ——
            case "s3", "minio" -> OmType.of(FAMILY_STORAGE, "S3");
            case "hdfs", "file", "ftp" -> OmType.of(FAMILY_STORAGE, "CustomStorage");
            // —— PipelineServiceType ——
            case "airflow" -> OmType.of(FAMILY_PIPELINE, "Airflow");
            // —— DashboardServiceType ——
            case "tableau" -> OmType.of(FAMILY_DASHBOARD, "Tableau");
            case "superset" -> OmType.of(FAMILY_DASHBOARD, "Superset");
            // —— APIServiceType ——
            case "http_api" -> OmType.of(FAMILY_API, "Rest");
            // —— 无官方族：回退 CustomDatabase ——
            default -> OmType.db("CustomDatabase");
        };
    }

    private static String buildServiceDesc(LhDatasource ds, String typeCode, OmType om) {
        StringBuilder sb = new StringBuilder();
        sb.append("portalType=").append(StrUtil.blankToDefault(typeCode, "custom"));
        sb.append("; omFamily=").append(om.family);
        sb.append("; omServiceType=").append(om.serviceType);
        if (ds != null && StrUtil.isNotBlank(ds.getDsCode())) {
            sb.append("; dsCode=").append(ds.getDsCode());
        }
        return sb.toString();
    }

    private static String buildDbDesc(LhDatasource ds, String typeCode, String omServiceType) {
        StringBuilder sb = new StringBuilder();
        sb.append("portalType=").append(StrUtil.blankToDefault(typeCode, "custom"));
        sb.append("; omServiceType=").append(omServiceType);
        if (ds != null) {
            if (StrUtil.isNotBlank(ds.getId())) {
                sb.append("; dsId=").append(ds.getId());
            }
            if (StrUtil.isNotBlank(ds.getDsCode())) {
                sb.append("; dsCode=").append(ds.getDsCode());
            }
            if (StrUtil.isNotBlank(ds.getEndpointHost())) {
                sb.append("; endpoint=").append(ds.getEndpointHost());
                if (StrUtil.isNotBlank(ds.getEndpointPort())) {
                    sb.append(":").append(ds.getEndpointPort());
                }
            }
        }
        return sb.toString();
    }

    /** OM displayName：含非 ASCII（如中文）时回退到 catalog/dsCode */
    private static String asciiDisplayName(String preferred, String fallback) {
        if (StrUtil.isBlank(preferred)) {
            return fallback;
        }
        boolean nonAscii = preferred.chars().anyMatch(c -> c > 127);
        return nonAscii ? fallback : preferred.trim();
    }

    private static String sanitize(String s) {
        String x = StrUtil.blankToDefault(s, "custom").toLowerCase();
        return x.replaceAll("[^a-z0-9_]", "_");
    }

    private static final class OmType {
        final String family;
        final String serviceType;

        private OmType(String family, String serviceType) {
            this.family = family;
            this.serviceType = serviceType;
        }

        static OmType db(String serviceType) {
            return new OmType(FAMILY_DATABASE, serviceType);
        }

        static OmType of(String family, String serviceType) {
            return new OmType(family, serviceType);
        }
    }

    /** OM 挂载规格 */
    public static final class Spec {
        /** DATABASE / MESSAGING / SEARCH / STORAGE / PIPELINE / DASHBOARD / API */
        public String omFamily;
        public String serviceName;
        public String serviceType;
        public String serviceDisplayName;
        public String serviceDescription;
        public String databaseName;
        public String databaseDisplayName;
        public String databaseDescription;
        public String schemaName;
        public String category;
        public String typeCode;
    }
}
