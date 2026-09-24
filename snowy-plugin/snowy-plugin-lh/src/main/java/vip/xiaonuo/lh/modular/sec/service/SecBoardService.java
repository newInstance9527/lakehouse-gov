package vip.xiaonuo.lh.modular.sec.service;

import java.util.List;
import java.util.Map;

/**
 * 安全中心运营台（门户 SoT：grant / mask / asset / 审计片段）
 */
public interface SecBoardService {

    Map<String, Object> overview(String ws);

    Map<String, Object> pageGrants(String ws, String q, long current, long size);

    Map<String, Object> pageMasks(String ws, String q, long current, long size);

    Map<String, Object> classification(String ws);

    Map<String, Object> pageAudit(String ws, String q, long current, long size);

    Map<String, Object> listSa();

    Map<String, Object> vaultHealth();

    Map<String, Object> routeWhitelist();
}
