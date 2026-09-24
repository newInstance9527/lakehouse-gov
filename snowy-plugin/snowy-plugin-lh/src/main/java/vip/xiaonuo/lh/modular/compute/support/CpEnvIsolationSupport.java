package vip.xiaonuo.lh.modular.compute.support;

import cn.hutool.core.util.StrUtil;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 环境隔离：物理独立的 dev_ / stg_ Catalog 与 MinIO 桶（soft ensure）。
 */
@Slf4j
@Component
public class CpEnvIsolationSupport {

    private static final Pattern WRITE_INTO = Pattern.compile(
            "(?i)\\b(insert\\s+into|merge\\s+into|create\\s+table|drop\\s+table|alter\\s+table|truncate\\s+table)\\s+"
                    + "([a-zA-Z0-9_]+)\\.");

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public LhProperties.EnvIsolation cfg() {
        if (lhProperties.getCompute() == null || lhProperties.getCompute().getEnvIsolation() == null) {
            return new LhProperties.EnvIsolation();
        }
        return lhProperties.getCompute().getEnvIsolation();
    }

    public boolean enabled() {
        return cfg().isEnabled();
    }

    public String catalogFor(String env) {
        LhProperties.EnvIsolation e = cfg();
        String n = StrUtil.blankToDefault(env, "TEST").trim().toUpperCase(Locale.ROOT);
        if ("PROD".equals(n) || "PRODUCTION".equals(n)) {
            return prodCatalog();
        }
        if ("PRE".equals(n) || "STG".equals(n)) {
            return StrUtil.blankToDefault(e.getStgCatalog(), "stg_iceberg");
        }
        return StrUtil.blankToDefault(e.getDevCatalog(), "dev_iceberg");
    }

    public String bucketFor(String env) {
        LhProperties.EnvIsolation e = cfg();
        String n = StrUtil.blankToDefault(env, "TEST").trim().toUpperCase(Locale.ROOT);
        if ("PROD".equals(n) || "PRODUCTION".equals(n)) {
            return StrUtil.blankToDefault(e.getProdBucket(), "warehouse");
        }
        if ("PRE".equals(n) || "STG".equals(n)) {
            return StrUtil.blankToDefault(e.getStgBucket(), "lh-stg-warehouse");
        }
        return StrUtil.blankToDefault(e.getDevBucket(), "lh-dev-warehouse");
    }

    public String warehouseUri(String env) {
        return "s3a://" + bucketFor(env) + "/";
    }

    public String prodCatalog() {
        LhProperties.EnvIsolation e = cfg();
        if (StrUtil.isNotBlank(e.getProdCatalog())) {
            return e.getProdCatalog().trim();
        }
        if (lhProperties.getGravitino() != null && StrUtil.isNotBlank(lhProperties.getGravitino().getCatalog())) {
            return lhProperties.getGravitino().getCatalog().trim();
        }
        return "iceberg";
    }

    /**
     * 开发态静态检查附加项：写入目标须落在 env Catalog（或明确的 env_ 前缀）。
     */
    public List<Map<String, String>> lintWrites(String sql, String env) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        String expected = catalogFor(env);
        String prefix = expectedEnvPrefix(env);
        var m = WRITE_INTO.matcher(sql == null ? "" : sql);
        while (m.find()) {
            String catalog = m.group(2);
            if (catalog == null) {
                continue;
            }
            String c = catalog.toLowerCase(Locale.ROOT);
            if (c.equals(expected.toLowerCase(Locale.ROOT))
                    || c.startsWith(prefix)
                    || c.startsWith("dev_")
                    || c.startsWith("stg_")
                    || c.startsWith("test_")
                    || c.startsWith("pre_")) {
                continue;
            }
            if (c.startsWith("prod_") || c.equals(prodCatalog().toLowerCase(Locale.ROOT))) {
                out.add(item("禁止写入生产 Catalog " + catalog + "（请用 " + expected + "）", "error"));
            } else if (!c.startsWith("ods_") && !c.startsWith("dwd_") && !c.startsWith("dws_") && !c.startsWith("ads_")) {
                // 裸层名已由 forbidProdLayer 覆盖；其它 catalog 提示应用 env 名
                out.add(item("写入 Catalog " + catalog + " 非本环境 " + expected + "，请确认", "warn"));
            }
        }
        return out;
    }

    /**
     * soft：确保 MinIO 桶 + Grav Iceberg Catalog（warehouse 指向独立桶）。
     */
    public Map<String, Object> ensurePhysical() {
        Map<String, Object> r = new LinkedHashMap<>();
        LhProperties.EnvIsolation e = cfg();
        if (!e.isEnabled() || !e.isAutoEnsure()) {
            r.put("skipped", true);
            return r;
        }
        List<String> notes = new ArrayList<>();
        notes.add(ensureBucketSoft(e.getDevBucket()));
        notes.add(ensureBucketSoft(e.getStgBucket()));
        notes.add(ensureGravCatalogSoft(e.getDevCatalog(), e.getDevBucket(), "dev Iceberg warehouse"));
        notes.add(ensureGravCatalogSoft(e.getStgCatalog(), e.getStgBucket(), "stg Iceberg warehouse"));
        notes.removeIf(StrUtil::isBlank);
        r.put("notes", notes);
        r.put("devCatalog", e.getDevCatalog());
        r.put("stgCatalog", e.getStgCatalog());
        r.put("devBucket", e.getDevBucket());
        r.put("stgBucket", e.getStgBucket());
        return r;
    }

    private String ensureBucketSoft(String bucket) {
        String b = StrUtil.trim(bucket);
        if (StrUtil.isBlank(b)) {
            return null;
        }
        try {
            MinioClient client = buildMinio();
            if (client == null) {
                return "minio unavailable, skip bucket " + b;
            }
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(b).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(b).build());
                return "created bucket " + b;
            }
            return "bucket ok " + b;
        } catch (Exception ex) {
            log.warn("ensure bucket {} soft-fail: {}", b, ex.getMessage());
            return "bucket soft-fail " + b + ": " + ex.getMessage();
        }
    }

    private String ensureGravCatalogSoft(String catalogName, String bucket, String comment) {
        String name = StrUtil.trim(catalogName);
        if (StrUtil.isBlank(name)) {
            return null;
        }
        try {
            String metalake = lhProperties.getGravitino() == null ? "lakehouse"
                    : StrUtil.blankToDefault(lhProperties.getGravitino().getMetalake(), "lakehouse");
            Map<String, String> props = new LinkedHashMap<>();
            props.put("catalog-backend", "jdbc");
            props.put("warehouse", "s3a://" + bucket + "/");
            Map<String, String> minio = credentialResolver.minio();
            String endpoint = first(minio, "url", "endpoint");
            if (StrUtil.isBlank(endpoint) && lhProperties.getMinio() != null) {
                endpoint = lhProperties.getMinio().getUrl();
            }
            if (StrUtil.isNotBlank(endpoint)) {
                props.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
                props.put("s3.endpoint", endpoint);
                props.put("s3.path-style-access", "true");
                props.put("s3.region", lhProperties.getMinio() == null ? "us-east-1"
                        : StrUtil.blankToDefault(lhProperties.getMinio().getRegion(), "us-east-1"));
                String ak = first(minio, "accessKey", "access-key");
                String sk = first(minio, "secretKey", "secret-key");
                if (StrUtil.isNotBlank(ak)) {
                    props.put("s3.access-key-id", ak);
                }
                if (StrUtil.isNotBlank(sk)) {
                    props.put("s3.secret-access-key", sk);
                }
            }
            // JDBC backend 细节由运维对齐生产 Catalog；此处至少登记 warehouse 隔离
            Map<String, Object> up = gravitinoClient.upsertCatalog(
                    metalake, name, "relational", "lakehouse-iceberg", comment, props);
            return "grav catalog " + name + " created=" + up.get("created");
        } catch (Exception ex) {
            log.warn("ensure grav catalog {} soft-fail: {}", name, ex.getMessage());
            return "grav soft-fail " + name + ": " + ex.getMessage();
        }
    }

    private MinioClient buildMinio() {
        Map<String, String> m = credentialResolver.minio();
        String endpoint = first(m, "url", "endpoint");
        if (StrUtil.isBlank(endpoint) && lhProperties.getMinio() != null) {
            endpoint = lhProperties.getMinio().getUrl();
        }
        String ak = first(m, "accessKey", "access-key");
        String sk = first(m, "secretKey", "secret-key");
        if (StrUtil.isBlank(ak) && lhProperties.getMinio() != null) {
            ak = lhProperties.getMinio().getAccessKey();
        }
        if (StrUtil.isBlank(sk) && lhProperties.getMinio() != null) {
            sk = lhProperties.getMinio().getSecretKey();
        }
        if (StrUtil.isBlank(endpoint) || StrUtil.isBlank(ak) || StrUtil.isBlank(sk)) {
            return null;
        }
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }
        return MinioClient.builder().endpoint(endpoint).credentials(ak, sk).build();
    }

    private static String expectedEnvPrefix(String env) {
        String n = StrUtil.blankToDefault(env, "TEST").trim().toUpperCase(Locale.ROOT);
        if ("PRE".equals(n) || "STG".equals(n)) {
            return "stg_";
        }
        return "dev_";
    }

    private static String first(Map<String, String> m, String... keys) {
        if (m == null) {
            return "";
        }
        for (String k : keys) {
            String v = m.get(k);
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static Map<String, String> item(String label, String tone) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("tone", tone);
        return m;
    }
}
