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
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S3 / MinIO：列举 bucket（或前缀）下对象样例（非 Grav）。
 */
@Slf4j
@Component
@Order(35)
public class MinioS3PreviewAdapter implements GovAssetPreviewAdapter {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    @Override
    public int order() {
        return 35;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        String t = PreviewAdapterSupport.dsType(ctx.getPrimaryDs());
        // 编码可能是 s3 / "S3 / MinIO" / minio
        if (t.contains("s3") || t.contains("minio")) {
            return true;
        }
        // 误标 table 但源引擎是对象存储时仍命中
        if (ctx.getAsset() != null) {
            String eng = StrUtil.blankToDefault(ctx.getAsset().getEngine(), "").toLowerCase();
            if (eng.contains("s3") || eng.contains("minio")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String objectName = StrUtil.blankToDefault(ctx.getObjectName(), "").trim();
        if (StrUtil.isBlank(objectName) && ds != null) {
            objectName = StrUtil.blankToDefault(ds.getDatabaseName(), "").trim();
        }
        if (StrUtil.isBlank(objectName)) {
            Map<String, Object> r = ctx.newResult();
            return PreviewAdapterSupport.emptyFail(r, "s3",
                    "S3/MinIO 无 bucket/objectName，无法预览（请登记 bucket 或在数据源填写 database/bucket）");
        }
        // objectName 可能是 bucket 或 bucket/prefix
        String bucket;
        String prefix = "";
        int slash = objectName.indexOf('/');
        if (slash > 0) {
            bucket = objectName.substring(0, slash);
            prefix = objectName.substring(slash + 1);
        } else {
            bucket = objectName;
        }
        int limit = Math.min(Math.max(ctx.getLimit(), 1), 50);
        Map<String, Object> r = ctx.newResult();
        r.put("qualifiedName", objectName);
        try {
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
            if (isBlankOrPlaceholder(ak) || isBlankOrPlaceholder(sk)) {
                Map<String, String> platform = credentialResolver.minio();
                if (isBlankOrPlaceholder(ak)) {
                    ak = StrUtil.blankToDefault(platform.get("accessKey"), "");
                }
                if (isBlankOrPlaceholder(sk)) {
                    sk = StrUtil.blankToDefault(platform.get("secretKey"), "");
                }
            }
            if (StrUtil.isBlank(endpoint)) {
                return PreviewAdapterSupport.emptyFail(r, "s3", "S3/MinIO 无 endpoint，无法预览");
            }
            if (isBlankOrPlaceholder(ak) || isBlankOrPlaceholder(sk)) {
                return PreviewAdapterSupport.emptyFail(r, "s3", "S3/MinIO 无有效 accessKey/secretKey");
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
            if (StrUtil.isNotBlank(port) && !ep.matches(".*:\\d+$") && !ep.contains(":")) {
                ep = ep + ":" + port;
            }
            String region = first(secret, "region");
            if (StrUtil.isBlank(region)) {
                region = "us-east-1";
            }

            MinioClient client = MinioClient.builder()
                    .endpoint((secure ? "https://" : "http://") + ep)
                    .credentials(ak, sk)
                    .region(region)
                    .build();

            ListObjectsArgs.Builder args = ListObjectsArgs.builder()
                    .bucket(bucket)
                    .recursive(true)
                    .maxKeys(limit);
            if (StrUtil.isNotBlank(prefix)) {
                args.prefix(prefix);
            }

            List<String> columns = List.of("key", "size", "lastModified", "etag");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Result<Item> result : client.listObjects(args.build())) {
                Item item = result.get();
                if (item == null || item.isDir()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", item.objectName());
                row.put("size", item.size());
                row.put("lastModified", item.lastModified() == null ? null : String.valueOf(item.lastModified()));
                row.put("etag", item.etag());
                rows.add(row);
                if (rows.size() >= limit) {
                    break;
                }
            }
            r.put("ok", true);
            r.put("source", "s3");
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("message", rows.isEmpty()
                    ? "已连接 bucket，暂无对象样例"
                    : "S3/MinIO 对象列表样例（探查，非 Grav/Trino）");
            r.put("hint", "内容预览（读对象字节）可后续增强；当前列对象元数据");
            return r;
        } catch (Exception e) {
            log.warn("S3 preview fail bucket={}: {}", bucket, e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "s3",
                    "S3/MinIO 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }

    private static boolean useHttps(Map<String, Object> secret, String host) {
        if (StrUtil.startWithIgnoreCase(host, "https://")) {
            return true;
        }
        if (StrUtil.startWithIgnoreCase(host, "http://")) {
            return false;
        }
        String ssl = first(secret, "ssl", "secure", "https");
        if (StrUtil.isBlank(ssl)) {
            return false;
        }
        String s = ssl.toLowerCase(Locale.ROOT);
        return s.contains("开启") || "true".equals(s) || "yes".equals(s) || "1".equals(s) || "https".equals(s);
    }

    private static boolean isBlankOrPlaceholder(String v) {
        if (StrUtil.isBlank(v)) {
            return true;
        }
        String s = v.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("REPLACE") || s.contains("YOUR_") || s.equals("TODO");
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
}
