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
package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;

import java.util.*;

/**
 * OpenMetadata 客户端（Bot Token 来自 Vault）
 * <p>禁止使用人类登录 JWT；服务端只读 {@code platform/openmetadata/bot}。
 * Schema Sync 仅 upsert 结构字段，保留 description / owner / tags。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class OpenMetadataClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;
    @Resource
    private LhVaultClient vaultClient;

    /**
     * 健康 / 版本探测
     *
     * @return 状态
     */
    public Map<String, Object> health() {
        try {
            String body = authGet("/api/v1/system/version");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "openmetadata");
            m.put("status", "UP");
            m.put("body", body);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "openmetadata");
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    /**
     * 表列表（示例）
     *
     * @param limit 条数
     * @return 原始 JSON
     */
    public Map<String, Object> listTables(int limit) {
        try {
            String body = authGet("/api/v1/tables?limit=" + Math.max(1, Math.min(limit, 100)));
            return Map.of("ok", true, "data", JSONUtil.parse(body));
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /**
     * 将 Bot Token 写入 Vault（运维录入 / 轮换）
     *
     * @param token Bot PAT
     * @param email 可选
     */
    public void rotateBotToken(String token, String email) {
        if (StrUtil.isBlank(token)) {
            throw new CommonException("token 不能为空");
        }
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("token", token);
        if (StrUtil.isNotBlank(email)) {
            secret.put("email", email);
        }
        LhProperties.Openmetadata om = lhProperties.getOpenmetadata();
        if (StrUtil.isNotBlank(om.getPassword())) {
            secret.putIfAbsent("password", om.getPassword());
        }
        vaultClient.write(om.getVaultPath(), secret);
        cachedToken = token;
        cachedAtMs = System.currentTimeMillis();
    }

    /** 进程内缓存，避免每次 HTTP 都读库 */
    private volatile String cachedToken;
    private volatile long cachedAtMs;

    /**
     * 解析 OM 访问令牌：Vault/yml → 必要时用 email/password 登录并回写 Vault
     */
    public String resolveAccessToken() {
        if (StrUtil.isNotBlank(cachedToken) && System.currentTimeMillis() - cachedAtMs < 30 * 60 * 1000L) {
            return cachedToken;
        }
        try {
            String token = credentialResolver.openMetadataToken();
            cachedToken = token;
            cachedAtMs = System.currentTimeMillis();
            return token;
        } catch (CommonException ex) {
            String loginToken = loginAndSeedToken();
            if (StrUtil.isBlank(loginToken)) {
                throw ex;
            }
            return loginToken;
        }
    }

    private String loginAndSeedToken() {
        LhProperties.Openmetadata om = lhProperties.getOpenmetadata();
        Map<String, Object> vault = vaultClient.readOrEmpty(om.getVaultPath());
        String email = firstNonBlank(str(vault.get("email")), om.getEmail());
        String password = firstNonBlank(str(vault.get("password")), om.getPassword());
        if (StrUtil.isBlank(email) || StrUtil.isBlank(password)) {
            return null;
        }
        String base = trim(om.getUrl());
        JSONObject body = new JSONObject();
        body.set("email", email);
        body.set("password", cn.hutool.core.codec.Base64.encode(password));
        var resp = HttpRequest.post(base + "/api/v1/users/login")
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(15000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("OpenMetadata 登录失败 {}: {}", resp.getStatus(), resp.body());
        }
        JSONObject json = JSONUtil.parseObj(resp.body());
        String token = firstNonBlank(json.getStr("accessToken"), json.getStr("token"));
        if (StrUtil.isBlank(token)) {
            throw new CommonException("OpenMetadata 登录响应无 accessToken");
        }
        Map<String, Object> secret = new LinkedHashMap<>(vault == null ? Map.of() : vault);
        secret.put("token", token);
        secret.put("email", email);
        secret.put("password", password);
        vaultClient.write(om.getVaultPath(), secret);
        cachedToken = token;
        cachedAtMs = System.currentTimeMillis();
        return token;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 按 FQN 取表；不存在返回 null
     */
    public JSONObject getTableByFqn(String fqn) {
        try {
            String body = authGet("/api/v1/tables/name/" + encPath(fqn) + "?fields=columns,owners,tags,description");
            return JSONUtil.parseObj(body);
        } catch (CommonException e) {
            if (StrUtil.containsIgnoreCase(e.getMessage(), "404")
                    || StrUtil.containsIgnoreCase(e.getMessage(), "not found")) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Grav→OM：仅 upsert 结构（列名/类型），不覆盖业务注释 / owner / tags
     *
     * @param serviceName OM DatabaseService 名
     * @param database    OM Database 名
     * @param schema      OM Schema（库内 schema）名
     * @param grav        Grav 表结构
     * @return omTableId / fqn / created / skipped
     */
    public Map<String, Object> upsertTableStructure(String serviceName, String database, String schema,
                                                  GravitinoClient.GravTable grav) {
        return upsertTableStructure(serviceName, database, schema, grav, null);
    }

    /**
     * Grav/JDBC→OM：upsert 表结构；可带分类规格创建 DatabaseService
     */
    public Map<String, Object> upsertTableStructure(String serviceName, String database, String schema,
                                                  GravitinoClient.GravTable grav,
                                                  LhOmCatalogClassifier.Spec classify) {
        String fqn = serviceName + "." + database + "." + schema + "." + grav.name;
        JSONObject existing = getTableByFqn(fqn);

        Map<String, String> existingColDesc = new LinkedHashMap<>();
        String tableDesc = null;
        Object owners = null;
        Object tags = null;
        if (existing != null) {
            tableDesc = existing.getStr("description");
            owners = existing.get("owners");
            tags = existing.get("tags");
            JSONArray cols = existing.getJSONArray("columns");
            if (cols != null) {
                for (int i = 0; i < cols.size(); i++) {
                    JSONObject c = cols.getJSONObject(i);
                    String name = c.getStr("name");
                    String desc = c.getStr("description");
                    if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(desc)) {
                        existingColDesc.put(name, desc);
                    }
                }
            }
        }

        JSONArray columns = new JSONArray();
        for (GravitinoClient.GravColumn gc : grav.columns) {
            if (StrUtil.isBlank(gc.name)) {
                continue;
            }
            JSONObject col = new JSONObject();
            col.set("name", gc.name);
            String omType = mapOmDataType(gc.type);
            col.set("dataType", omType);
            col.set("dataTypeDisplay", StrUtil.blankToDefault(gc.type, omType));
            Integer len = resolveDataLength(gc.type, omType);
            if (len != null) {
                col.set("dataLength", len);
            }
            if (existingColDesc.containsKey(gc.name)) {
                col.set("description", existingColDesc.get(gc.name));
            } else if (existing == null && StrUtil.isNotBlank(gc.comment)) {
                col.set("description", gc.comment);
            }
            columns.add(col);
        }

        if (existing == null) {
            ensureDatabaseHierarchy(serviceName, database, schema, classify);
            String schemaFqn = serviceName + "." + database + "." + schema;
            JSONObject create = new JSONObject();
            create.set("name", grav.name);
            create.set("tableType", "Regular");
            if (columns.isEmpty()) {
                JSONObject placeholder = new JSONObject();
                placeholder.set("name", "_placeholder");
                placeholder.set("dataType", "VARCHAR");
                placeholder.set("dataTypeDisplay", "varchar");
                placeholder.set("dataLength", 64);
                columns.add(placeholder);
            }
            create.set("columns", columns);
            create.set("databaseSchema", schemaFqn);
            if (StrUtil.isNotBlank(grav.location)) {
                create.set("sourceUrl", grav.location);
            }
            String body = authPost("/api/v1/tables", create.toString());
            JSONObject created = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created", true);
            r.put("fqn", created.getStr("fullyQualifiedName", fqn));
            r.put("omTableId", created.getStr("id"));
            r.put("omRevision", created.getStr("version"));
            r.put("service", serviceName);
            return r;
        }

        // 已存在：OM PUT /api/v1/tables 走 CreateTable（createOrUpdate），不能带 id
        JSONObject put = new JSONObject();
        put.set("name", existing.getStr("name", grav.name));
        put.set("tableType", existing.getStr("tableType", "Regular"));
        put.set("columns", columns);
        put.set("databaseSchema", resolveSchemaFqn(existing, serviceName, database, schema));
        if (StrUtil.isNotBlank(tableDesc)) {
            put.set("description", tableDesc);
        }
        if (owners != null) {
            put.set("owners", owners);
        }
        if (tags != null) {
            put.set("tags", tags);
        }
        if (existing.get("extension") != null) {
            put.set("extension", existing.get("extension"));
        }
        if (StrUtil.isNotBlank(existing.getStr("displayName"))) {
            put.set("displayName", existing.getStr("displayName"));
        }
        if (StrUtil.isNotBlank(grav.location)) {
            put.set("sourceUrl", grav.location);
        } else if (StrUtil.isNotBlank(existing.getStr("sourceUrl"))) {
            put.set("sourceUrl", existing.getStr("sourceUrl"));
        }
        String body = authPut("/api/v1/tables", put.toString());
        JSONObject updated = JSONUtil.parseObj(body);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("created", false);
        r.put("fqn", updated.getStr("fullyQualifiedName", fqn));
        r.put("omTableId", updated.getStr("id", existing.getStr("id")));
        r.put("omRevision", updated.getStr("version"));
        r.put("service", serviceName);
        return r;
    }

    /**
     * 确保 MessagingService 存在（Kafka / CustomMessaging 等）
     *
     * @param classify          分类
     * @param bootstrapServers  Kafka bootstrap；可空时占位
     */
    public void ensureMessagingService(LhOmCatalogClassifier.Spec classify, String bootstrapServers) {
        if (classify == null || StrUtil.isBlank(classify.serviceName)) {
            return;
        }
        String serviceType = StrUtil.blankToDefault(classify.serviceType, "Kafka");
        JSONObject s = new JSONObject();
        s.set("name", classify.serviceName);
        if (StrUtil.isNotBlank(classify.serviceDisplayName)) {
            s.set("displayName", classify.serviceDisplayName);
        }
        if (StrUtil.isNotBlank(classify.serviceDescription)) {
            s.set("description", classify.serviceDescription);
        }
        s.set("serviceType", serviceType);
        JSONObject config = new JSONObject();
        config.set("type", serviceType);
        if ("Kafka".equals(serviceType)) {
            config.set("bootstrapServers",
                    StrUtil.blankToDefault(bootstrapServers, "localhost:9092"));
            config.set("securityProtocol", "PLAINTEXT");
        }
        // CustomMessaging：仅 type（无 bootstrapServers 字段）
        s.set("connection", new JSONObject().set("config", config));
        try {
            authPost("/api/v1/services/messagingServices", s.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /**
     * Upsert Topic（MessagingService → Topic；FQN=service.topic）
     */
    public Map<String, Object> upsertTopic(LhOmCatalogClassifier.Spec classify, String topicName,
                                           int partitions, String description, String bootstrapServers) {
        String service = classify.serviceName;
        String name = topicName;
        String fqn = service + "." + name;
        ensureMessagingService(classify, bootstrapServers);
        JSONObject create = new JSONObject();
        create.set("name", name);
        create.set("service", service);
        create.set("partitions", Math.max(1, partitions));
        if (StrUtil.isNotBlank(description)) {
            create.set("description", description);
        }
        create.set("messageSchema", new JSONObject().set("schemaType", "None"));
        try {
            // PUT = createOrUpdate
            String body = authPut("/api/v1/topics", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fqn", js.getStr("fullyQualifiedName", fqn));
            r.put("omTopicId", js.getStr("id"));
            r.put("service", service);
            return r;
        } catch (Exception putEx) {
            String body = authPost("/api/v1/topics", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fqn", js.getStr("fullyQualifiedName", fqn));
            r.put("omTopicId", js.getStr("id"));
            r.put("service", service);
            return r;
        }
    }

    /**
     * 确保 SearchService 存在（ElasticSearch）
     */
    public void ensureSearchService(LhOmCatalogClassifier.Spec classify, String hostPort) {
        if (classify == null || StrUtil.isBlank(classify.serviceName)) {
            return;
        }
        String serviceType = StrUtil.blankToDefault(classify.serviceType, "ElasticSearch");
        JSONObject s = new JSONObject();
        s.set("name", classify.serviceName);
        if (StrUtil.isNotBlank(classify.serviceDisplayName)) {
            s.set("displayName", classify.serviceDisplayName);
        }
        if (StrUtil.isNotBlank(classify.serviceDescription)) {
            s.set("description", classify.serviceDescription);
        }
        s.set("serviceType", serviceType);
        JSONObject config = new JSONObject();
        config.set("type", serviceType);
        config.set("hostPort", StrUtil.blankToDefault(hostPort, "http://127.0.0.1:9200"));
        s.set("connection", new JSONObject().set("config", config));
        try {
            authPost("/api/v1/services/searchServices", s.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /**
     * Upsert SearchIndex（FQN=service.index）
     */
    public Map<String, Object> upsertSearchIndex(LhOmCatalogClassifier.Spec classify, String indexName,
                                                 String description) {
        String service = classify.serviceName;
        ensureSearchService(classify, null);
        JSONObject create = new JSONObject();
        create.set("name", indexName);
        create.set("service", service);
        if (StrUtil.isNotBlank(description)) {
            create.set("description", description);
        }
        // fields 必填：占位字段
        JSONArray fields = new JSONArray();
        JSONObject f = new JSONObject();
        f.set("name", "object_name");
        f.set("dataType", "TEXT");
        f.set("dataTypeDisplay", "text");
        fields.add(f);
        create.set("fields", fields);
        String fqn = service + "." + indexName;
        try {
            String body = authPut("/api/v1/searchIndexes", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            return Map.of("fqn", js.getStr("fullyQualifiedName", fqn), "service", service);
        } catch (Exception e) {
            String body = authPost("/api/v1/searchIndexes", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fqn", js.getStr("fullyQualifiedName", fqn));
            r.put("service", service);
            return r;
        }
    }

    /**
     * 确保 StorageService 存在（S3 / CustomStorage）
     */
    public void ensureStorageService(LhOmCatalogClassifier.Spec classify) {
        if (classify == null || StrUtil.isBlank(classify.serviceName)) {
            return;
        }
        String serviceType = StrUtil.blankToDefault(classify.serviceType, "S3");
        JSONObject s = new JSONObject();
        s.set("name", classify.serviceName);
        if (StrUtil.isNotBlank(classify.serviceDisplayName)) {
            s.set("displayName", classify.serviceDisplayName);
        }
        if (StrUtil.isNotBlank(classify.serviceDescription)) {
            s.set("description", classify.serviceDescription);
        }
        s.set("serviceType", serviceType);
        JSONObject config = new JSONObject();
        config.set("type", serviceType);
        if ("S3".equals(serviceType)) {
            config.set("awsConfig", new JSONObject()
                    .set("awsAccessKeyId", "unused")
                    .set("awsSecretAccessKey", "unused")
                    .set("awsRegion", "us-east-1"));
        }
        s.set("connection", new JSONObject().set("config", config));
        try {
            authPost("/api/v1/services/storageServices", s.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /**
     * Upsert Container（StorageService → Container；FQN=service.container）
     */
    public Map<String, Object> upsertContainer(LhOmCatalogClassifier.Spec classify, String containerName,
                                               String description) {
        String service = classify.serviceName;
        ensureStorageService(classify);
        JSONObject create = new JSONObject();
        create.set("name", containerName);
        create.set("service", service);
        if (StrUtil.isNotBlank(description)) {
            create.set("description", description);
        }
        String fqn = service + "." + containerName;
        try {
            String body = authPut("/api/v1/containers", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            return Map.of("fqn", js.getStr("fullyQualifiedName", fqn), "service", service);
        } catch (Exception e) {
            String body = authPost("/api/v1/containers", create.toString());
            JSONObject js = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fqn", js.getStr("fullyQualifiedName", fqn));
            r.put("service", service);
            return r;
        }
    }

    /**
     * 确保 Pipeline / Dashboard / API Service（仅服务节点；资产可后续补）
     */
    public void ensureTypedService(LhOmCatalogClassifier.Spec classify) {
        if (classify == null || StrUtil.isBlank(classify.serviceName)) {
            return;
        }
        String family = StrUtil.blankToDefault(classify.omFamily, LhOmCatalogClassifier.FAMILY_DATABASE);
        String path = switch (family) {
            case LhOmCatalogClassifier.FAMILY_PIPELINE -> "/api/v1/services/pipelineServices";
            case LhOmCatalogClassifier.FAMILY_DASHBOARD -> "/api/v1/services/dashboardServices";
            case LhOmCatalogClassifier.FAMILY_API -> "/api/v1/services/apiServices";
            case LhOmCatalogClassifier.FAMILY_MESSAGING -> "/api/v1/services/messagingServices";
            case LhOmCatalogClassifier.FAMILY_SEARCH -> "/api/v1/services/searchServices";
            case LhOmCatalogClassifier.FAMILY_STORAGE -> "/api/v1/services/storageServices";
            default -> null;
        };
        if (path == null) {
            return;
        }
        if (LhOmCatalogClassifier.FAMILY_MESSAGING.equals(family)) {
            ensureMessagingService(classify, null);
            return;
        }
        if (LhOmCatalogClassifier.FAMILY_SEARCH.equals(family)) {
            ensureSearchService(classify, null);
            return;
        }
        if (LhOmCatalogClassifier.FAMILY_STORAGE.equals(family)) {
            ensureStorageService(classify);
            return;
        }
        JSONObject s = new JSONObject();
        s.set("name", classify.serviceName);
        if (StrUtil.isNotBlank(classify.serviceDisplayName)) {
            s.set("displayName", classify.serviceDisplayName);
        }
        if (StrUtil.isNotBlank(classify.serviceDescription)) {
            s.set("description", classify.serviceDescription);
        }
        s.set("serviceType", classify.serviceType);
        JSONObject config = new JSONObject();
        config.set("type", classify.serviceType);
        if (LhOmCatalogClassifier.FAMILY_PIPELINE.equals(family)) {
            config.set("hostPort", "http://127.0.0.1:8080");
        } else if (LhOmCatalogClassifier.FAMILY_DASHBOARD.equals(family)) {
            config.set("hostPort", "http://127.0.0.1:8088");
        } else if (LhOmCatalogClassifier.FAMILY_API.equals(family)) {
            config.set("openAPISchemaURL", "http://127.0.0.1/openapi.json");
        }
        s.set("connection", new JSONObject().set("config", config));
        try {
            authPost(path, s.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /** CreateTable.databaseSchema 只要 FQN 字符串，不要 EntityReference */
    private static String resolveSchemaFqn(JSONObject existing, String service, String database, String schema) {
        Object dbSchema = existing == null ? null : existing.get("databaseSchema");
        if (dbSchema instanceof JSONObject) {
            String fqn = ((JSONObject) dbSchema).getStr("fullyQualifiedName");
            if (StrUtil.isNotBlank(fqn)) {
                return fqn;
            }
            String name = ((JSONObject) dbSchema).getStr("name");
            if (StrUtil.isNotBlank(name) && name.contains(".")) {
                return name;
            }
        } else if (dbSchema != null && StrUtil.isNotBlank(String.valueOf(dbSchema))) {
            return String.valueOf(dbSchema);
        }
        return service + "." + database + "." + schema;
    }

    private void ensureDatabaseHierarchy(String service, String database, String schema) {
        ensureDatabaseHierarchy(service, database, schema, null);
    }

    private void ensureDatabaseHierarchy(String service, String database, String schema,
                                         LhOmCatalogClassifier.Spec classify) {
        String serviceType = classify != null && StrUtil.isNotBlank(classify.serviceType)
                ? classify.serviceType : "CustomDatabase";
        try {
            JSONObject s = new JSONObject();
            s.set("name", service);
            if (classify != null && StrUtil.isNotBlank(classify.serviceDisplayName)) {
                s.set("displayName", classify.serviceDisplayName);
            }
            if (classify != null && StrUtil.isNotBlank(classify.serviceDescription)) {
                s.set("description", classify.serviceDescription);
            }
            s.set("serviceType", serviceType);
            s.set("connection", buildServiceConnection(serviceType));
            authPost("/api/v1/services/databaseServices", s.toString());
        } catch (Exception ignored) {
            // exists
        }
        try {
            JSONObject db = new JSONObject();
            db.set("name", database);
            if (classify != null && StrUtil.isNotBlank(classify.databaseDisplayName)) {
                db.set("displayName", classify.databaseDisplayName);
            }
            if (classify != null && StrUtil.isNotBlank(classify.databaseDescription)) {
                db.set("description", classify.databaseDescription);
            }
            db.set("service", service);
            authPost("/api/v1/databases", db.toString());
        } catch (Exception ignored) {
            // exists
        }
        try {
            JSONObject sch = new JSONObject();
            sch.set("name", schema);
            sch.set("database", service + "." + database);
            authPost("/api/v1/databaseSchemas", sch.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /**
     * 按 OM serviceType 构造最小可用 connection（仅元数据挂载，非强制探活）
     */
    private JSONObject buildServiceConnection(String serviceType) {
        JSONObject config = new JSONObject();
        config.set("type", serviceType);
        switch (serviceType) {
            case "Mysql" -> {
                config.set("scheme", "mysql+pymysql");
                config.set("username", "lakehouse");
                config.set("authType", new JSONObject().set("password", "unused"));
                config.set("hostPort", "127.0.0.1:3306");
            }
            case "Postgres" -> {
                config.set("scheme", "postgresql+psycopg2");
                config.set("username", "lakehouse");
                config.set("authType", new JSONObject().set("password", "unused"));
                config.set("hostPort", "127.0.0.1:5432");
                config.set("database", "postgres");
            }
            case "Clickhouse" -> {
                config.set("scheme", "clickhouse+http");
                config.set("username", "default");
                config.set("password", "unused");
                config.set("hostPort", "127.0.0.1:8123");
                config.set("databaseSchema", "default");
            }
            case "Oracle" -> {
                config.set("scheme", "oracle+cx_oracle");
                config.set("username", "lakehouse");
                config.set("password", "unused");
                config.set("hostPort", "127.0.0.1:1521");
                config.set("oracleConnectionType", new JSONObject().set("databaseSchema", "XE"));
            }
            case "Mssql" -> {
                config.set("scheme", "mssql+pyodbc");
                config.set("username", "sa");
                config.set("password", "unused");
                config.set("hostPort", "127.0.0.1:1433");
            }
            case "Trino" -> {
                config.set("scheme", "trino");
                config.set("hostPort", "127.0.0.1:8080");
                config.set("catalog", "hive");
            }
            case "Hive" -> {
                config.set("scheme", "hive");
                config.set("username", "hive");
                config.set("hostPort", "127.0.0.1:10000");
            }
            case "MongoDB" -> {
                config.set("scheme", "mongodb");
                config.set("hostPort", "127.0.0.1:27017");
            }
            case "Doris" -> {
                // Doris 连接形态接近 Mysql；仅元数据挂载占位
                config.set("scheme", "mysql+pymysql");
                config.set("username", "lakehouse");
                config.set("authType", new JSONObject().set("password", "unused"));
                config.set("hostPort", "127.0.0.1:9030");
            }
            case "Iceberg" -> {
                // 最小占位；真实 catalog 由门户/Grav 侧维护
                config.set("catalogUri", "http://127.0.0.1:8181");
            }
            case "Datalake" -> {
                // S3/MinIO 等：仅挂载占位，不探活
                config.set("configSource", new JSONObject()
                        .set("securityConfig", new JSONObject()
                                .set("awsAccessKeyId", "unused")
                                .set("awsSecretAccessKey", "unused")
                                .set("awsRegion", "us-east-1")));
                config.set("bucketName", "lakehouse");
                config.set("prefix", "");
            }
            default -> {
                // CustomDatabase 等：无额外必填
            }
        }
        return new JSONObject().set("config", config);
    }

    /**
     * 粗粒度类型映射到 OM ColumnDataType
     */
    private String mapOmDataType(String gravType) {
        if (StrUtil.isBlank(gravType)) {
            return "VARCHAR";
        }
        String t = gravType.toLowerCase(Locale.ROOT);
        if (t.contains("varchar") || t.contains("string") || t.contains("text") || t.contains("clob")) {
            return "VARCHAR";
        }
        if (t.contains("char") && !t.contains("varchar")) {
            return "CHAR";
        }
        if (t.contains("bigint") || t.contains("int8") || t.equals("long")) {
            return "BIGINT";
        }
        if (t.contains("tinyint") || t.contains("smallint") || t.contains("mediumint")
                || t.contains("int") || t.contains("integer") || t.contains("short")) {
            return "INT";
        }
        if (t.contains("double") || t.contains("float") || t.contains("real") || t.contains("decimal")
                || t.contains("numeric") || t.contains("number")) {
            if (t.contains("decimal") || t.contains("numeric") || t.contains("number")) {
                return "DECIMAL";
            }
            return t.contains("float") ? "FLOAT" : "DOUBLE";
        }
        if (t.contains("bool") || t.equals("bit")) {
            return "BOOLEAN";
        }
        if (t.contains("datetime") || t.contains("timestamp")) {
            return "TIMESTAMP";
        }
        if (t.contains("date")) {
            return "DATE";
        }
        if (t.contains("time")) {
            return "TIME";
        }
        if (t.contains("json")) {
            return "JSON";
        }
        if (t.contains("binary") || t.contains("bytes") || t.contains("blob") || t.contains("varbinary")) {
            return t.contains("varbinary") ? "VARBINARY" : "BINARY";
        }
        if (t.contains("array")) {
            return "ARRAY";
        }
        if (t.contains("map") || t.contains("struct")) {
            return "MAP";
        }
        // Grav external / unknown → VARCHAR
        return "VARCHAR";
    }

    /**
     * OM 对 VARCHAR/CHAR/BINARY/VARBINARY 强制要求 dataLength
     */
    private Integer resolveDataLength(String rawType, String omType) {
        if (!needsDataLength(omType)) {
            return null;
        }
        Integer parsed = parseLength(rawType);
        if (parsed != null && parsed > 0) {
            return parsed;
        }
        // 缺长度时给安全默认值，避免 CreateTable 400
        if ("CHAR".equals(omType)) {
            return 1;
        }
        if ("BINARY".equals(omType) || "VARBINARY".equals(omType)) {
            return 255;
        }
        return 255;
    }

    private static boolean needsDataLength(String omType) {
        return "VARCHAR".equals(omType) || "CHAR".equals(omType)
                || "BINARY".equals(omType) || "VARBINARY".equals(omType);
    }

    private static Integer parseLength(String rawType) {
        if (StrUtil.isBlank(rawType)) {
            return null;
        }
        // varchar(20) / VARCHAR(64) / decimal(10,2) → 取第一个数字
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(\\s*(\\d+)").matcher(rawType);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 表血缘（soft-fail）：GET /api/v1/lineage/table/name/{fqn}
     *
     * @param fqn            OM 表 FQN
     * @param upstreamDepth  上游层数
     * @param downstreamDepth 下游层数
     */
    public Map<String, Object> getTableLineage(String fqn, int upstreamDepth, int downstreamDepth) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (StrUtil.isBlank(fqn)) {
            r.put("ok", false);
            r.put("degraded", true);
            r.put("message", "fqn required");
            return r;
        }
        int up = Math.max(0, Math.min(upstreamDepth, 20));
        int down = Math.max(0, Math.min(downstreamDepth, 20));
        try {
            String path = "/api/v1/lineage/table/name/" + encPath(fqn)
                    + "?upstreamDepth=" + up + "&downstreamDepth=" + down;
            String body = authGet(path);
            r.put("ok", true);
            r.put("source", "openmetadata");
            r.put("fqn", fqn);
            r.put("upstreamDepth", up);
            r.put("downstreamDepth", down);
            r.put("data", JSONUtil.parse(body));
            return r;
        } catch (Exception e) {
            r.put("ok", false);
            r.put("degraded", true);
            r.put("source", "openmetadata");
            r.put("fqn", fqn);
            r.put("message", e.getMessage());
            return r;
        }
    }

    /**
     * 表级最新质量分摘要（soft-fail；无 profile 时 degraded）
     */
    public Map<String, Object> getTableProfileSummary(String fqn) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (StrUtil.isBlank(fqn)) {
            r.put("ok", false);
            r.put("message", "fqn required");
            return r;
        }
        try {
            JSONObject table = getTableByFqn(fqn);
            if (table == null) {
                r.put("ok", false);
                r.put("degraded", true);
                r.put("message", "table not found");
                return r;
            }
            String id = table.getStr("id");
            String body = authGet("/api/v1/tables/" + id + "/tableProfile");
            r.put("ok", true);
            r.put("source", "openmetadata");
            r.put("fqn", fqn);
            r.put("data", JSONUtil.parse(body));
            return r;
        } catch (Exception e) {
            r.put("ok", false);
            r.put("degraded", true);
            r.put("message", e.getMessage());
            return r;
        }
    }

    /**
     * 确保 Glossary 存在并 upsert 术语（仅 name/displayName/description；不写枚举码值）。
     *
     * @param glossaryName Glossary 名（如 LakehouseStandard）
     * @param termName     术语名（标准字段名或码值集 ID）
     * @param displayName  展示名；空则同 termName
     * @param description  人读描述；可为 null
     * @return fqn / omId / created|updated
     */
    public Map<String, Object> upsertGlossaryTerm(String glossaryName, String termName,
                                                  String displayName, String description) {
        if (StrUtil.isBlank(glossaryName) || StrUtil.isBlank(termName)) {
            throw new CommonException("Glossary 名与术语名不能为空");
        }
        String gName = glossaryName.trim();
        String tName = sanitizeGlossaryName(termName.trim());
        if (StrUtil.isBlank(tName)) {
            throw new CommonException("术语名非法: {}", termName);
        }
        JSONObject glossary = ensureGlossary(gName);
        String glossaryFqn = glossary.getStr("fullyQualifiedName", gName);
        String glossaryId = glossary.getStr("id");

        String termFqn = glossaryFqn + "." + tName;
        JSONObject existing = getGlossaryTermByFqn(termFqn);
        JSONObject body = new JSONObject();
        body.set("name", tName);
        body.set("displayName", StrUtil.blankToDefault(displayName, tName));
        if (description != null) {
            body.set("description", description);
        }
        JSONObject glossRef = new JSONObject();
        if (StrUtil.isNotBlank(glossaryId)) {
            glossRef.set("id", glossaryId);
        } else {
            glossRef.set("fullyQualifiedName", glossaryFqn);
        }
        body.set("glossary", glossRef);

        String respBody;
        boolean created;
        if (existing == null) {
            respBody = authPost("/api/v1/glossaryTerms", body.toString());
            created = true;
        } else {
            body.set("id", existing.getStr("id"));
            respBody = authPut("/api/v1/glossaryTerms", body.toString());
            created = false;
        }
        JSONObject updated = JSONUtil.parseObj(respBody);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("fqn", updated.getStr("fullyQualifiedName", termFqn));
        r.put("omId", updated.getStr("id"));
        r.put("glossaryFqn", glossaryFqn);
        r.put("created", created);
        r.put("description", updated.getStr("description"));
        return r;
    }

    /**
     * 按 FQN 读 Glossary Term；不存在返回 null
     */
    public JSONObject getGlossaryTermByFqn(String fqn) {
        return getEntityByFqn("glossaryTerms", fqn, "description,displayName,glossary");
    }

    private JSONObject ensureGlossary(String name) {
        JSONObject existing = getEntityByFqn("glossaries", name, "description");
        if (existing != null) {
            return existing;
        }
        JSONObject create = new JSONObject();
        create.set("name", name);
        create.set("displayName", name);
        create.set("description", "Lakehouse 数据标准术语（门户 gov_std_* soft-fail 同步；不含码值枚举）");
        String body = authPost("/api/v1/glossaries", create.toString());
        return JSONUtil.parseObj(body);
    }

    /** OM Glossary / Term 名：字母数字下划线连字符 */
    static String sanitizeGlossaryName(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') {
            s = "t_" + s;
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    private String authGet(String path) {
        return authRequest("GET", path, null, true);
    }

    private String authPost(String path, String json) {
        return authRequest("POST", path, json, true);
    }

    private String authPut(String path, String json) {
        return authRequest("PUT", path, json, true);
    }

    private String authPatch(String path, String jsonPatch) {
        return authRequest("PATCH", path, jsonPatch, true);
    }

    /**
     * 按 FQN 取 Topic；不存在返回 null
     */
    public JSONObject getTopicByFqn(String fqn) {
        return getEntityByFqn("topics", fqn, "owners,tags,description,messageSchema");
    }

    /**
     * 按 FQN 取 SearchIndex；不存在返回 null
     */
    public JSONObject getSearchIndexByFqn(String fqn) {
        return getEntityByFqn("searchIndexes", fqn, "owners,tags,description,fields");
    }

    /**
     * 按 FQN 取 Container；不存在返回 null
     */
    public JSONObject getContainerByFqn(String fqn) {
        return getEntityByFqn("containers", fqn, "owners,tags,description");
    }

    private JSONObject getEntityByFqn(String collection, String fqn, String fields) {
        try {
            String q = StrUtil.isNotBlank(fields) ? ("?fields=" + fields) : "";
            String body = authGet("/api/v1/" + collection + "/name/" + encPath(fqn) + q);
            return JSONUtil.parseObj(body);
        } catch (CommonException e) {
            if (StrUtil.containsIgnoreCase(e.getMessage(), "404")
                    || StrUtil.containsIgnoreCase(e.getMessage(), "not found")) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 按资产 kind / omEntityType 读取 OM 实体（结构 + 人读字段）
     *
     * @param omEntityType table/topic/searchIndex/container
     * @param fqn          OM FQN
     */
    public JSONObject getCatalogEntity(String omEntityType, String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return null;
        }
        String t = StrUtil.blankToDefault(omEntityType, "table").toLowerCase(Locale.ROOT);
        return switch (t) {
            case "topic" -> getTopicByFqn(fqn);
            case "searchindex", "search_index", "index" -> getSearchIndexByFqn(fqn);
            case "container" -> getContainerByFqn(fqn);
            default -> getTableByFqn(fqn);
        };
    }

    /**
     * JSON Patch 更新 OM 人读元数据（description / displayName / tags）
     * <p>不覆盖列结构；SoT 为人读字段的 OM。</p>
     *
     * @param omEntityType table/topic/searchIndex/container
     * @param fqn          实体 FQN
     * @param description  非 null 则 patch description（空串清空）
     * @param displayName  非 null 则 patch displayName
     * @param tagFqns      非 null 则整体 replace tags（传空列表清空）
     */
    public Map<String, Object> patchEntityMeta(String omEntityType, String fqn,
                                               String description, String displayName,
                                               List<String> tagFqns) {
        if (StrUtil.isBlank(fqn)) {
            throw new CommonException("OM FQN 不能为空");
        }
        String collection = switch (StrUtil.blankToDefault(omEntityType, "table").toLowerCase(Locale.ROOT)) {
            case "topic" -> "topics";
            case "searchindex", "search_index", "index" -> "searchIndexes";
            case "container" -> "containers";
            default -> "tables";
        };
        JSONObject existing = getCatalogEntity(omEntityType, fqn);
        if (existing == null) {
            throw new CommonException("OM 实体不存在: {}", fqn);
        }
        JSONArray patch = new JSONArray();
        if (description != null) {
            if (existing.getStr("description") == null) {
                patch.add(op("add", "/description", description));
            } else {
                patch.add(op("replace", "/description", description));
            }
        }
        if (displayName != null) {
            if (existing.getStr("displayName") == null) {
                patch.add(op("add", "/displayName", displayName));
            } else {
                patch.add(op("replace", "/displayName", displayName));
            }
        }
        if (tagFqns != null) {
            JSONArray tags = new JSONArray();
            for (String tag : tagFqns) {
                if (StrUtil.isBlank(tag)) {
                    continue;
                }
                JSONObject t = new JSONObject();
                t.set("tagFQN", tag.trim());
                t.set("source", "Classification");
                t.set("labelType", "Manual");
                t.set("state", "Confirmed");
                tags.add(t);
            }
            if (existing.get("tags") == null) {
                patch.add(op("add", "/tags", tags));
            } else {
                patch.add(op("replace", "/tags", tags));
            }
        }
        if (patch.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("skipped", true);
            r.put("reason", "no_fields");
            r.put("fqn", fqn);
            return r;
        }
        String body = authPatch("/api/v1/" + collection + "/name/" + encPath(fqn)
                + "?changeSource=Manual", patch.toString());
        JSONObject updated = JSONUtil.parseObj(body);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("fqn", updated.getStr("fullyQualifiedName", fqn));
        r.put("omId", updated.getStr("id"));
        r.put("omRevision", updated.getStr("version"));
        r.put("description", updated.getStr("description"));
        r.put("displayName", updated.getStr("displayName"));
        r.put("tags", updated.get("tags"));
        r.put("entityType", collection);
        return r;
    }

    private static JSONObject op(String op, String path, Object value) {
        JSONObject o = new JSONObject();
        o.set("op", op);
        o.set("path", path);
        o.set("value", value);
        return o;
    }

    private String authRequest(String method, String path, String json, boolean retryOn401) {
        String base = trim(lhProperties.getOpenmetadata().getUrl());
        String token = resolveAccessToken();
        HttpRequest req;
        if ("POST".equalsIgnoreCase(method)) {
            req = HttpRequest.post(base + path).body(json).header("Content-Type", "application/json");
        } else if ("PUT".equalsIgnoreCase(method)) {
            req = HttpRequest.put(base + path).body(json).header("Content-Type", "application/json");
        } else if ("PATCH".equalsIgnoreCase(method)) {
            req = HttpRequest.patch(base + path).body(json)
                    .header("Content-Type", "application/json-patch+json");
        } else {
            req = HttpRequest.get(base + path);
        }
        var resp = req.header("Authorization", "Bearer " + token)
                .timeout(20000)
                .execute();
        if (resp.getStatus() == 401 && retryOn401) {
            cachedToken = null;
            cachedAtMs = 0;
            String refreshed = loginAndSeedToken();
            if (StrUtil.isBlank(refreshed)) {
                throw new CommonException("OpenMetadata 鉴权失败 401，且无法用 email/password 刷新");
            }
            return authRequest(method, path, json, false);
        }
        if (resp.getStatus() >= 400) {
            throw new CommonException("OpenMetadata {} 失败 {}: {}", method, resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    private String encPath(String fqn) {
        return fqn == null ? "" : fqn.replace(" ", "%20");
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
