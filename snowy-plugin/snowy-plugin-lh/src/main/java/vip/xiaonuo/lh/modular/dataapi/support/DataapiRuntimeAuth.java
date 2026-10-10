package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiKeyMeta;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiKeyMetaMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

/**
 * 数据服务运行时门面鉴权：订阅 {@code X-App-Key} + Bearer → 绑定与行级主体。
 */
@Component
public class DataapiRuntimeAuth {

    public static final String HEADER_APP_KEY = "X-App-Key";

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private DataapiApiKeyMetaMapper keyMetaMapper;
    @Resource
    private DataapiApiBindingMapper bindingMapper;
    @Resource
    private LhVaultClient vaultClient;

    /**
     * @param appKey     请求头 X-App-Key
     * @param bearer     Authorization Bearer 密文（可带或不带 "Bearer " 前缀）
     * @param bindingId  可选：须与 Key 绑定一致
     * @param publicPath 可选：须与绑定 publicPath 一致（在未传 bindingId 时亦可用于解析）
     */
    public AuthResult authenticate(String appKey, String bearer, String bindingId, String publicPath) {
        if (StrUtil.isBlank(appKey)) {
            throw new CommonException("缺少 " + HEADER_APP_KEY);
        }
        String token = stripBearer(bearer);
        if (StrUtil.isBlank(token)) {
            throw new CommonException("缺少 Authorization Bearer");
        }

        DataapiApiKeyMeta meta = keyMetaMapper.selectOne(new QueryWrapper<DataapiApiKeyMeta>().lambda()
                .eq(DataapiApiKeyMeta::getAppKey, appKey.trim())
                .eq(DataapiApiKeyMeta::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (meta == null) {
            throw new CommonException("无效的 App-Key");
        }
        if (!"active".equalsIgnoreCase(StrUtil.blankToDefault(meta.getStatus(), ""))) {
            throw new CommonException("订阅 Key 已失效：" + meta.getStatus());
        }
        if (meta.getExpireAt() != null && meta.getExpireAt().before(new Date())) {
            throw new CommonException("订阅 Key 已过期");
        }

        String vaultPath = StrUtil.blankToDefault(meta.getVaultPath(), LhVaultPaths.dataapiKey(meta.getId()));
        if (!vaultClient.exists(vaultPath)) {
            throw new CommonException("Vault 无密钥（未登记或已销毁）");
        }
        String expected = vaultClient.getString(vaultPath, "token");
        if (!tokenMatches(expected, token)) {
            throw new CommonException("Bearer 校验失败");
        }

        if (StrUtil.isBlank(meta.getBindingId())) {
            throw new CommonException("订阅 Key 未绑定 API");
        }
        if (StrUtil.isNotBlank(bindingId) && !meta.getBindingId().equals(bindingId.trim())) {
            throw new CommonException("App-Key 与请求 bindingId 不匹配");
        }

        DataapiApiBinding binding = bindingMapper.selectById(meta.getBindingId());
        if (binding == null || !NOT_DELETE.equals(binding.getDeleteFlag())) {
            throw new CommonException("订阅目标 API 绑定不存在");
        }
        if (!"published".equalsIgnoreCase(StrUtil.blankToDefault(binding.getState(), ""))) {
            throw new CommonException("仅已发布 API 可经运行时门面调用，当前：" + binding.getState());
        }
        if (StrUtil.isNotBlank(publicPath)) {
            String want = normalizePath(publicPath);
            String have = normalizePath(binding.getPublicPath());
            if (!want.equals(have)) {
                throw new CommonException("App-Key 与请求 path 不匹配");
            }
        }

        String subjectId = StrUtil.blankToDefault(meta.getApplicant(), meta.getCreateUser());
        if (StrUtil.isBlank(subjectId)) {
            throw new CommonException("订阅 Key 无申请人主体，无法注入行级策略");
        }

        AuthResult r = new AuthResult();
        r.meta = meta;
        r.binding = binding;
        r.subjectId = subjectId.trim();
        return r;
    }

    public static String stripBearer(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return s.substring(7).trim();
        }
        return s;
    }

    public static String normalizePath(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static boolean tokenMatches(String expected, String presented) {
        if (StrUtil.isBlank(expected) || StrUtil.isBlank(presented)) {
            return false;
        }
        byte[] a = expected.trim().getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.trim().getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }

    public static final class AuthResult {
        public DataapiApiKeyMeta meta;
        public DataapiApiBinding binding;
        public String subjectId;
    }
}
