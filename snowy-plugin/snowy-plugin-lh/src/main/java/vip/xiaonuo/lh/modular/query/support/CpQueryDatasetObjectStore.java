package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 即席数据集抽样 → MinIO/S3。失败时由调用方降级写门户 sample_json。
 */
@Slf4j
@Component
public class CpQueryDatasetObjectStore {

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

        public PutResult(String bucket, String objectKey, String uri, String sha256) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.uri = uri;
            this.sha256 = sha256;
        }
    }

    public String configuredBucket() {
        LhProperties.Minio m = lhProperties.getMinio();
        return StrUtil.blankToDefault(m.getBucket(), CpQueryDatasetObjectKeys.DEFAULT_BUCKET);
    }

    public String configuredPrefix() {
        LhProperties.Minio m = lhProperties.getMinio();
        return StrUtil.blankToDefault(m.getDatasetPrefix(), CpQueryDatasetObjectKeys.DEFAULT_PREFIX);
    }

    /**
     * 写入抽样 JSON。凭证或 endpoint 缺失、或 MinIO 不可达时抛异常，由上层决定是否降级。
     */
    public PutResult putSampleJson(String ws, String dsCode, String json) throws Exception {
        byte[] bytes = StrUtil.blankToDefault(json, "[]").getBytes(StandardCharsets.UTF_8);
        String sha = DigestUtil.sha256Hex(bytes);
        String bucket = configuredBucket();
        String key = CpQueryDatasetObjectKeys.objectKey(configuredPrefix(), ws, dsCode);
        MinioClient client = buildClient();
        ensureBucket(client, bucket);
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, bytes.length, -1)
                    .contentType("application/json; charset=utf-8")
                    .build());
        }
        return new PutResult(bucket, key, CpQueryDatasetObjectKeys.uri(bucket, key), sha);
    }

    /** 读取抽样 JSON 正文；对象不存在或失败抛异常。 */
    public String getSampleJson(String bucket, String objectKey) throws Exception {
        if (StrUtil.isBlank(bucket) || StrUtil.isBlank(objectKey)) {
            throw new IllegalArgumentException("bucket/objectKey 不能为空");
        }
        MinioClient client = buildClient();
        try (var stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
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

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("created MinIO bucket for query dataset: {}", bucket);
        }
    }
}
