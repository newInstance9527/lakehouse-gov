package vip.xiaonuo.lh.modular.dataapi.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiKeyMeta;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiKeyMetaMapper;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 订阅 Key 签发与二次查看：平台 SoT = dataapi_api_key_meta + Vault。
 * <p>列表只返回脱敏元数据；完整 Bearer 经 {@link #revealSecret} 按权限从 Vault 读取并记审计。</p>
 */
@Service
public class DataapiKeyIssueService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private DataapiApiKeyMetaMapper keyMetaMapper;
    @Resource
    private DataapiApiBindingMapper bindingMapper;
    @Resource
    private LhVaultClient vaultClient;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> issueFromSubscribeTicket(ApplyTicket ticket) {
        if (ticket == null) {
            throw new CommonException("申请单为空");
        }
        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(ticket.getPayload(), "{}"));
        String bindingId = StrUtil.trim(payload.getStr("apiBindingId"));
        String publicPath = StrUtil.trim(payload.getStr("publicPath"));
        String method = StrUtil.blankToDefault(payload.getStr("method"), "GET").toUpperCase(Locale.ROOT);
        String consumer = StrUtil.blankToDefault(payload.getStr("consumerName"), ticket.getApplicant());
        int qps = payload.getInt("qps", 100);
        if (qps < 1) {
            qps = 1;
        }
        DataapiApiBinding binding = resolveBinding(bindingId, publicPath, method);
        if (binding == null) {
            throw new CommonException("订阅目标 API 绑定不存在：" + StrUtil.blankToDefault(publicPath, bindingId));
        }
        if (!"published".equalsIgnoreCase(binding.getState())) {
            throw new CommonException("仅已发布 API 可订阅，当前状态：" + binding.getState());
        }
        // 同申请单幂等
        DataapiApiKeyMeta exist = keyMetaMapper.selectOne(new QueryWrapper<DataapiApiKeyMeta>().lambda()
                .eq(DataapiApiKeyMeta::getTicketId, ticket.getId())
                .eq(DataapiApiKeyMeta::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (exist != null && "active".equalsIgnoreCase(exist.getStatus())) {
            return alreadyIssued(exist, binding);
        }

        String keyId = IdUtil.getSnowflakeNextIdStr();
        String appKey = "AK_" + RandomUtil.randomString(12).toUpperCase(Locale.ROOT);
        String secret = "sk_" + RandomUtil.randomString(40);
        String hint = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        String vaultPath = LhVaultPaths.dataapiKey(keyId);

        Map<String, Object> secretBody = new LinkedHashMap<>();
        secretBody.put("appKey", appKey);
        secretBody.put("token", secret);
        secretBody.put("bindingId", binding.getId());
        secretBody.put("publicPath", binding.getPublicPath());
        secretBody.put("method", binding.getMethod());
        secretBody.put("ticketId", ticket.getId());
        secretBody.put("ticketNo", ticket.getTicketNo());
        vaultClient.write(vaultPath, secretBody);

        DataapiApiKeyMeta meta = new DataapiApiKeyMeta();
        meta.setId(keyId);
        meta.setRevision(1);
        meta.setStatus("active");
        meta.setWs(StrUtil.blankToDefault(binding.getWs(), "default"));
        meta.setBindingId(binding.getId());
        meta.setConsumerName(consumer);
        meta.setApplicant(ticket.getApplicant());
        meta.setKeyHint(hint);
        meta.setVaultPath(vaultPath);
        meta.setTicketId(ticket.getId());
        meta.setExpireAt(ticket.getExpiresAt());
        meta.setQpsLimit(qps);
        meta.setAppKey(appKey);
        meta.setRemark("api_subscribe " + ticket.getTicketNo());
        meta.setDeleteFlag(NOT_DELETE);
        meta.setCreateTime(new Date());
        meta.setCreateUser(LhLoginUsers.requireUserId());
        keyMetaMapper.insert(meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyId", keyId);
        out.put("appKey", appKey);
        out.put("token", secret);
        out.put("keyHint", hint);
        out.put("qpsLimit", qps);
        out.put("bindingId", binding.getId());
        out.put("publicPath", binding.getPublicPath());
        out.put("method", binding.getMethod());
        out.put("vaultPath", vaultPath);
        out.put("expireAt", meta.getExpireAt());
        String path = StrUtil.blankToDefault(binding.getPublicPath(), "/api");
        out.put("curl", "curl -X POST \"{portal}/lh/dataapi/runtime/invoke\" "
                + "-H \"Content-Type: application/json\" "
                + "-H \"X-App-Key: " + appKey + "\" "
                + "-H \"Authorization: Bearer " + secret + "\" "
                + "-d \"{\\\"bindingId\\\":\\\"" + binding.getId() + "\\\",\\\"path\\\":\\\"" + path + "\\\"}\"");
        out.put("curlGateway", "curl -H \"X-App-Key: " + appKey + "\" -H \"Authorization: Bearer " + secret
                + "\" \"{gateway}" + path + "\"  # 直连 Gateway 旁路门户行级注入，仅受信网络");
        return out;
    }

    private Map<String, Object> alreadyIssued(DataapiApiKeyMeta exist, DataapiApiBinding binding) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyId", exist.getId());
        out.put("appKey", exist.getAppKey());
        out.put("token", null);
        out.put("keyHint", exist.getKeyHint());
        out.put("qpsLimit", exist.getQpsLimit());
        out.put("bindingId", binding.getId());
        out.put("publicPath", binding.getPublicPath());
        out.put("method", binding.getMethod());
        out.put("vaultPath", exist.getVaultPath());
        out.put("expireAt", exist.getExpireAt());
        out.put("message", "该申请单已签发过 Key；密文请在运行/API 详情中「查看密钥」，或重新申请");
        out.put("replay", true);
        return out;
    }

    /**
     * 按权限从 Vault 回显 Bearer 密文。允许：申请人 / 创建人 / 超管·业务管理员·数据运维。
     */
    public Map<String, Object> revealSecret(String keyId, String reason) {
        if (StrUtil.isBlank(keyId)) {
            throw new CommonException("订阅 Key id 不能为空");
        }
        DataapiApiKeyMeta meta = keyMetaMapper.selectById(keyId);
        if (meta == null || !NOT_DELETE.equals(meta.getDeleteFlag())) {
            throw new CommonException("订阅 Key 不存在：" + keyId);
        }
        assertCanReveal(meta);

        String vaultPath = StrUtil.blankToDefault(meta.getVaultPath(), LhVaultPaths.dataapiKey(meta.getId()));
        if (!vaultClient.exists(vaultPath)) {
            throw new CommonException("Vault 无密钥（未登记或已销毁）：" + vaultPath);
        }
        String token = vaultClient.getString(vaultPath, "token");
        if (StrUtil.isBlank(token)) {
            throw new CommonException("Vault 条目缺少 token：" + vaultPath);
        }
        String appKey = StrUtil.blankToDefault(vaultClient.getString(vaultPath, "appKey"), meta.getAppKey());
        String useReason = StrUtil.blankToDefault(StrUtil.trim(reason), "查看订阅密钥");
        if (useReason.length() > 200) {
            useReason = useReason.substring(0, 200);
        }

        SaBaseLoginUser user = LhLoginUsers.requireUser();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", meta.getId());
        out.put("appKey", appKey);
        out.put("token", token);
        out.put("keyHint", meta.getKeyHint());
        out.put("vaultPath", vaultPath);
        out.put("reason", useReason);
        out.put("operator", user.getId());
        out.put("operatorName", StrUtil.blankToDefault(user.getName(), user.getAccount()));
        out.put("revealedAt", new Date());
        return out;
    }

    private void assertCanReveal(DataapiApiKeyMeta meta) {
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        String uid = user.getId();
        if (LhLoginUsers.hasAnyRole("superAdmin", "bizAdmin", "dataOps")) {
            return;
        }
        if (StrUtil.isNotBlank(meta.getApplicant()) && meta.getApplicant().equals(uid)) {
            return;
        }
        if (StrUtil.isNotBlank(meta.getCreateUser()) && meta.getCreateUser().equals(uid)) {
            return;
        }
        throw new CommonException("无权查看该订阅密钥：仅申请人或管理员可二次查看");
    }

    private DataapiApiBinding resolveBinding(String bindingId, String publicPath, String method) {
        if (StrUtil.isNotBlank(bindingId)) {
            DataapiApiBinding byId = bindingMapper.selectById(bindingId);
            if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
                return byId;
            }
        }
        if (StrUtil.isBlank(publicPath)) {
            return null;
        }
        return bindingMapper.selectOne(new QueryWrapper<DataapiApiBinding>().lambda()
                .eq(DataapiApiBinding::getDeleteFlag, NOT_DELETE)
                .eq(DataapiApiBinding::getPublicPath, publicPath)
                .eq(DataapiApiBinding::getMethod, method)
                .eq(DataapiApiBinding::getState, "published")
                .last("LIMIT 1"));
    }

    /** 解析时效文案为 expireAt（与申请中心一致：N天 / 长期） */
    public static Date resolveExpireAt(String expireLabel) {
        if (StrUtil.isBlank(expireLabel) || expireLabel.contains("长期") || "forever".equalsIgnoreCase(expireLabel)) {
            return null;
        }
        String digits = expireLabel.replaceAll("[^0-9]", "");
        if (StrUtil.isBlank(digits)) {
            return null;
        }
        try {
            int days = Integer.parseInt(digits);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_MONTH, days);
            return c.getTime();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
