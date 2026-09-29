package vip.xiaonuo.lh.core.vault;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HashiCorp Vault KV v2 后端（波次 R4）。
 * <p>路径约定：门户 {@code vault_path}（如 {@code platform/trino/query}）映射为
 * {@code {addr}/v1/{kvMount}/data/{vault_path}}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "lh.vault", name = "backend", havingValue = "hashicorp")
public class LhHashicorpVaultStore implements LhVaultStore {

    @Resource
    private LhProperties lhProperties;

    private String addr() {
        String a = StrUtil.removeSuffix(StrUtil.blankToDefault(lhProperties.getVault().getAddr(), ""), "/");
        if (StrUtil.isBlank(a)) {
            throw new CommonException("lh.vault.addr 未配置（hashicorp 后端）");
        }
        return a;
    }

    private String token() {
        String t = lhProperties.getVault().getToken();
        if (StrUtil.isBlank(t)) {
            throw new CommonException("lh.vault.token 未配置（hashicorp 后端）");
        }
        return t;
    }

    private String mount() {
        return StrUtil.blankToDefault(lhProperties.getVault().getKvMount(), "secret");
    }

    private String dataUrl(String vaultPath) {
        String path = StrUtil.removePrefix(StrUtil.trim(vaultPath), "/");
        return addr() + "/v1/" + mount() + "/data/" + path;
    }

    private String metadataUrl(String vaultPath) {
        String path = StrUtil.removePrefix(StrUtil.trim(vaultPath), "/");
        return addr() + "/v1/" + mount() + "/metadata/" + path;
    }

    @Override
    public void write(String vaultPath, Map<String, Object> secret) {
        if (StrUtil.isBlank(vaultPath)) {
            throw new CommonException("vault_path 不能为空");
        }
        JSONObject body = JSONUtil.createObj().set("data", secret == null ? Map.of() : secret);
        HttpResponse resp = HttpRequest.post(dataUrl(vaultPath))
                .header("X-Vault-Token", token())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(timeoutMs())
                .execute();
        if (!resp.isOk()) {
            throw new CommonException("Vault 写入失败 {}: HTTP {} {}", vaultPath, resp.getStatus(), resp.body());
        }
    }

    @Override
    public Map<String, Object> read(String vaultPath) {
        HttpResponse resp = HttpRequest.get(dataUrl(vaultPath))
                .header("X-Vault-Token", token())
                .timeout(timeoutMs())
                .execute();
        if (resp.getStatus() == 404) {
            throw new CommonException("凭证不存在: {}", vaultPath);
        }
        if (!resp.isOk()) {
            throw new CommonException("Vault 读取失败 {}: HTTP {} {}", vaultPath, resp.getStatus(), resp.body());
        }
        JSONObject root = JSONUtil.parseObj(resp.body());
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return new LinkedHashMap<>();
        }
        JSONObject inner = data.getJSONObject("data");
        if (inner == null) {
            return new LinkedHashMap<>(data);
        }
        return new LinkedHashMap<>(inner);
    }

    @Override
    public Map<String, Object> readOrEmpty(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            return Collections.emptyMap();
        }
        try {
            if (!exists(vaultPath)) {
                return Collections.emptyMap();
            }
            return read(vaultPath);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean exists(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            return false;
        }
        HttpResponse resp = HttpRequest.get(dataUrl(vaultPath))
                .header("X-Vault-Token", token())
                .timeout(timeoutMs())
                .execute();
        return resp.isOk();
    }

    @Override
    public void delete(String vaultPath) {
        // metadata delete 永久删除 KV v2 路径
        HttpResponse resp = HttpRequest.delete(metadataUrl(vaultPath))
                .header("X-Vault-Token", token())
                .timeout(timeoutMs())
                .execute();
        if (!resp.isOk() && resp.getStatus() != 404) {
            throw new CommonException("Vault 删除失败 {}: HTTP {} {}", vaultPath, resp.getStatus(), resp.body());
        }
    }

    @Override
    public String backendId() {
        return "hashicorp";
    }

    private int timeoutMs() {
        int t = lhProperties.getVault().getTimeoutMs();
        return t > 0 ? t : 8000;
    }
}
