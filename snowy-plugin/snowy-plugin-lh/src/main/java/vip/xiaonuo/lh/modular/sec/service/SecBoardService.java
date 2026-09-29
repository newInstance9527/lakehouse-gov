package vip.xiaonuo.lh.modular.sec.service;

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

    Map<String, Object> listSa(String ws);

    /** 注册作业 SA：写 sec_job_sa + Vault 动态种子（无明文回传） */
    Map<String, Object> registerSa(vip.xiaonuo.lh.modular.sec.param.SecJobSaRegisterParam param);

    /** 退役作业 SA（软删） */
    void retireSa(String id);

    /** Vault 凭证健康/轮换台账（ig_secret_store；无明文） */
    Map<String, Object> vaultHealth();

    /**
     * 本地动态密轮换：生成新密、previous* 宽限期；数据源另置 binding stale。
     */
    Map<String, Object> rotateVault(String vaultPath);

    Map<String, Object> routeWhitelist();
}
