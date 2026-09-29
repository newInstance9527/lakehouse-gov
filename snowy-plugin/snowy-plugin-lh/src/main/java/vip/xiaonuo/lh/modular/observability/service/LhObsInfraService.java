package vip.xiaonuo.lh.modular.observability.service;

import java.util.Map;

/**
 * 基础设施监控门面（§30）：无 VM/夜莺采集时合法空态，禁止演示数字。
 */
public interface LhObsInfraService {

    Map<String, Object> summary(String ws);

    Map<String, Object> nodes(String ws);

    Map<String, Object> procs(String ws);

    Map<String, Object> alerts(String ws);

    /** L1 容器（§30.5 二期；空态合法） */
    Map<String, Object> containers(String ws);

    /** L2 集群对象（§30.5 二期；空态合法） */
    Map<String, Object> cluster(String ws);

    /** 容量预测（§30.5 二期；空态合法） */
    Map<String, Object> capacityForecast(String ws);
}
