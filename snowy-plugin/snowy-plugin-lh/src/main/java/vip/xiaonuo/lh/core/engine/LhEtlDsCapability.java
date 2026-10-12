package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据源 typeCode ↔ ETL 编排能力（对齐 doc/ETL编排-跨模块待办.md §F）。
 * <p>ready=可真跑；partial=能选源但编译/插件未齐；planned=待加深；excluded=非编排抽取职责。</p>
 */
public final class LhEtlDsCapability {

    public enum Level {
        ready, partial, planned, excluded
    }

    public record Cap(
            String typeCode,
            Level ingest,
            Level export,
            List<String> sourceNodes,
            List<String> sinkNodes,
            String note
    ) {
    }

    private static final Map<String, Cap> BY_TYPE = new LinkedHashMap<>();

    static {
        // JDBC RDB
        put("mysql", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), "CDC/批入湖强；出湖 DataX");
        put("mariadb", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), "协议同 mysql");
        put("postgresql", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), null);
        put("pg", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), "postgresql 别名");
        put("oracle", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), null);
        put("sqlserver", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), null);
        put("azuresql", Level.ready, Level.partial, List.of("source"), List.of("sink_rdb"), "sqlserver 别名");
        // DW
        put("clickhouse", Level.ready, Level.ready, List.of("source"), List.of("sink_ck", "sink_rdb"), "批 JDBC 8123；汇 FLINK_STREAM");
        put("doris", Level.ready, Level.ready, List.of("source"), List.of("sink_rdb"),
                "入湖 JDBC；出湖 DataX doriswriter Stream Load");
        put("starrocks", Level.ready, Level.ready, List.of("source"), List.of("sink_rdb"),
                "入湖 JDBC；出湖 DataX starrockswriter Stream Load");
        put("hive", Level.partial, Level.planned, List.of("source"), List.of(), "jdbc:hive2；一般不写出 Hive");
        put("iceberg", Level.planned, Level.ready, List.of("source"), List.of("sink_iceberg"), "汇已强；读湖可选");
        put("hbase", Level.partial, Level.planned, List.of("source_hbase"), List.of(), "DataX hbasereader");
        put("trino", Level.excluded, Level.excluded, List.of(), List.of(), "查询网关，非编排抽取");
        // MQ
        put("kafka", Level.ready, Level.ready, List.of("source_kafka"), List.of("sink_kafka"), "FLINK_STREAM 折汇");
        put("pulsar", Level.ready, Level.planned, List.of("source_pulsar"), List.of("sink_kafka"), "SHELL 占位 + 下游 FLINK_STREAM");
        put("rabbitmq", Level.partial, Level.planned, List.of("source_rabbitmq"), List.of(),
                "专用算子 SHELL+Vault LH_RABBIT_*；真消费设 LH_RABBIT_CONSUMER_CMD");
        // NoSQL / search
        put("mongodb", Level.partial, Level.planned, List.of("source_mongo"), List.of(), "DataX mongodbreader");
        put("redis", Level.excluded, Level.excluded, List.of(), List.of(),
                "一般不入编排抽取（KV 缓存/会话）；合规删除与预览另有适配器");
        put("elasticsearch", Level.planned, Level.partial, List.of(), List.of("sink_search"), "汇 DataX ES writer");
        put("opensearch", Level.planned, Level.partial, List.of(), List.of("sink_search"), "注册禁别名；能力同 ES");
        // storage
        put("hdfs", Level.partial, Level.partial, List.of("source_file"), List.of("sink_object"), "DataX hdfswriter/reader");
        put("s3", Level.partial, Level.partial, List.of("source_file"), List.of("sink_object"), "MinIO/S3 → osswriter 族");
        put("minio", Level.partial, Level.partial, List.of("source_file"), List.of("sink_object"), "s3 别名");
        put("ftp", Level.partial, Level.partial, List.of("source_file"), List.of("sink_ftp"), "ftpreader/ftpwriter");
        put("sftp", Level.partial, Level.partial, List.of("source_file"), List.of("sink_ftp"), "ftp 别名");
        put("file", Level.partial, Level.partial, List.of("source_file"), List.of("sink_ftp"), "FTP/SFTP 登记码");
        // API / endpoint
        put("http_api", Level.partial, Level.excluded, List.of("source_api"), List.of(), "httpreader");
        put("tableau", Level.excluded, Level.partial, List.of(), List.of("sink_bi"), "endpoint；sink_bi SHELL 审计");
        put("superset", Level.excluded, Level.partial, List.of(), List.of("sink_bi"), "dataset 数字 id 可 refresh");
        put("airflow", Level.excluded, Level.excluded, List.of(), List.of(), "调度对端，非 ETL 源汇");
    }

    private LhEtlDsCapability() {
    }

    private static void put(String code, Level ingest, Level export,
                            List<String> sourceNodes, List<String> sinkNodes, String note) {
        BY_TYPE.put(code, new Cap(code, ingest, export, sourceNodes, sinkNodes, note));
    }

    public static Cap of(String typeCode) {
        String t = normalize(typeCode);
        if (t == null) {
            return null;
        }
        Cap c = BY_TYPE.get(t);
        if (c != null) {
            return c;
        }
        // 模糊：含关键字
        for (Map.Entry<String, Cap> e : BY_TYPE.entrySet()) {
            if (t.contains(e.getKey()) || e.getKey().contains(t)) {
                return e.getValue();
            }
        }
        return new Cap(t, Level.planned, Level.planned, List.of("source"), List.of("sink_rdb"),
                "未登记于能力矩阵，默认 planned");
    }

    public static List<Map<String, Object>> allAsMaps() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Cap c : BY_TYPE.values()) {
            list.add(toMap(c));
        }
        return list;
    }

    public static Map<String, Object> toMap(Cap c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typeCode", c.typeCode());
        m.put("ingest", c.ingest().name());
        m.put("export", c.export().name());
        m.put("sourceNodes", c.sourceNodes());
        m.put("sinkNodes", c.sinkNodes());
        if (StrUtil.isNotBlank(c.note())) {
            m.put("note", c.note());
        }
        return m;
    }

    /** 节点是否允许绑定该 type（excluded 或角色不匹配 → false）。 */
    public static boolean allowsBind(String typeCode, String nodeType) {
        Cap c = of(typeCode);
        if (c == null || StrUtil.isBlank(nodeType)) {
            return true;
        }
        if (IgEtlNodeTypes.SOURCE.contains(nodeType)) {
            if (c.ingest() == Level.excluded) {
                return false;
            }
            if (c.sourceNodes().isEmpty()) {
                return false;
            }
            return c.sourceNodes().contains(nodeType)
                    || softMatchSource(nodeType, c);
        }
        if (nodeType.startsWith("sink_")) {
            if (c.export() == Level.excluded) {
                return false;
            }
            if (c.sinkNodes().isEmpty()) {
                return false;
            }
            return c.sinkNodes().contains(nodeType) || softMatchSink(nodeType, c);
        }
        return true;
    }

    private static boolean softMatchSource(String nodeType, Cap c) {
        // source_file 可绑 storage；source_api 可绑 http
        if ("source_file".equals(nodeType)) {
            return c.sourceNodes().contains("source_file");
        }
        if ("source_api".equals(nodeType)) {
            return c.sourceNodes().contains("source_api");
        }
        if ("source_kafka".equals(nodeType)) {
            return c.sourceNodes().contains("source_kafka");
        }
        return false;
    }

    private static boolean softMatchSink(String nodeType, Cap c) {
        return c.sinkNodes().contains(nodeType);
    }

    /** 校验提示：返回 null 表示无问题；否则 warn/error 文案。 */
    public static String bindAdvice(String typeCode, String nodeType) {
        Cap c = of(typeCode);
        if (c == null || StrUtil.isBlank(nodeType)) {
            return null;
        }
        boolean sourceSide = IgEtlNodeTypes.SOURCE.contains(nodeType);
        boolean sinkSide = nodeType.startsWith("sink_");
        if (!sourceSide && !sinkSide) {
            return null;
        }
        Level lv = sourceSide ? c.ingest() : c.export();
        if (lv == Level.excluded) {
            return "数据源类型 " + c.typeCode() + " 不用于 ETL "
                    + (sourceSide ? "入湖" : "出湖") + "（" + StrUtil.blankToDefault(c.note(), "excluded") + "）";
        }
        if (!allowsBind(typeCode, nodeType)) {
            List<String> prefer = sourceSide ? c.sourceNodes() : c.sinkNodes();
            return "类型 " + c.typeCode() + " 建议挂在 "
                    + (prefer.isEmpty() ? "其它节点" : String.join("/", prefer))
                    + "，当前节点 " + nodeType + " 不匹配";
        }
        if (lv == Level.planned) {
            return "类型 " + c.typeCode() + " ETL 能力尚为 planned（"
                    + StrUtil.blankToDefault(c.note(), "编译/插件待加深") + "）";
        }
        if (lv == Level.partial) {
            return "类型 " + c.typeCode() + " ETL 能力 partial（"
                    + StrUtil.blankToDefault(c.note(), "可发布但插件/方言可能不全") + "）";
        }
        return null;
    }

    public static Set<String> ingestableTypeCodes() {
        Set<String> s = new java.util.LinkedHashSet<>();
        for (Cap c : BY_TYPE.values()) {
            if (c.ingest() != Level.excluded) {
                s.add(c.typeCode());
            }
        }
        return s;
    }

    /** 能力矩阵已显式声明的 typeCode（含别名）。 */
    public static Set<String> declaredTypeCodes() {
        return java.util.Collections.unmodifiableSet(BY_TYPE.keySet());
    }

    public static boolean isDeclared(String typeCode) {
        String t = normalize(typeCode);
        return t != null && BY_TYPE.containsKey(t);
    }

    private static String normalize(String typeCode) {
        if (StrUtil.isBlank(typeCode)) {
            return null;
        }
        return typeCode.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("/", "_");
    }
}
