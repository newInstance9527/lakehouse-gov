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
package vip.xiaonuo.lh.modular.datasource.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.LhOmCatalogClassifier;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.discover.LhInventoryObjectKinds;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 门户清单 → OpenMetadata 直通（不经过 Gravitino）
 * <p>按 OM 官方服务族挂载：Messaging/Topic、Search/SearchIndex、Storage/Container，
 * 以及 Pipeline/Dashboard/API Service；其余回退 DatabaseService→Table。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Service
public class LhPortalInventoryOmBridge {

    @Resource
    private LhDsTableMapper dsTableMapper;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private LhVaultClient vaultClient;

    /**
     * 将门户 ig_ds_table 清单 upsert 到 OM
     *
     * @param ds      数据源
     * @param catalog 挂载用 catalog 名（通常 dsCode 清洗名）
     * @return 推送摘要
     */
    public Map<String, Object> pushInventory(LhDatasource ds, String catalog) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (ds == null || StrUtil.isBlank(ds.getId())) {
            r.put("skipped", true);
            r.put("reason", "no_datasource");
            return r;
        }
        String cat = StrUtil.blankToDefault(catalog, gravitinoProjector.catalogNameOf(ds));
        LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, cat);
        String family = StrUtil.blankToDefault(classify.omFamily, LhOmCatalogClassifier.FAMILY_DATABASE);
        String kind = LhInventoryObjectKinds.ofType(ds.getType());
        String kindEn = LhInventoryObjectKinds.labelOf(kind);

        List<LhDsTable> rows = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId())
                .orderByAsc(LhDsTable::getTableName));
        if (rows == null) {
            rows = List.of();
        }

        r.put("omFamily", family);
        r.put("omServiceType", classify.serviceType);
        r.put("omService", classify.serviceName);
        r.put("objectKind", kind);
        r.put("objectKindLabel", kindEn);
        r.put("path", "portal_om");

        // 无清单时：仍确保官方 Service 节点出现在 OM（Kafka/ES/S3 等）
        if (rows.isEmpty()) {
            try {
                ensureServiceShell(ds, classify, family);
                r.put("skipped", true);
                r.put("reason", "empty_inventory");
                r.put("ok", true);
                r.put("upserted", 0);
                r.put("hint", "empty portal inventory; OM service ensured");
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
            }
            return r;
        }

        return switch (family) {
            case LhOmCatalogClassifier.FAMILY_MESSAGING -> pushMessaging(ds, classify, rows, r);
            case LhOmCatalogClassifier.FAMILY_SEARCH -> pushSearch(ds, classify, rows, r);
            case LhOmCatalogClassifier.FAMILY_STORAGE -> pushStorage(classify, rows, r);
            case LhOmCatalogClassifier.FAMILY_PIPELINE,
                 LhOmCatalogClassifier.FAMILY_DASHBOARD,
                 LhOmCatalogClassifier.FAMILY_API -> pushServiceShell(ds, classify, family, rows, r);
            default -> pushDatabaseTables(classify, kind, kindEn, cat, rows, r);
        };
    }

    private Map<String, Object> pushMessaging(LhDatasource ds, LhOmCatalogClassifier.Spec classify,
                                              List<LhDsTable> rows, Map<String, Object> r) {
        String bootstrap = resolveBootstrap(ds);
        openMetadataClient.ensureMessagingService(classify, bootstrap);
        int upserted = 0;
        int errors = 0;
        List<String> msgs = new ArrayList<>();
        for (LhDsTable row : rows) {
            if (row == null || StrUtil.isBlank(row.getTableName())) {
                continue;
            }
            String rawName = row.getTableName().trim();
            String omName = sanitizeOmName(rawName);
            if (StrUtil.isBlank(omName)) {
                continue;
            }
            try {
                int parts = 1;
                if (row.getRowCount() != null && row.getRowCount() > 0) {
                    parts = row.getRowCount().intValue();
                }
                String desc = firstNonBlank(row.getCommentTxt(), row.getRemark(), "Topic: " + rawName);
                // OM Topic name 允许原 topic 名中的部分字符；优先保留原名，非法再清洗
                String topicName = isOmEntityName(rawName) ? rawName : omName;
                openMetadataClient.upsertTopic(classify, topicName, parts, desc, bootstrap);
                upserted++;
            } catch (Exception e) {
                errors++;
                if (msgs.size() < 12) {
                    msgs.add(rawName + ": " + e.getMessage());
                }
                log.warn("Portal→OM Topic fail dsId={} name={}: {}", ds.getId(), rawName, e.getMessage());
            }
        }
        r.put("ok", upserted > 0 || errors == 0);
        r.put("upserted", upserted);
        r.put("errors", errors);
        r.put("errorMsgs", msgs);
        r.put("fqnPrefix", classify.serviceName);
        r.put("inventoried", rows.size());
        return r;
    }

    private Map<String, Object> pushSearch(LhDatasource ds, LhOmCatalogClassifier.Spec classify,
                                           List<LhDsTable> rows, Map<String, Object> r) {
        String hostPort = resolveEsHostPort(ds);
        openMetadataClient.ensureSearchService(classify, hostPort);
        int upserted = 0;
        int errors = 0;
        List<String> msgs = new ArrayList<>();
        for (LhDsTable row : rows) {
            if (row == null || StrUtil.isBlank(row.getTableName())) {
                continue;
            }
            String rawName = row.getTableName().trim();
            String omName = sanitizeOmName(rawName);
            if (StrUtil.isBlank(omName)) {
                continue;
            }
            try {
                String indexName = isOmEntityName(rawName) ? rawName : omName;
                String desc = firstNonBlank(row.getCommentTxt(), row.getRemark(), "Index: " + rawName);
                openMetadataClient.upsertSearchIndex(classify, indexName, desc);
                upserted++;
            } catch (Exception e) {
                errors++;
                if (msgs.size() < 12) {
                    msgs.add(rawName + ": " + e.getMessage());
                }
                log.warn("Portal→OM SearchIndex fail dsId={} name={}: {}", ds.getId(), rawName, e.getMessage());
            }
        }
        r.put("ok", upserted > 0 || errors == 0);
        r.put("upserted", upserted);
        r.put("errors", errors);
        r.put("errorMsgs", msgs);
        r.put("fqnPrefix", classify.serviceName);
        r.put("inventoried", rows.size());
        return r;
    }

    private Map<String, Object> pushStorage(LhOmCatalogClassifier.Spec classify,
                                            List<LhDsTable> rows, Map<String, Object> r) {
        openMetadataClient.ensureStorageService(classify);
        int upserted = 0;
        int errors = 0;
        List<String> msgs = new ArrayList<>();
        for (LhDsTable row : rows) {
            if (row == null || StrUtil.isBlank(row.getTableName())) {
                continue;
            }
            String rawName = row.getTableName().trim();
            String omName = sanitizeOmName(rawName);
            if (StrUtil.isBlank(omName)) {
                continue;
            }
            try {
                String desc = firstNonBlank(row.getCommentTxt(), row.getRemark(), "Container: " + rawName);
                openMetadataClient.upsertContainer(classify, omName, desc);
                upserted++;
            } catch (Exception e) {
                errors++;
                if (msgs.size() < 12) {
                    msgs.add(rawName + ": " + e.getMessage());
                }
                log.warn("Portal→OM Container fail name={}: {}", rawName, e.getMessage());
            }
        }
        r.put("ok", upserted > 0 || errors == 0);
        r.put("upserted", upserted);
        r.put("errors", errors);
        r.put("errorMsgs", msgs);
        r.put("fqnPrefix", classify.serviceName);
        r.put("inventoried", rows.size());
        return r;
    }

    private Map<String, Object> pushServiceShell(LhDatasource ds, LhOmCatalogClassifier.Spec classify,
                                                 String family, List<LhDsTable> rows, Map<String, Object> r) {
        openMetadataClient.ensureTypedService(classify);
        r.put("ok", true);
        r.put("upserted", 0);
        r.put("inventoried", rows.size());
        r.put("hint", "OM " + family + " service ensured; asset-level sync not implemented for " + classify.typeCode);
        return r;
    }

    private Map<String, Object> pushDatabaseTables(LhOmCatalogClassifier.Spec classify,
                                                   String kind, String kindEn, String cat,
                                                   List<LhDsTable> rows, Map<String, Object> r) {
        String schema = sanitizeOmName(kind);
        if (StrUtil.isBlank(schema)) {
            schema = "inventory";
        }
        int upserted = 0;
        int errors = 0;
        List<String> msgs = new ArrayList<>();
        String metalake = "lakehouse";
        for (LhDsTable row : rows) {
            if (row == null || StrUtil.isBlank(row.getTableName())) {
                continue;
            }
            String rawName = row.getTableName().trim();
            String omName = sanitizeOmName(rawName);
            if (StrUtil.isBlank(omName)) {
                continue;
            }
            try {
                GravitinoClient.GravTable gt = new GravitinoClient.GravTable();
                gt.metalake = metalake;
                gt.catalog = cat;
                gt.schema = schema;
                gt.name = omName;
                gt.comment = kindEn + ": " + rawName;
                gt.auditVersion = System.currentTimeMillis();
                GravitinoClient.GravColumn c1 = new GravitinoClient.GravColumn();
                c1.name = "object_kind";
                c1.type = "varchar(64)";
                c1.comment = kind;
                c1.nullable = true;
                GravitinoClient.GravColumn c2 = new GravitinoClient.GravColumn();
                c2.name = "object_name";
                c2.type = "varchar(512)";
                c2.comment = rawName;
                c2.nullable = true;
                gt.columns = List.of(c1, c2);

                LhOmCatalogClassifier.Spec spec = copy(classify);
                if (StrUtil.isNotBlank(spec.databaseDescription)) {
                    spec.databaseDescription = spec.databaseDescription
                            + "; inventory=" + kind + "; path=portal_om";
                }
                openMetadataClient.upsertTableStructure(
                        classify.serviceName, classify.databaseName, schema, gt, spec);
                upserted++;
            } catch (Exception e) {
                errors++;
                if (msgs.size() < 12) {
                    msgs.add(rawName + ": " + e.getMessage());
                }
                log.warn("Portal→OM Table fail name={}: {}", rawName, e.getMessage());
            }
        }
        r.put("ok", upserted > 0 || errors == 0);
        r.put("omDatabase", classify.databaseName);
        r.put("omSchema", schema);
        r.put("upserted", upserted);
        r.put("errors", errors);
        r.put("errorMsgs", msgs);
        r.put("fqnPrefix", classify.serviceName + "." + classify.databaseName + "." + schema);
        r.put("inventoried", rows.size());
        return r;
    }

    private void ensureServiceShell(LhDatasource ds, LhOmCatalogClassifier.Spec classify, String family) {
        switch (family) {
            case LhOmCatalogClassifier.FAMILY_MESSAGING ->
                    openMetadataClient.ensureMessagingService(classify, resolveBootstrap(ds));
            case LhOmCatalogClassifier.FAMILY_SEARCH ->
                    openMetadataClient.ensureSearchService(classify, resolveEsHostPort(ds));
            case LhOmCatalogClassifier.FAMILY_STORAGE ->
                    openMetadataClient.ensureStorageService(classify);
            case LhOmCatalogClassifier.FAMILY_PIPELINE,
                 LhOmCatalogClassifier.FAMILY_DASHBOARD,
                 LhOmCatalogClassifier.FAMILY_API ->
                    openMetadataClient.ensureTypedService(classify);
            default -> {
                // Database 族空清单不预建（避免空 CustomDatabase 噪音）
            }
        }
    }

    private String resolveBootstrap(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String bootstrap = first(secret, "bootstrap", "bootstrapServers", "host");
        String port = first(secret, "port");
        if (StrUtil.isBlank(bootstrap) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            bootstrap = ds.getEndpointHost();
            if (StrUtil.isBlank(port)) {
                port = ds.getEndpointPort();
            }
        }
        if (StrUtil.isNotBlank(port) && StrUtil.isNotBlank(bootstrap) && !bootstrap.contains(":")) {
            bootstrap = bootstrap + ":" + port;
        }
        return bootstrap;
    }

    private String resolveEsHostPort(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String host = first(secret, "host", "hosts", "url", "endpoint");
        String port = first(secret, "port");
        if (StrUtil.isBlank(host) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            host = ds.getEndpointHost();
            if (StrUtil.isBlank(port)) {
                port = ds.getEndpointPort();
            }
        }
        if (StrUtil.isBlank(host)) {
            return "http://127.0.0.1:9200";
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        if (StrUtil.isNotBlank(port) && !host.contains(":")) {
            return "http://" + host + ":" + port;
        }
        if (host.contains(":")) {
            return "http://" + host;
        }
        return "http://" + host + ":9200";
    }

    /**
     * 是否应走门户→OM（Grav 不支持，或 Grav 有 Catalog 但不做 relational Schema Sync）
     */
    public boolean shouldPush(LhDatasource ds) {
        if (ds == null) {
            return false;
        }
        String t = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        return switch (t) {
            case "rabbitmq", "redis", "elasticsearch", "es",
                 "kafka", "pulsar",
                 "s3", "minio", "s3_minio", "hdfs",
                 "mongodb", "http_api", "file", "ftp",
                 "trino", "tableau", "superset", "airflow",
                 "iceberg", "hbase" -> true;
            default -> false;
        };
    }

    private static LhOmCatalogClassifier.Spec copy(LhOmCatalogClassifier.Spec src) {
        LhOmCatalogClassifier.Spec s = new LhOmCatalogClassifier.Spec();
        s.omFamily = src.omFamily;
        s.serviceName = src.serviceName;
        s.serviceType = src.serviceType;
        s.serviceDisplayName = src.serviceDisplayName;
        s.serviceDescription = src.serviceDescription;
        s.databaseName = src.databaseName;
        s.databaseDisplayName = src.databaseDisplayName;
        s.databaseDescription = src.databaseDescription;
        s.schemaName = src.schemaName;
        s.category = src.category;
        s.typeCode = src.typeCode;
        return s;
    }

    /** OM 标识：字母数字下划线，保留可读性 */
    static String sanitizeOmName(String name) {
        if (StrUtil.isBlank(name)) {
            return "";
        }
        String s = name.trim()
                .replace('.', '_')
                .replace('/', '_')
                .replace(':', '_')
                .replace('-', '_');
        s = s.replaceAll("[^a-zA-Z0-9_]", "_");
        if (s.isEmpty()) {
            return "";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "o_" + s;
        }
        return StrUtil.sub(s, 0, 128);
    }

    /** Topic/Index 名：OM entityName 较宽松，允许 ._- 等常见字符 */
    private static boolean isOmEntityName(String name) {
        if (StrUtil.isBlank(name) || name.contains("::")) {
            return false;
        }
        return name.length() <= 256 && name.matches("^[A-Za-z0-9._\\-]+$");
    }

    private static String first(Map<String, Object> m, String... keys) {
        if (m == null) {
            return "";
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return "";
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return "";
    }
}
