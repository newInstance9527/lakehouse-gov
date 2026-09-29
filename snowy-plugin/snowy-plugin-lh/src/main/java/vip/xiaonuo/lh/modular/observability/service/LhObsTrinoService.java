package vip.xiaonuo.lh.modular.observability.service;

import java.util.Map;

/**
 * 查询治理 Trino 门面（§24 · §36.2㉓）：包装 gov/overview + 审计。
 */
public interface LhObsTrinoService {

    Map<String, Object> queues(String ws);

    Map<String, Object> topUsers(String ws, String range);

    Map<String, Object> slow(String ws, String range, Integer limit);
}
