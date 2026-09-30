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
package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 门户清单对象语义（共用既有 LhDsTable / ig_ds_table.table_name 存对象名；kind 仅 API/文案层）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public final class LhInventoryObjectKinds {

    public static final String TABLE = "table";
    public static final String TOPIC = "topic";
    public static final String INDEX = "index";
    public static final String BUCKET = "bucket";
    public static final String QUEUE = "queue";
    public static final String KEY_PREFIX = "key_prefix";
    /** Redis SCAN 样本等具体 Key（非前缀登记） */
    public static final String KEY = "key";
    public static final String PATH = "path";
    public static final String COLLECTION = "collection";
    public static final String FILESET = "fileset";
    public static final String ENDPOINT = "endpoint";
    public static final String UNKNOWN = "object";

    private LhInventoryObjectKinds() {
    }

    public static String ofType(String typeCode) {
        String t = StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT);
        return switch (t) {
            case "kafka", "pulsar" -> TOPIC;
            case "rabbitmq" -> QUEUE;
            case "elasticsearch" -> INDEX;
            case "redis" -> KEY_PREFIX;
            case "s3", "minio", "s3_minio" -> BUCKET;
            case "hdfs", "file", "ftp" -> PATH;
            case "mongodb" -> COLLECTION;
            case "http_api" -> ENDPOINT;
            case "hive", "iceberg", "hbase", "mysql", "pg", "postgresql", "oracle", "sqlserver",
                    "clickhouse", "doris", "trino" -> TABLE;
            default -> UNKNOWN;
        };
    }

    /** English short label (OM sync / API; avoid Chinese in OpenMetadata) */
    public static String labelOf(String kind) {
        return switch (StrUtil.blankToDefault(kind, UNKNOWN)) {
            case TOPIC -> "Topic";
            case QUEUE -> "Queue";
            case INDEX -> "Index";
            case BUCKET -> "Bucket";
            case KEY_PREFIX -> "KeyPrefix";
            case KEY -> "Key";
            case PATH -> "Path";
            case COLLECTION -> "Collection";
            case FILESET -> "Fileset";
            case ENDPOINT -> "Endpoint";
            case TABLE -> "Table";
            default -> "Object";
        };
    }
}
