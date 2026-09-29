package vip.xiaonuo.lh.modular.observability.service;

import java.util.Map;

/**
 * 任务运维门面（§12 · §36.2⑱）：聚合 ETL / recon，动作走官方 ETL API。
 */
public interface LhObsTasksService {

    /** group=all|etl|flink|ds|recon|quality */
    Map<String, Object> listTasks(String group, String ws);

    /** pause|resume|rerun|backfill */
    Map<String, Object> action(String id, Map<String, Object> body);

    /** 按血缘出下游补数工单 */
    Map<String, Object> rerunDownstream(String id, Map<String, Object> body);

    Map<String, Object> slaSummary(String ws);
}
