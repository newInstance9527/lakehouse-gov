package vip.xiaonuo.lh.modular.export.service;

import java.util.Map;

/**
 * 出湖到期停作业（A6）
 */
public interface ExportLifecycleService {

    /**
     * 扫描 expires_at 已过的 approved lake_export：
     * 暂停挂接 ETL DAG（门户 paused + DS OFFLINE）→ 申请标 expired → 写审计 + 删副本通知。
     */
    Map<String, Object> expireDue();
}
