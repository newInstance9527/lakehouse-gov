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
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MinIO / S3 清单发现：ListBuckets；若已填 bucket 则可选列举顶层前缀
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@Order(10)
public class LhMinioInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    @Override
    public boolean supports(String typeCode) {
        String t = StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT);
        return "s3".equals(t) || "minio".equals(t) || "s3_minio".equals(t);
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.BUCKET;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String endpoint = first(secret, "endpoint", "host", "url");
        String port = first(secret, "port");
        if (StrUtil.isBlank(endpoint) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            endpoint = ds.getEndpointHost();
        }
        if (StrUtil.isBlank(port) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            port = ds.getEndpointPort();
        }
        String ak = first(secret, "accessKey", "access-key", "accessKeyId", "username", "user");
        String sk = first(secret, "secretKey", "secret-key", "secretAccessKey", "password");
        // 台账登记时常写 REPLACE_ 占位：回退平台 MinIO 凭证（platform/minio/s3 或 yml bootstrap）
        if (isBlankOrPlaceholder(ak) || isBlankOrPlaceholder(sk)) {
            Map<String, String> platform = credentialResolver.minio();
            if (isBlankOrPlaceholder(ak)) {
                ak = StrUtil.blankToDefault(platform.get("accessKey"), "");
            }
            if (isBlankOrPlaceholder(sk)) {
                sk = StrUtil.blankToDefault(platform.get("secretKey"), "");
            }
            log.info("MinIO invent dsId={} using platform credential fallback akPresent={}",
                    ds.getId(), StrUtil.isNotBlank(ak));
        }
        if (StrUtil.isBlank(endpoint)) {
            throw new CommonException("S3/MinIO 清单同步需要 endpoint（Vault / 端点）");
        }
        if (isBlankOrPlaceholder(ak) || isBlankOrPlaceholder(sk)) {
            throw new CommonException(
                    "S3/MinIO 清单同步需要有效 accessKey/secretKey（Vault）。"
                            + "请编辑数据源补全，或配置 lh.minio.access-key/secret-key / platform/minio/s3");
        }

        boolean secure = useHttps(secret, endpoint);
        String ep = endpoint.trim();
        if (ep.startsWith("https://")) {
            secure = true;
            ep = ep.substring("https://".length());
        } else if (ep.startsWith("http://")) {
            secure = false;
            ep = ep.substring("http://".length());
        }
        ep = ep.replaceAll("/+$", "");
        // host:port 已含端口则不再拼
        if (StrUtil.isNotBlank(port) && !ep.matches(".*:\\d+$") && !ep.contains(":")) {
            ep = ep + ":" + port;
        }
        String region = first(secret, "region");
        if (StrUtil.isBlank(region)) {
            region = "us-east-1";
        }

        String configuredBucket = first(secret, "bucket", "path", "database");
        if (StrUtil.isBlank(configuredBucket) && StrUtil.isNotBlank(ds.getDatabaseName())) {
            configuredBucket = ds.getDatabaseName();
        }
        if (StrUtil.isNotBlank(configuredBucket) && configuredBucket.startsWith("s3a://")) {
            configuredBucket = configuredBucket.substring("s3a://".length()).replaceFirst("/.*$", "");
        }

        String type = StrUtil.blankToDefault(ds.getType(), "s3").toLowerCase(Locale.ROOT);
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint((secure ? "https://" : "http://") + ep)
                    .credentials(ak, sk)
                    .region(region)
                    .build();
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            List<Bucket> buckets = client.listBuckets();
            for (Bucket b : buckets) {
                if (b == null || StrUtil.isBlank(b.name())) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = b.name();
                m.engine = type;
                m.encoding = "UTF-8";
                m.comment = "bucket";
                items.add(m);
            }

            // 已填 bucket：补充顶层前缀（delimiter=/，非递归）
            if (StrUtil.isNotBlank(configuredBucket)) {
                try {
                    Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                            .bucket(configuredBucket)
                            .delimiter("/")
                            .recursive(false)
                            .maxKeys(200)
                            .build());
                    int prefixCount = 0;
                    for (Result<Item> r : results) {
                        Item item = r.get();
                        if (item == null) {
                            continue;
                        }
                        String name;
                        if (item.isDir()) {
                            name = configuredBucket + "/" + StrUtil.removeSuffix(item.objectName(), "/");
                        } else {
                            // 顶层对象不入库；仅目录前缀
                            continue;
                        }
                        if (StrUtil.isBlank(name) || items.stream().anyMatch(x -> name.equals(x.name))) {
                            continue;
                        }
                        LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                        m.name = name;
                        m.engine = type;
                        m.encoding = "UTF-8";
                        m.comment = "top-level prefix under " + configuredBucket;
                        items.add(m);
                        prefixCount++;
                        if (prefixCount >= 100) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("MinIO listObjects soft-fail bucket={}: {}", configuredBucket, e.getMessage());
                }
            }

            return LhInventoryDiscoverResult.remote("minio_s3", LhInventoryObjectKinds.BUCKET, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("S3/MinIO 清单同步失败: {}", e.getMessage());
        }
    }

    private static boolean useHttps(Map<String, Object> secret, String endpoint) {
        if (StrUtil.startWithIgnoreCase(endpoint, "https://")) {
            return true;
        }
        if (StrUtil.startWithIgnoreCase(endpoint, "http://")) {
            return false;
        }
        String secure = first(secret, "secure", "ssl", "https");
        if (StrUtil.isBlank(secure)) {
            // 默认端口 443 倾向 https；9000/9009 倾向 http
            String port = first(secret, "port");
            return "443".equals(port);
        }
        String s = secure.toLowerCase(Locale.ROOT);
        return s.contains("开启") || "true".equals(s) || "yes".equals(s) || "1".equals(s) || "https".equals(s);
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static boolean isBlankOrPlaceholder(String v) {
        if (StrUtil.isBlank(v)) {
            return true;
        }
        String s = v.trim();
        return "******".equals(s)
                || s.startsWith("REPLACE_")
                || "changeme".equalsIgnoreCase(s)
                || "todo".equalsIgnoreCase(s);
    }
}
