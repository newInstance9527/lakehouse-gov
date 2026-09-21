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
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Milvus 向量客户端（P1）。未启用 / 不可达时全部软失败，调用方降级关键词。
 */
@Component
public class LhMilvusClient {

    private static final String F_CHUNK_ID = "chunk_id";
    private static final String F_ENTRY_ID = "entry_id";
    private static final String F_WS = "ws";
    private static final String F_CAT = "cat";
    private static final String F_EMBEDDING = "embedding";

    @Resource
    private LhProperties lhProperties;

    private volatile MilvusServiceClient client;
    private final AtomicBoolean collectionReady = new AtomicBoolean(false);
    private final Object lock = new Object();

    /** 配置开关 + URI 非空 */
    public boolean available() {
        LhProperties.Ai ai = lhProperties.getAi();
        return ai != null && ai.isMilvusEnabled() && StrUtil.isNotBlank(ai.getMilvusUri());
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
     */
    public List<Map<String, Object>> search(String ws, float[] query, int topK, List<String> cats) {
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
            expr.append(F_WS).append(" == \"").append(escapeExpr(StrUtil.blankToDefault(ws, "default"))).append('"');
            if (cats != null && !cats.isEmpty()) {
                String in = cats.stream()
                        .filter(StrUtil::isNotBlank)
                        .map(s -> "\"" + escapeExpr(s.trim().toLowerCase(Locale.ROOT)) + "\"")
                        .collect(Collectors.joining(", "));
                if (StrUtil.isNotBlank(in)) {
                    expr.append(" && ").append(F_CAT).append(" in [").append(in).append(']');
                }
            }
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collectionName())
                    .withMetricType(MetricType.COSINE)
                    .withTopK(Math.max(1, Math.min(topK, 50)))
                    .withVectors(Collections.singletonList(toFloatList(query)))
                    .withVectorFieldName(F_EMBEDDING)
                    .withExpr(expr.toString())
                    .withOutFields(List.of(F_CHUNK_ID, F_ENTRY_ID, F_CAT))
                    .withParams("{\"nprobe\":16}")
                    .build();
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
        synchronized (lock) {
            if (client != null) {
                return client;
            }
            try {
                LhProperties.Ai ai = lhProperties.getAi();
                ConnectParam.Builder b = ConnectParam.newBuilder()
                        .withUri(ai.getMilvusUri().trim());
                if (StrUtil.isNotBlank(ai.getMilvusToken())) {
                    b.withToken(ai.getMilvusToken().trim());
                }
                if (StrUtil.isNotBlank(ai.getMilvusDatabase())) {
                    b.withDatabaseName(ai.getMilvusDatabase().trim());
                }
                client = new MilvusServiceClient(b.build());
                return client;
            } catch (Throwable t) {
                client = null;
                return null;
            }
        }
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
