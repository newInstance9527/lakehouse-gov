package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectRetentionArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetObjectRetentionArgs;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.io.ByteArrayInputStream;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 合规证据包 → MinIO/S3；优先 Object Lock COMPLIANCE 保留期（WORM）。
 * <p>桶无 Object Lock 或保留期不可设时 soft-fail：仍落盘，返回 {@code wormApplied=false}。</p>
 */
@Slf4j
@Component
public class GovDelEvidenceObjectStore {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    @Getter
    public static class PutResult {
        private final String bucket;
        private final String objectKey;
        private final String uri;
        private final String sha256;
        private final boolean wormApplied;
        private final String wormMode;
        private final String retainUntil;
        private final String wormNote;

        public PutResult(String bucket, String objectKey, String uri, String sha256,
                         boolean wormApplied, String wormMode, String retainUntil, String wormNote) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.uri = uri;
            this.sha256 = sha256;
            this.wormApplied = wormApplied;
            this.wormMode = wormMode;
            this.retainUntil = retainUntil;
            this.wormNote = wormNote;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", bucket);
            m.put("objectKey", objectKey);
            m.put("objectPath", uri);
            m.put("sha256", sha256);
            m.put("wormApplied", wormApplied);
            m.put("wormMode", wormMode);
            m.put("retainUntil", retainUntil);
            m.put("wormNote", wormNote);
            return m;
        }
    }

    public String configuredBucket() {
        LhProperties.Minio m = lhProperties.getMinio();
        return StrUtil.blankToDefault(m.getEvidenceBucket(), GovDelEvidenceObjectKeys.DEFAULT_BUCKET);
    }

    public String configuredPrefix() {
        LhProperties.Minio m = lhProperties.getMinio();
        return StrUtil.blankToDefault(m.getEvidencePrefix(), GovDelEvidenceObjectKeys.DEFAULT_PREFIX);
    }

    public int configuredWormDays() {
        LhProperties.Minio m = lhProperties.getMinio();
        int days = m.getEvidenceWormDays() == null ? 2555 : m.getEvidenceWormDays();
        return Math.max(1, days);
    }

    public RetentionMode configuredWormMode() {
        LhProperties.Minio m = lhProperties.getMinio();
        String raw = StrUtil.blankToDefault(m.getEvidenceWormMode(), "COMPLIANCE").trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(raw) || "OFF".equals(raw)) {
            return null;
        }
        if ("GOVERNANCE".equals(raw)) {
            return RetentionMode.GOVERNANCE;
        }
        return RetentionMode.COMPLIANCE;
    }

    /**
     * 写入证据 ZIP。同 sha 已存在则跳过上传，仍回读 / 尝试补设保留期。
     */
    public PutResult putZip(String reqNo, byte[] zipBytes) throws Exception {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("证据包内容为空");
        }
        String sha = DigestUtil.sha256Hex(zipBytes);
        String bucket = configuredBucket();
        String key = GovDelEvidenceObjectKeys.objectKey(configuredPrefix(), reqNo, sha);
        String uri = GovDelEvidenceObjectKeys.uri(bucket, key);
        MinioClient client = buildClient();
        ensureBucket(client, bucket);

        boolean exists = objectExists(client, bucket, key);
        if (!exists) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
                PutObjectArgs.Builder put = PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(in, zipBytes.length, -1)
                        .contentType(GovDelEvidenceObjectKeys.CONTENT_TYPE_ZIP);
                RetentionMode mode = configuredWormMode();
                ZonedDateTime until = null;
                if (mode != null) {
                    until = ZonedDateTime.now().plusDays(configuredWormDays());
                    put.retention(new Retention(mode, until));
                }
                try {
                    client.putObject(put.build());
                    if (mode != null) {
                        return new PutResult(bucket, key, uri, sha, true, mode.name(), until.toString(),
                                "Object Lock retention 已随 put 生效");
                    }
                    return new PutResult(bucket, key, uri, sha, false, "NONE", null,
                            "配置 evidence-worm-mode=NONE，仅落盘");
                } catch (Exception putEx) {
                    // 桶无 Object Lock 时带 retention 的 put 会失败 → 无锁重试
                    if (mode == null) {
                        throw putEx;
                    }
                    log.warn("evidence put with retention failed, retry without lock: {} {}", key, putEx.getMessage());
                    try (ByteArrayInputStream in2 = new ByteArrayInputStream(zipBytes)) {
                        client.putObject(PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(key)
                                .stream(in2, zipBytes.length, -1)
                                .contentType(GovDelEvidenceObjectKeys.CONTENT_TYPE_ZIP)
                                .build());
                    }
                    return applyRetentionSoft(client, bucket, key, uri, sha, mode);
                }
            }
        }
        RetentionMode mode = configuredWormMode();
        if (mode == null) {
            return new PutResult(bucket, key, uri, sha, false, "NONE", null, "对象已存在；WORM 关闭");
        }
        return applyRetentionSoft(client, bucket, key, uri, sha, mode);
    }

    public byte[] getZip(String bucket, String objectKey) throws Exception {
        if (StrUtil.isBlank(bucket) || StrUtil.isBlank(objectKey)) {
            throw new IllegalArgumentException("bucket/objectKey 不能为空");
        }
        MinioClient client = buildClient();
        try (var stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        }
    }

    private PutResult applyRetentionSoft(MinioClient client, String bucket, String key, String uri,
                                         String sha, RetentionMode mode) {
        ZonedDateTime until = ZonedDateTime.now().plusDays(configuredWormDays());
        try {
            client.setObjectRetention(SetObjectRetentionArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .config(new Retention(mode, until))
                    .build());
            return new PutResult(bucket, key, uri, sha, true, mode.name(), until.toString(),
                    "Object Lock retention 已设置");
        } catch (Exception e) {
            String existing = null;
            try {
                Retention r = client.getObjectRetention(GetObjectRetentionArgs.builder()
                        .bucket(bucket).object(key).build());
                if (r != null && r.retainUntilDate() != null) {
                    existing = r.retainUntilDate().toString();
                    return new PutResult(bucket, key, uri, sha, true,
                            r.mode() != null ? r.mode().name() : mode.name(),
                            existing, "已有保留期");
                }
            } catch (Exception ignored) {
                // ignore
            }
            log.warn("evidence WORM soft-fail (bucket may lack Object Lock): {} {}", key, e.getMessage());
            return new PutResult(bucket, key, uri, sha, false, mode.name(), null,
                    "落盘成功但未设保留期：" + StrUtil.maxLength(e.getMessage(), 200)
                            + "（需 Object Lock 桶；见 lh.minio.evidence-bucket）");
        }
    }

    private boolean objectExists(MinioClient client, String bucket, String key) {
        try {
            client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (exists) {
            return;
        }
        try {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).objectLock(true).build());
            log.info("created MinIO Object Lock bucket for evidence: {}", bucket);
        } catch (Exception e) {
            log.warn("create Object Lock bucket failed, fallback plain bucket {}: {}", bucket, e.getMessage());
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    MinioClient buildClient() {
        LhProperties.Minio cfg = lhProperties.getMinio();
        String endpoint = StrUtil.trim(cfg.getUrl());
        if (StrUtil.isBlank(endpoint)) {
            throw new IllegalStateException("lh.minio.url 未配置");
        }
        Map<String, String> cred = credentialResolver.minio();
        String ak = StrUtil.trim(cred.get("accessKey"));
        String sk = StrUtil.trim(cred.get("secretKey"));
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk)) {
            throw new IllegalStateException("MinIO accessKey/secretKey 未配置（Vault 或 lh.minio.*）");
        }
        boolean secure = endpoint.startsWith("https://");
        String ep = endpoint;
        if (ep.startsWith("https://")) {
            ep = ep.substring("https://".length());
        } else if (ep.startsWith("http://")) {
            ep = ep.substring("http://".length());
            secure = false;
        }
        ep = ep.replaceAll("/+$", "");
        String region = StrUtil.blankToDefault(cfg.getRegion(), "us-east-1");
        return MinioClient.builder()
                .endpoint((secure ? "https://" : "http://") + ep)
                .credentials(ak, sk)
                .region(region)
                .build();
    }
}
