package vip.xiaonuo.lh.modular.moduleops.service;

import java.util.List;
import java.util.Map;

/**
 * 治理模块运维：连接参数（脱敏）+ 运行/探活状态
 */
public interface LhModuleOpsService {

    /** catalog | quality | lineage | all */
    List<Map<String, Object>> listModules(String module);

    Map<String, Object> detail(String module);
}
