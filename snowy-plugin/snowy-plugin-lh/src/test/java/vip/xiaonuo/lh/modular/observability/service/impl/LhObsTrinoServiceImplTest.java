package vip.xiaonuo.lh.modular.observability.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LhObsTrinoServiceImplTest {

    @Test
    void mapSourceToQueue() {
        Assertions.assertEquals("dashboard", LhObsTrinoServiceImpl.mapSourceToQueue("superset-dashboard"));
        Assertions.assertEquals("etl", LhObsTrinoServiceImpl.mapSourceToQueue("ds-etl-batch"));
        Assertions.assertEquals("etl", LhObsTrinoServiceImpl.mapSourceToQueue("flink-job"));
        Assertions.assertEquals("adhoc", LhObsTrinoServiceImpl.mapSourceToQueue("lakehouse-portal"));
        Assertions.assertEquals("adhoc", LhObsTrinoServiceImpl.mapSourceToQueue(null));
    }
}
