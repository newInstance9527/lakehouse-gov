package vip.xiaonuo.lh.core.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ETL 18 类节点（与前端 NODE_TYPES 同名，禁止别名）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
public final class IgEtlNodeTypes {

    private IgEtlNodeTypes() {
    }

    public static final List<String> ALL = List.of(
            "source", "source_api", "source_file",
            "clean", "transform", "mapping",
            "quality", "parallel", "condition", "union",
            "sink_iceberg", "sink_ck", "sink_kafka",
            "sink_object", "sink_ftp", "sink_rdb", "sink_search", "sink_bi"
    );

    public static final Set<String> SOURCE = Set.of("source", "source_api", "source_file");
    public static final Set<String> SINK = Set.of(
            "sink_iceberg", "sink_ck", "sink_kafka",
            "sink_object", "sink_ftp", "sink_rdb", "sink_search", "sink_bi"
    );
    public static final Set<String> PROCESS = Set.of("clean", "transform", "mapping");
    public static final Set<String> CONTROL = Set.of("quality", "parallel", "condition", "union");

    public static final List<Map<String, Object>> GROUPS = List.of(
            Map.of("key", "source", "label", "数据源"),
            Map.of("key", "process", "label", "清洗转换"),
            Map.of("key", "control", "label", "控制"),
            Map.of("key", "sink", "label", "目标 / 出湖")
    );

    public static final Map<String, Map<String, Object>> META = Map.ofEntries(
            entry("source", "CDC / 库表", "source", List.of("out")),
            entry("source_api", "API 抽取", "source", List.of("out")),
            entry("source_file", "文件 / FTP", "source", List.of("out")),
            entry("clean", "清洗规则", "process", List.of("in", "out")),
            entry("transform", "转换 / SQL", "process", List.of("in", "out")),
            entry("mapping", "字段映射", "process", List.of("in", "out")),
            entry("quality", "质量门禁", "control", List.of("in", "out")),
            entry("parallel", "并行分支", "control", List.of("in", "out")),
            entry("condition", "条件分支", "control", List.of("in", "out")),
            entry("union", "合并 UNION", "control", List.of("in", "out")),
            entry("sink_iceberg", "Iceberg", "sink", List.of("in")),
            entry("sink_ck", "ClickHouse", "sink", List.of("in")),
            entry("sink_kafka", "Kafka", "sink", List.of("in")),
            entry("sink_object", "对象存储", "sink", List.of("in")),
            entry("sink_ftp", "FTP/SFTP", "sink", List.of("in")),
            entry("sink_rdb", "关系库", "sink", List.of("in")),
            entry("sink_search", "搜索引擎", "sink", List.of("in")),
            entry("sink_bi", "BI / 出湖", "sink", List.of("in"))
    );

    private static Map.Entry<String, Map<String, Object>> entry(String type, String label, String group, List<String> ports) {
        return Map.entry(type, Map.of("type", type, "label", label, "group", group, "ports", ports));
    }

    public static boolean isKnown(String type) {
        return type != null && ALL.contains(type);
    }
}
