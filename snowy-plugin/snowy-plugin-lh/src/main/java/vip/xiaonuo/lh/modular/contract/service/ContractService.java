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
}
