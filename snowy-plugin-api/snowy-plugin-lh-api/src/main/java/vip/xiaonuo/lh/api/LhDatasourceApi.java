package vip.xiaonuo.lh.api;

import java.util.Map;

/**
 * 数据源中心对外 API
 */
public interface LhDatasourceApi {

    /**
     * 作业侧解析数据源：返回脱敏元数据 + 短租凭证
     */
    Map<String, Object> resolveDs(String dsId, String mode);
}
