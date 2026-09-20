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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MongoDB：{@code find().limit(N)} 样例文档（无 Grav Catalog）。
 */
@Slf4j
@Component
@Order(28)
public class MongoPreviewAdapter implements GovAssetPreviewAdapter {

    private static final int VALUE_MAX = 800;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 28;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        return "mongodb".equalsIgnoreCase(PreviewAdapterSupport.dsType(ctx.getPrimaryDs()))
                && StrUtil.isNotBlank(ctx.getObjectName());
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String objectName = ctx.getObjectName().trim();
        // db.collection 或仅 collection
        String database;
        String collection;
        int dot = objectName.indexOf('.');
        if (dot > 0 && objectName.indexOf('.', dot + 1) < 0) {
            database = objectName.substring(0, dot);
            collection = objectName.substring(dot + 1);
        } else {
            database = PreviewAdapterSupport.firstNonBlank(ds.getDatabaseName(), "admin");
            collection = objectName.contains(".") ? objectName.substring(objectName.lastIndexOf('.') + 1) : objectName;
        }
        int limit = Math.min(Math.max(ctx.getLimit(), 1), 50);
        Map<String, Object> r = ctx.newResult();
        r.put("qualifiedName", database + "." + collection);
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String uri = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("uri")),
                    PreviewAdapterSupport.str(secret.get("mongoUri")),
                    PreviewAdapterSupport.str(secret.get("connectionString")));
            if (StrUtil.isBlank(uri)) {
                String host = PreviewAdapterSupport.firstNonBlank(
                        PreviewAdapterSupport.str(secret.get("host")), ds.getEndpointHost());
                String port = PreviewAdapterSupport.firstNonBlank(
                        PreviewAdapterSupport.str(secret.get("port")), ds.getEndpointPort(), "27017");
                String user = PreviewAdapterSupport.firstNonBlank(
                        PreviewAdapterSupport.str(secret.get("username")),
                        PreviewAdapterSupport.str(secret.get("user")), "");
                String pwd = PreviewAdapterSupport.firstNonBlank(
                        PreviewAdapterSupport.str(secret.get("password")), "");
                if (StrUtil.isBlank(host)) {
                    return PreviewAdapterSupport.emptyFail(r, "mongodb", "MongoDB 无 host/uri，无法预览");
                }
                if (StrUtil.isNotBlank(user)) {
                    uri = "mongodb://" + encode(user) + ":" + encode(pwd) + "@" + host + ":" + port;
                } else {
                    uri = "mongodb://" + host + ":" + port;
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> colSet = new LinkedHashSet<>();
            try (MongoClient client = MongoClients.create(uri)) {
                MongoDatabase db = client.getDatabase(database);
                MongoCollection<Document> coll = db.getCollection(collection);
                for (Document doc : coll.find().limit(limit)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : doc.entrySet()) {
                        String k = e.getKey();
                        colSet.add(k);
                        Object v = e.getValue();
                        row.put(k, truncate(v == null ? null : String.valueOf(v)));
                    }
                    rows.add(row);
                }
            }
            List<String> columns = new ArrayList<>(colSet);
            if (columns.isEmpty()) {
                columns = List.of("_id");
            }
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> n = new LinkedHashMap<>();
                for (String c : columns) {
                    n.put(c, row.getOrDefault(c, null));
                }
                normalized.add(n);
            }
            r.put("ok", true);
            r.put("source", "mongodb");
            r.put("columns", columns);
            r.put("rows", normalized);
            r.put("rowCount", normalized.size());
            r.put("message", normalized.isEmpty()
                    ? "已连接集合，暂无文档样例"
                    : "MongoDB find 样例（探查，非 Grav/Trino）");
            r.put("hint", "Gravitino 无 Mongo Catalog；目录预览走原生驱动");
            return r;
        } catch (Exception e) {
            log.warn("Mongo preview fail {}.{}: {}", database, collection, e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "mongodb",
                    "MongoDB 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }

    private static String encode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String truncate(String v) {
        if (v == null) {
            return null;
        }
        return v.length() > VALUE_MAX ? v.substring(0, VALUE_MAX) + "…" : v;
    }
}
