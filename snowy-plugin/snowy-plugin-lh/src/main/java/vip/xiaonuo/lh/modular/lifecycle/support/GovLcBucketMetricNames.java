package vip.xiaonuo.lh.modular.lifecycle.support;

/**
 * 桶级指标命名契约（Categraf → VictoriaMetrics → {@code storage/buckets|trend}）。
 * <p>
 * 平台名优先；现网若仅有 MinIO 原生名则回退（不重复采集）。
 */
public final class GovLcBucketMetricNames {

    /** 平台：桶已用字节 */
    public static final String USED_BYTES = "lh_bucket_storage_used_bytes";
    /** 平台：桶对象数 */
    public static final String OBJECT_COUNT = "lh_bucket_storage_object_count";
    /** 平台：桶容量（字节）；常由配置/配额投影，MinIO 未必自带 */
    public static final String CAPACITY_BYTES = "lh_bucket_storage_capacity_bytes";

    /** MinIO metrics v2：桶用量 */
    public static final String MINIO_USED_V2 = "minio_bucket_usage_total_bytes";
    /** MinIO metrics v2：桶对象数 */
    public static final String MINIO_OBJECTS_V2 = "minio_bucket_usage_object_total";
    /** MinIO metrics v2：桶配额（有则作 capacity） */
    public static final String MINIO_QUOTA_V2 = "minio_bucket_quota_total_bytes";

    /** MinIO metrics v3：桶用量 */
    public static final String MINIO_USED_V3 = "minio_cluster_usage_buckets_total_bytes";
    /** MinIO metrics v3：桶对象数 */
    public static final String MINIO_OBJECTS_V3 = "minio_cluster_usage_buckets_objects_count";
    /** MinIO metrics v3：桶配额 */
    public static final String MINIO_QUOTA_V3 = "minio_cluster_usage_buckets_quota_total_bytes";

    public static final String LABEL_BUCKET = "bucket";

    private GovLcBucketMetricNames() {
    }

    /** PromQL：优先平台名，否则 MinIO v2/v3。 */
    public static String usedBytesQuery() {
        return USED_BYTES + " or " + MINIO_USED_V2 + " or " + MINIO_USED_V3;
    }

    public static String objectCountQuery() {
        return OBJECT_COUNT + " or " + MINIO_OBJECTS_V2 + " or " + MINIO_OBJECTS_V3;
    }

    public static String capacityBytesQuery() {
        return CAPACITY_BYTES + " or " + MINIO_QUOTA_V2 + " or " + MINIO_QUOTA_V3;
    }
}
