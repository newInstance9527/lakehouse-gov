package vip.xiaonuo.lh.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhEtlDsCapabilityTest {

    @Test
    void kafka_ready_on_kafka_nodes() {
        assertTrue(LhEtlDsCapability.allowsBind("kafka", "source_kafka"));
        assertTrue(LhEtlDsCapability.allowsBind("kafka", "sink_kafka"));
        assertFalse(LhEtlDsCapability.allowsBind("kafka", "source"));
        assertEquals(LhEtlDsCapability.Level.ready, LhEtlDsCapability.of("kafka").ingest());
    }

    @Test
    void trino_excluded_from_etl() {
        assertFalse(LhEtlDsCapability.allowsBind("trino", "source"));
        assertFalse(LhEtlDsCapability.allowsBind("trino", "sink_rdb"));
        String advice = LhEtlDsCapability.bindAdvice("trino", "source");
        assertTrue(advice != null && advice.contains("不用于"), advice);
    }

    @Test
    void elasticsearch_binds_sink_search() {
        assertTrue(LhEtlDsCapability.allowsBind("elasticsearch", "sink_search"));
        assertFalse(LhEtlDsCapability.allowsBind("elasticsearch", "sink_rdb"));
    }

    @Test
    void allAsMaps_covers_registerable_core() {
        assertTrue(LhEtlDsCapability.allAsMaps().size() >= 20);
        assertTrue(LhEtlDsCapability.ingestableTypeCodes().contains("mysql"));
        assertFalse(LhEtlDsCapability.ingestableTypeCodes().contains("trino"));
    }
}
