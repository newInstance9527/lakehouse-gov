package vip.xiaonuo.lh.core.engine;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * F10：可登记 typeCode ×（源|汇）冒烟对账。
 * <p>对齐 {@link LhDatasourceTypeEnum}（typeOptions 编码）与 {@link LhEtlDsCapability}；
 * 推荐算子须落在 {@link IgEtlNodeTypes}。</p>
 */
class EtlDsCoverageSmokeTest {

    /** 与前端 NODE_TYPES / IgEtlNodeTypes.ALL 同步；增删算子时改此断言。 */
    private static final int EXPECTED_NODE_TYPE_COUNT = 23;

    @Test
    void nodeTypes_count_is_23() {
        assertEquals(EXPECTED_NODE_TYPE_COUNT, IgEtlNodeTypes.ALL.size());
        assertEquals(EXPECTED_NODE_TYPE_COUNT, EngineResolver.NODE_TYPES.size());
        assertEquals(8, IgEtlNodeTypes.SOURCE.size());
        assertEquals(8, IgEtlNodeTypes.SINK.size());
    }

    @Test
    void every_enum_type_is_declared_in_capability() {
        List<String> missing = new ArrayList<>();
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if (!LhEtlDsCapability.isDeclared(e.getValue())) {
                missing.add(e.getValue());
            }
        }
        assertTrue(missing.isEmpty(), "LhEtlDsCapability 缺枚举 typeCode: " + missing);
    }

    @Test
    void preferred_nodes_are_known_and_bindable() {
        List<String> errors = new ArrayList<>();
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            LhEtlDsCapability.Cap cap = LhEtlDsCapability.of(e.getValue());
            for (String n : cap.sourceNodes()) {
                if (!IgEtlNodeTypes.isKnown(n)) {
                    errors.add(e.getValue() + " sourceNode unknown: " + n);
                } else if (cap.ingest() != LhEtlDsCapability.Level.excluded
                        && !LhEtlDsCapability.allowsBind(e.getValue(), n)) {
                    errors.add(e.getValue() + " cannot bind source " + n);
                }
            }
            for (String n : cap.sinkNodes()) {
                if (!IgEtlNodeTypes.isKnown(n)) {
                    errors.add(e.getValue() + " sinkNode unknown: " + n);
                } else if (cap.export() != LhEtlDsCapability.Level.excluded
                        && !LhEtlDsCapability.allowsBind(e.getValue(), n)) {
                    errors.add(e.getValue() + " cannot bind sink " + n);
                }
            }
            if (cap.ingest() == LhEtlDsCapability.Level.excluded) {
                assertFalse(LhEtlDsCapability.allowsBind(e.getValue(), "source"),
                        e.getValue() + " excluded ingest should reject source");
            }
            if (cap.export() == LhEtlDsCapability.Level.excluded) {
                assertFalse(LhEtlDsCapability.allowsBind(e.getValue(), "sink_rdb"),
                        e.getValue() + " excluded export should reject sink_rdb");
            }
        }
        if (!errors.isEmpty()) {
            fail(String.join("\n", errors));
        }
    }

    @Test
    void smoke_matrix_rows_cover_typeOptions_codes() {
        // typeOptions 回落枚举时跳过 pg 别名展示；能力矩阵仍须含 pg
        Set<String> typeOptionCodes = new LinkedHashSet<>();
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if ("pg".equals(e.getValue())) {
                continue;
            }
            typeOptionCodes.add(e.getValue());
        }
        assertTrue(typeOptionCodes.size() >= 24, "typeOptions codes: " + typeOptionCodes.size());

        List<String> rows = new ArrayList<>();
        for (String code : typeOptionCodes) {
            LhEtlDsCapability.Cap c = LhEtlDsCapability.of(code);
            String src = c.sourceNodes().isEmpty() ? "—" : String.join("/", c.sourceNodes());
            String sink = c.sinkNodes().isEmpty() ? "—" : String.join("/", c.sinkNodes());
            rows.add(code + "\tingest=" + c.ingest() + "\texport=" + c.export()
                    + "\tsrc=" + src + "\tsink=" + sink);
            assertTrue(LhEtlDsCapability.isDeclared(code), code);
        }
        assertEquals(typeOptionCodes.size(), rows.size());
        // ready/partial 必须挂算子；planned+空列表表示「该侧暂不做」（如 Hive 不写出）
        for (String code : typeOptionCodes) {
            LhEtlDsCapability.Cap c = LhEtlDsCapability.of(code);
            if (c.ingest() == LhEtlDsCapability.Level.ready
                    || c.ingest() == LhEtlDsCapability.Level.partial) {
                assertFalse(c.sourceNodes().isEmpty(), code + " ingest " + c.ingest() + " needs sourceNodes");
            }
            if (c.export() == LhEtlDsCapability.Level.ready
                    || c.export() == LhEtlDsCapability.Level.partial) {
                assertFalse(c.sinkNodes().isEmpty(), code + " export " + c.export() + " needs sinkNodes");
            }
        }
    }

    @Test
    void alias_codes_declared() {
        for (String alias : List.of("mariadb", "azuresql", "minio", "sftp", "opensearch", "pg")) {
            assertTrue(LhEtlDsCapability.isDeclared(alias), alias);
        }
    }
}
