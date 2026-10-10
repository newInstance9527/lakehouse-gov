package vip.xiaonuo.lh.modular.observability.service;

import java.util.List;
import java.util.Map;

public interface LhObsSpanService {

    Map<String, Object> linksOverview(String ws);

    Map<String, Object> listSpans(String ws, String linkId, String traceId, String runId, String eventId,
                                 String status, long current, long size);

    Map<String, Object> trace(String traceId, String ws);

    Map<String, Object> searchLogs(String ws, String q, String traceId, String runId, String eventId,
                                  long current, long size);

    Map<String, Object> spanError(String spanId, String ws);

    /** 批量/单条写入 gov_obs_span；body.spans 或单对象 */
    Map<String, Object> ingest(Map<String, Object> body);

    /** ETL/质量失败同源写 span（soft-fail 调用方） */
    void recordComponentSpan(String ws, String linkId, String service, String op, String status,
                             String runId, String eventId, String error, String attrsJson);

    /**
     * 带 {@code traceId} 写 span（合规链路 K：trace_id=工单号）。
     * {@code traceId} 为空时回退为自动生成。
     */
    void recordComponentSpan(String ws, String linkId, String service, String op, String status,
                             String runId, String eventId, String error, String attrsJson, String traceId);
}
