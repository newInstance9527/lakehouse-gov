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
package vip.xiaonuo.lh.modular.datasource.enums;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Arrays;
import java.util.Optional;

/**
 * 数据源类型枚举（对齐 OpenMetadata 连接器 + 门户注册表单 DS_TYPE_FIELDS）
 * <p>一期作业路径可用类型见 {@link #isIngestable()}；分析路径仅 Trino 作 query_gateway。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
public enum LhDatasourceTypeEnum {

    MYSQL("mysql", "MySQL", LhDatasourceCategoryEnum.RDB),
    /** 兼容历史编码 pg */
    PG("pg", "PostgreSQL", LhDatasourceCategoryEnum.RDB),
    POSTGRESQL("postgresql", "PostgreSQL", LhDatasourceCategoryEnum.RDB),
    ORACLE("oracle", "Oracle", LhDatasourceCategoryEnum.RDB),
    SQLSERVER("sqlserver", "SQL Server", LhDatasourceCategoryEnum.RDB),
    CLICKHOUSE("clickhouse", "ClickHouse", LhDatasourceCategoryEnum.DW),
    DORIS("doris", "Doris", LhDatasourceCategoryEnum.DW),
    STARROCKS("starrocks", "StarRocks", LhDatasourceCategoryEnum.DW),
    HIVE("hive", "Hive", LhDatasourceCategoryEnum.DW),
    ICEBERG("iceberg", "Iceberg", LhDatasourceCategoryEnum.DW),
    HBASE("hbase", "HBase", LhDatasourceCategoryEnum.DW),
    TRINO("trino", "Trino", LhDatasourceCategoryEnum.DW),
    KAFKA("kafka", "Kafka", LhDatasourceCategoryEnum.MQ),
    RABBITMQ("rabbitmq", "RabbitMQ", LhDatasourceCategoryEnum.MQ),
    PULSAR("pulsar", "Pulsar", LhDatasourceCategoryEnum.MQ),
    MONGODB("mongodb", "MongoDB", LhDatasourceCategoryEnum.NOSQL),
    REDIS("redis", "Redis", LhDatasourceCategoryEnum.NOSQL),
    ELASTICSEARCH("elasticsearch", "Elasticsearch", LhDatasourceCategoryEnum.SEARCH),
    HDFS("hdfs", "HDFS", LhDatasourceCategoryEnum.STORAGE),
    S3("s3", "S3/MinIO", LhDatasourceCategoryEnum.STORAGE),
    FILE("file", "FTP/SFTP", LhDatasourceCategoryEnum.STORAGE),
    FTP("ftp", "FTP/SFTP", LhDatasourceCategoryEnum.STORAGE),
    HTTP_API("http_api", "HTTP API", LhDatasourceCategoryEnum.API),
    TABLEAU("tableau", "Tableau", LhDatasourceCategoryEnum.DASHBOARD),
    SUPERSET("superset", "Superset", LhDatasourceCategoryEnum.DASHBOARD),
    AIRFLOW("airflow", "Airflow", LhDatasourceCategoryEnum.PIPELINE);

    private final String value;
    private final String label;
    private final LhDatasourceCategoryEnum category;

    LhDatasourceTypeEnum(String value, String label, LhDatasourceCategoryEnum category) {
        this.value = value;
        this.label = label;
        this.category = category;
    }

    /**
     * 按编码解析
     *
     * @param type 类型编码
     * @return 枚举可选
     */
    public static Optional<LhDatasourceTypeEnum> of(String type) {
        if (StrUtil.isBlank(type)) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(e -> e.value.equalsIgnoreCase(type)).findFirst();
    }

    /**
     * 校验类型是否支持
     *
     * @param type 类型编码
     */
    public static void validate(String type) {
        if (of(type).isEmpty()) {
            throw new CommonException("不支持的数据源类型: {}", type);
        }
    }

    /**
     * 解析分类编码（未知类型默认 rdb）
     *
     * @param type 类型编码
     * @return 分类值
     */
    public static String resolveCategory(String type) {
        return of(type).map(e -> e.category.getValue()).orElse(LhDatasourceCategoryEnum.RDB.getValue());
    }

    /**
     * 是否可作为 ETL/入湖作业源（DAG 选源白名单）
     *
     * @return true=可入湖
     */
    public boolean isIngestable() {
        return this != TRINO && this != TABLEAU && this != SUPERSET && this != AIRFLOW;
    }

    /**
     * 是否 JDBC 关系型（可做真实连通性/Schema 探测）
     *
     * @return true=JDBC
     */
    public boolean isJdbc() {
        return this == MYSQL || this == PG || this == POSTGRESQL || this == ORACLE || this == SQLSERVER
                || this == CLICKHOUSE || this == DORIS || this == STARROCKS || this == TRINO;
    }
}
