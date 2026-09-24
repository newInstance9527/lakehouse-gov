package vip.xiaonuo.lh.core.idempotency;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.idempotency.entity.LhApiIdempotency;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 写操作幂等：同 scope+用户+Idempotency-Key 复放首次成功响应。
 * <p>键来源：入参 {@code idempotencyKey} 或请求头 {@code Idempotency-Key} / {@code X-Idempotency-Key}。
 * 无键则不启用守卫（保持旧行为）。
 */
@Service
public class LhIdempotencyGuard {

    public static final String SCOPE_APPLY_TICKET = "apply_ticket";
    public static final String SCOPE_RELEASE_CREATE = "release_create";
    public static final String SCOPE_RELEASE_PUBLISH = "release_publish";
    public static final String SCOPE_CONTRACT_CHANGE = "contract_change";
    public static final String SCOPE_CONTRACT_SCHEMA = "contract_schema";

    private static final String ST_PROCESSING = "processing";
    private static final String ST_COMPLETED = "completed";
    private static final String ST_FAILED = "failed";

    @Resource
    private LhIdempotencyStore store;

    public static String resolveKey(String bodyKey) {
        if (StrUtil.isNotBlank(bodyKey)) {
            return bodyKey.trim();
        }
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String h = req.getHeader("Idempotency-Key");
            if (StrUtil.isBlank(h)) {
                h = req.getHeader("X-Idempotency-Key");
            }
            return StrUtil.trim(h);
        } catch (Exception e) {
            return null;
        }
    }

    public static String hashPayload(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            if (payload instanceof Map<?, ?> map) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if ("idempotencyKey".equalsIgnoreCase(k) || "idempotency_key".equalsIgnoreCase(k)) {
                        continue;
                    }
                    copy.put(k, e.getValue());
                }
                return DigestUtil.sha256Hex(JSONUtil.toJsonStr(copy));
            }
            // 实体：去掉幂等键再摘要，避免同体异键误判
            cn.hutool.json.JSONObject obj = JSONUtil.parseObj(JSONUtil.toJsonStr(payload));
            obj.remove("idempotencyKey");
            obj.remove("idempotency_key");
            return DigestUtil.sha256Hex(obj.toString());
        } catch (Exception e) {
            return DigestUtil.sha256Hex(String.valueOf(payload));
        }
    }

    public <T> T run(String scope, String idemKey, String reqHash,
                     Supplier<T> action,
                     String resourceType,
                     Function<T, String> resourceIdOf,
                     Class<T> resultType) {
        String key = resolveKey(idemKey);
        if (StrUtil.isBlank(key)) {
            return action.get();
        }
        String subject = safeSubject();

        for (int attempt = 0; attempt < 2; attempt++) {
            LhApiIdempotency existing = store.find(scope, subject, key);
            if (existing != null && !LhIdempotencyStore.expired(existing)) {
                if (ST_FAILED.equals(existing.getStatus())) {
                    store.retire(existing);
                    continue;
                }
                return replayOrBusy(existing, reqHash, resultType);
            }
            if (existing != null && LhIdempotencyStore.expired(existing)) {
                store.retire(existing);
            }

            LhApiIdempotency row = store.tryBegin(scope, subject, key, reqHash);
            if (row == null) {
                LhApiIdempotency raced = store.find(scope, subject, key);
                if (raced == null) {
                    throw new CommonException("幂等记录冲突，请重试");
                }
                if (ST_FAILED.equals(raced.getStatus())) {
                    store.retire(raced);
                    continue;
                }
                return replayOrBusy(raced, reqHash, resultType);
            }

            try {
                T result = action.get();
                store.markCompleted(row.getId(), resourceType,
                        resourceIdOf == null ? null : resourceIdOf.apply(result),
                        JSONUtil.toJsonStr(result));
                return result;
            } catch (RuntimeException e) {
                store.markFailed(row.getId(), e.getMessage());
                throw e;
            }
        }
        throw new CommonException("幂等处理失败，请更换 Idempotency-Key 后重试");
    }

    private <T> T replayOrBusy(LhApiIdempotency existing, String reqHash, Class<T> resultType) {
        if (StrUtil.isNotBlank(reqHash)
                && StrUtil.isNotBlank(existing.getReqHash())
                && !reqHash.equalsIgnoreCase(existing.getReqHash())) {
            throw new CommonException("幂等键已使用且请求体不一致，请更换 Idempotency-Key");
        }
        if (ST_PROCESSING.equals(existing.getStatus())) {
            throw new CommonException("相同幂等键的请求正在处理中，请稍后重试");
        }
        if (!ST_COMPLETED.equals(existing.getStatus()) || StrUtil.isBlank(existing.getResponseJson())) {
            throw new CommonException("幂等复放失败：无缓存响应");
        }
        try {
            if (Map.class.isAssignableFrom(resultType)) {
                return (T) JSONUtil.parseObj(existing.getResponseJson());
            }
            return JSONUtil.toBean(existing.getResponseJson(), resultType);
        } catch (Exception e) {
            throw new CommonException("幂等复放反序列化失败: " + e.getMessage());
        }
    }

    private static String safeSubject() {
        try {
            return StrUtil.blankToDefault(LhLoginUsers.requireUserId(), "");
        } catch (Exception e) {
            return "";
        }
    }
}
