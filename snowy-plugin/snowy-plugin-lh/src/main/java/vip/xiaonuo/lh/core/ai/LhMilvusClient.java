package vip.xiaonuo.lh.core.ai;

import cn.hutool.core.util.StrUtil;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Milvus 向量客户端（P1）。未启用 / 不可达时全部软失败，调用方降级关键词。
 * <p>连接失败（含 DEADLINE_EXCEEDED）后短退避，避免每请求重连刷 ERROR；检索/索引继续走关键词。
 */
@Component
public class LhMilvusClient {

    private static final Logger log = LoggerFactory.getLogger(LhMilvusClient.class);

    private static final String F_CHUNK_ID = "chunk_id";
    private static final String F_ENTRY_ID = "entry_id";
    private static final String F_WS = "ws";
    private static final String F_CAT = "cat";
    private static final String F_EMBEDDING = "embedding";

    /** 连接超时（SDK 默认 10s；不可达时过长且易刷日志） */
    private static final long CONNECT_TIMEOUT_SEC = 3L;
    /** 连接失败后最短重试间隔 */
    private static final long CONNECT_BACKOFF_MS = 60_000L;

    @Resource
    private LhProperties lhProperties;

    private volatile MilvusServiceClient client;
    private final AtomicBoolean collectionReady = new AtomicBoolean(false);
    /** 连接失败后禁止立即重连（epoch millis；0=可立即尝试） */
    private volatile long nextConnectAttemptAtMs = 0L;
    private final Object lock = new Object();

    /**
     * 配置开关 + URI 非空（不探测连通；连通见 {@link #probe()}）。
     * 连接失败退避期内仍返回 true（配置意图未变），实际 RPC 由 {@link #client()} 快速返回 null。
     */
    public boolean available() {
        LhProperties.Ai ai = lhProperties.getAi();
        return ai != null && ai.isMilvusEnabled() && StrUtil.isNotBlank(ai.getMilvusUri());
    }

    /** 当前是否处于连接失败退避（关键词降级，不重连） */
    public boolean connectBackoffActive() {
        return System.currentTimeMillis() < nextConnectAttemptAtMs;
    }

    /**
     * 运维探针：开关 / URI / 集合 / 是否可达。不可达时调用方应走关键词降级。
     * <p>返回字段：enabled、uri、database、collection、reachable、collectionExists、mode、message
     */
    public Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        LhProperties.Ai ai = lhProperties.getAi();
        boolean enabled = available();
        out.put("enabled", enabled);
        out.put("uri", enabled && ai != null ? StrUtil.nullToEmpty(ai.getMilvusUri()) : "");
        out.put("database", ai == null ? "default" : StrUtil.blankToDefault(ai.getMilvusDatabase(), "default"));
        out.put("collection", collectionName());
        if (!enabled) {
            out.put("reachable", false);
            out.put("collectionExists", false);
            out.put("mode", "keyword");
            out.put("message", "milvus-enabled=false 或 URI 空；检索走 MySQL 关键词");
            return out;
        }
        try {
            if (connectBackoffActive()) {
                out.put("reachable", false);
                out.put("collectionExists", false);
                out.put("mode", "keyword");
                out.put("message", "连接退避中（上次不可达）；降级关键词");
                return out;
            }
            MilvusServiceClient c = client();
            if (c == null) {
                out.put("reachable", false);
                out.put("collectionExists", false);
                out.put("mode", "keyword");
                out.put("message", "连接失败；降级关键词");
                return out;
            }
            R<Boolean> has = c.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName())
                    .build());
            boolean rpcOk = has != null && has.getStatus() == R.Status.Success.getCode();
            boolean exists = rpcOk && Boolean.TRUE.equals(has.getData());
            out.put("reachable", rpcOk);
            out.put("collectionExists", exists);
            out.put("mode", rpcOk ? "vector" : "keyword");
            out.put("message", rpcOk
                    ? (exists ? "OK" : "已连通；集合尚未创建（首次索引 ensure 后出现）")
                    : "RPC 失败；降级关键词");
            return out;
        } catch (Throwable t) {
            // 连接损坏时清掉，下次重连
            destroy();
            out.put("reachable", false);
            out.put("collectionExists", false);
            out.put("mode", "keyword");
            out.put("message", "探测异常；降级关键词");
            return out;
        }
    }

    /**
     * 确保集合存在（缺则创建 + 向量索引 + load）。失败返回 false。
     */
    public boolean ensureCollection(int dim) {
        if (!available()) {
            return false;
        }
        if (collectionReady.get()) {
            return true;
        }
        synchronized (lock) {
            if (collectionReady.get()) {
                return true;
            }
            try {
                MilvusServiceClient c = client();
                if (c == null) {
                    return false;
                }
                String name = collectionName();
                R<Boolean> has = c.hasCollection(HasCollectionParam.newBuilder()
                        .withCollectionName(name)
                        .build());
                if (has == null || has.getStatus() != R.Status.Success.getCode()) {
                    return false;
                }
                if (!Boolean.TRUE.equals(has.getData())) {
                    int useDim = dim > 0 ? dim : embedDim();
                    List<FieldType> fields = List.of(
                            FieldType.newBuilder()
                                    .withName(F_CHUNK_ID)
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(64)
                                    .withPrimaryKey(true)
                                    .withAutoID(false)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName(F_ENTRY_ID)
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(64)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName(F_WS)
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(64)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName(F_CAT)
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(32)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName(F_EMBEDDING)
                                    .withDataType(DataType.FloatVector)
                                    .withDimension(useDim)
                                    .build()
                    );
                    R<RpcStatus> created = c.createCollection(CreateCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .withDescription("lh kb chunk vectors")
                            .withFieldTypes(fields)
                            .build());
                    if (created == null || created.getStatus() != R.Status.Success.getCode()) {
                        return false;
                    }
                    R<RpcStatus> idx = c.createIndex(CreateIndexParam.newBuilder()
                            .withCollectionName(name)
                            .withFieldName(F_EMBEDDING)
                            .withIndexType(IndexType.AUTOINDEX)
                            .withMetricType(MetricType.COSINE)
                            .build());
                    if (idx == null || idx.getStatus() != R.Status.Success.getCode()) {
                        return false;
                    }
                }
                R<RpcStatus> load = c.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(name)
                        .build());
                if (load == null || load.getStatus() != R.Status.Success.getCode()) {
                    return false;
                }
                collectionReady.set(true);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** 单条 upsert；失败返回 false */
    public boolean upsert(String chunkId, String entryId, String ws, String cat, float[] vector) {
        if (!available() || StrUtil.isBlank(chunkId) || vector == null || vector.length == 0) {
            return false;
        }
        try {
            if (!ensureCollection(vector.length)) {
                return false;
            }
            MilvusServiceClient c = client();
            if (c == null) {
                return false;
            }
            List<Float> vec = toFloatList(vector);
            List<UpsertParam.Field> fields = List.of(
                    new UpsertParam.Field(F_CHUNK_ID, Collections.singletonList(chunkId)),
                    new UpsertParam.Field(F_ENTRY_ID, Collections.singletonList(StrUtil.nullToEmpty(entryId))),
                    new UpsertParam.Field(F_WS, Collections.singletonList(StrUtil.blankToDefault(ws, "default"))),
                    new UpsertParam.Field(F_CAT, Collections.singletonList(StrUtil.blankToDefault(cat, "faq"))),
                    new UpsertParam.Field(F_EMBEDDING, Collections.singletonList(vec))
            );
            R<MutationResult> r = c.upsert(UpsertParam.newBuilder()
                    .withCollectionName(collectionName())
                    .withFields(fields)
                    .build());
            return r != null && r.getStatus() == R.Status.Success.getCode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按 entry_id 删除向量；失败静默 */
    public boolean deleteByEntryId(String entryId) {
        if (!available() || StrUtil.isBlank(entryId)) {
            return false;
        }
        try {
            if (!ensureCollection(embedDim())) {
                return false;
            }
            MilvusServiceClient c = client();
            if (c == null) {
                return false;
            }
            String expr = F_ENTRY_ID + " == \"" + escapeExpr(entryId) + "\"";
            R<MutationResult> r = c.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName())
                    .withExpr(expr)
                    .build());
            return r != null && r.getStatus() == R.Status.Success.getCode();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 向量检索。返回 [{chunkId, entryId, score}, ...]；失败返回空列表。
     * {@code ws} 非空时按单空间过滤；{@code extraWs} 可并入（如公用知识 {@code _platform}）。
     */
    public List<Map<String, Object>> search(String ws, float[] query, int topK, List<String> cats) {
        return search(ws, null, query, topK, cats);
    }

    public List<Map<String, Object>> search(
            String ws, Collection<String> extraWs, float[] query, int topK, List<String> cats) {
        if (!available() || query == null || query.length == 0 || topK <= 0) {
            return List.of();
        }
        try {
            if (!ensureCollection(query.length)) {
                return List.of();
            }
            MilvusServiceClient c = client();
            if (c == null) {
                return List.of();
            }
            StringBuilder expr = new StringBuilder();
            java.util.LinkedHashSet<String> wsSet = new java.util.LinkedHashSet<>();
            if (StrUtil.isNotBlank(ws)) {
                wsSet.add(ws.trim());
            }
            if (extraWs != null) {
                for (String x : extraWs) {
                    if (StrUtil.isNotBlank(x)) {
                        wsSet.add(x.trim());
                    }
                }
            }
            if (wsSet.size() == 1) {
                expr.append(F_WS).append(" == \"").append(escapeExpr(wsSet.iterator().next())).append('"');
            } else if (wsSet.size() > 1) {
                String in = wsSet.stream()
                        .map(s -> "\"" + escapeExpr(s) + "\"")
                        .collect(Collectors.joining(", "));
                expr.append(F_WS).append(" in [").append(in).append(']');
            }
            if (cats != null && !cats.isEmpty()) {
                String in = cats.stream()
                        .filter(StrUtil::isNotBlank)
                        .map(s -> "\"" + escapeExpr(s.trim().toLowerCase(Locale.ROOT)) + "\"")
                        .collect(Collectors.joining(", "));
                if (StrUtil.isNotBlank(in)) {
                    if (!expr.isEmpty()) {
                        expr.append(" && ");
                    }
                    expr.append(F_CAT).append(" in [").append(in).append(']');
                }
            }
            SearchParam.Builder spb = SearchParam.newBuilder()
                    .withCollectionName(collectionName())
                    .withMetricType(MetricType.COSINE)
                    .withTopK(Math.max(1, Math.min(topK, 50)))
                    .withVectors(Collections.singletonList(toFloatList(query)))
                    .withVectorFieldName(F_EMBEDDING)
                    .withOutFields(List.of(F_CHUNK_ID, F_ENTRY_ID, F_CAT))
                    .withParams("{\"nprobe\":16}");
            if (!expr.isEmpty()) {
                spb.withExpr(expr.toString());
            }
            SearchParam param = spb.build();
            R<SearchResults> r = c.search(param);
            if (r == null || r.getStatus() != R.Status.Success.getCode() || r.getData() == null) {
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(r.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            if (scores == null || scores.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>(scores.size());
            for (SearchResultsWrapper.IDScore s : scores) {
                Map<String, Object> m = new LinkedHashMap<>();
                Object chunkId = s.get(F_CHUNK_ID);
                if (chunkId == null) {
                    chunkId = s.getStrID();
                }
                m.put("chunkId", chunkId == null ? null : String.valueOf(chunkId));
                Object entryId = s.get(F_ENTRY_ID);
                m.put("entryId", entryId == null ? null : String.valueOf(entryId));
                m.put("score", (double) s.getScore());
                out.add(m);
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    @PreDestroy
    public void destroy() {
        MilvusServiceClient c = client;
        client = null;
        collectionReady.set(false);
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // soft-fail
            }
        }
    }

    private MilvusServiceClient client() {
        MilvusServiceClient existing = client;
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        if (now < nextConnectAttemptAtMs) {
            return null;
        }
        synchronized (lock) {
            if (client != null) {
                return client;
            }
            if (System.currentTimeMillis() < nextConnectAttemptAtMs) {
                return null;
            }
            String uri = "";
            try {
                LhProperties.Ai ai = lhProperties.getAi();
                uri = ai.getMilvusUri().trim();
                ConnectParam.Builder b = ConnectParam.newBuilder()
                        .withUri(uri)
                        .withConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (StrUtil.isNotBlank(ai.getMilvusToken())) {
                    b.withToken(ai.getMilvusToken().trim());
                }
                if (StrUtil.isNotBlank(ai.getMilvusDatabase())) {
                    b.withDatabaseName(ai.getMilvusDatabase().trim());
                }
                client = new MilvusServiceClient(b.build());
                nextConnectAttemptAtMs = 0L;
                return client;
            } catch (Throwable t) {
                client = null;
                nextConnectAttemptAtMs = System.currentTimeMillis() + CONNECT_BACKOFF_MS;
                String err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                // 每次失败尝试最多一条 WARN（配合退避 ≈ 每分钟一次），避免每请求刷 ERROR
                log.warn("Milvus 不可达，检索/索引降级关键词（{}s 后重试）uri={} err={}",
                        CONNECT_BACKOFF_MS / 1000, uri, abbreviate(err, 240));
                return null;
            }
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private String collectionName() {
        return StrUtil.blankToDefault(lhProperties.getAi().getMilvusCollection(), "lh_kb_chunk");
    }

    private int embedDim() {
        int d = lhProperties.getAi().getEmbedDim();
        return d > 0 ? d : 1536;
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private static String escapeExpr(String raw) {
        return StrUtil.nullToEmpty(raw).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
