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
}
