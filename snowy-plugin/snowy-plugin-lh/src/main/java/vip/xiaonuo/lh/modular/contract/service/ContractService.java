package vip.xiaonuo.lh.modular.contract.service;

import java.util.List;
import java.util.Map;

public interface ContractService {

    Map<String, Object> overview(String ws);

    Map<String, Object> listSchemas(String ws, String q);

    Map<String, Object> registerSchema(Map<String, Object> body);

    List<Map<String, Object>> schemaVersions(String name, String ws);

    Map<String, Object> listChanges(String ws, String status);

    Map<String, Object> createChange(Map<String, Object> body);

    Map<String, Object> changeAction(String id, String action, Map<String, Object> body);

    Map<String, Object> check(Map<String, Object> body);

    Map<String, Object> getCdcConfig(String topic, String ws);

    Map<String, Object> putCdcConfig(String topic, String ws, Map<String, Object> body);

    /**
     * ETL 发布门禁：对 DAG 涉及的表名做契约检查。
     * <p>仅当存在契约登记且（契约 status=fail 或存在未关闭的破坏性变更）时抛错；无契约不阻断。</p>
     *
     * @param ws        工作空间
     * @param tableFqns source/sink 表 FQN 或短名
     * @return 摘要（checked / blocked / notes）；未启用门禁时 checked=0
     */
    Map<String, Object> assertDeployAllowed(String ws, List<String> tableFqns);
}
