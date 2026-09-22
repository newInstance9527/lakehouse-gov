package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非结构化内容级擦除：按主体前缀 List + RemoveObject（含历史版本不强制；最小闭环删当前对象）。
 */
@Slf4j
@Component
public class GovDelObjectPurgeExecutor {

    private static final int MAX_DELETE = 500;

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public record PurgeResult(boolean ok, int listed, int deleted, String message, String engineRef) {
        public static PurgeResult fail(String msg) {
            return new PurgeResult(false, 0, 0, msg, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("listed", listed);
            m.put("deleted", deleted);
            m.put("message", message);
            m.put("engineRef", engineRef);
            return m;
        }
    }

    public PurgeResult purge(String objectFqn, String subjectIdHash) {
        GovDelObjectPurgeSupport.ObjectTarget target;
        try {
            target = GovDelObjectPurgeSupport.resolve(objectFqn, subjectIdHash);
        } catch (IllegalArgumentException e) {
            return PurgeResult.fail(e.getMessage());
        }
        try {
            MinioClient client = buildClient();
            List<String> keys = new ArrayList<>();
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(target.bucket())
                    .prefix(target.prefix())
                    .recursive(true)
                    .maxKeys(MAX_DELETE)
                    .build());
            for (Result<Item> r : results) {
                Item item = r.get();
                if (item == null || item.isDir() || StrUtil.isBlank(item.objectName())) {
                    continue;
                }
                keys.add(item.objectName());
                if (keys.size() >= MAX_DELETE) {
                    break;
                }
            }
            int deleted = 0;
            for (String key : keys) {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(target.bucket())
                        .object(key)
                        .build());
                deleted++;
            }
            String ref = "s3://" + target.bucket() + "/" + target.prefix();
            return new PurgeResult(true, keys.size(), deleted,
                    "已删对象 " + deleted + "/" + keys.size() + " @ " + ref, ref);
        } catch (Exception e) {
            log.warn("object purge soft-fail fqn={}: {}", objectFqn, e.getMessage());
            return PurgeResult.fail(StrUtil.maxLength(
                    StrUtil.blankToDefault(e.getMessage(), "对象擦除失败"), 400));
        }
    }

    /** 验证残留：列出前缀下对象数；引擎不可达时返回 -1（soft-fail）。 */
    public long countRemaining(String objectFqn, String subjectIdHash) {
        GovDelObjectPurgeSupport.ObjectTarget target;
        try {
            target = GovDelObjectPurgeSupport.resolve(objectFqn, subjectIdHash);
        } catch (IllegalArgumentException e) {
            return -1L;
        }
        try {
            MinioClient client = buildClient();
            long n = 0;
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(target.bucket())
                    .prefix(target.prefix())
                    .recursive(true)
                    .maxKeys(MAX_DELETE)
                    .build());
            for (Result<Item> r : results) {
                Item item = r.get();
                if (item != null && !item.isDir() && StrUtil.isNotBlank(item.objectName())) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            log.warn("object count soft-fail: {}", e.getMessage());
            return -1L;
        }
    }

    private MinioClient buildClient() {
        LhProperties.Minio m = lhProperties.getMinio();
        if (m == null || StrUtil.isBlank(m.getUrl())) {
            throw new IllegalStateException("lh.minio.url 未配置");
        }
        Map<String, String> cred = credentialResolver.minio();
        String ak = cred != null ? cred.get("accessKey") : null;
        String sk = cred != null ? cred.get("secretKey") : null;
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk)) {
            throw new IllegalStateException("MinIO accessKey/secretKey 未配置（Vault 或 lh.minio.*）");
        }
        return MinioClient.builder()
                .endpoint(m.getUrl())
                .credentials(ak, sk)
                .build();
    }
}
