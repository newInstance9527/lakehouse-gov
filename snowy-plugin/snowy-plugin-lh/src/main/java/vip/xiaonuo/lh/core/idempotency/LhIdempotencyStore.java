package vip.xiaonuo.lh.core.idempotency;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.idempotency.entity.LhApiIdempotency;
import vip.xiaonuo.lh.core.idempotency.mapper.LhApiIdempotencyMapper;

import java.util.Calendar;
import java.util.Date;

@Service
public class LhIdempotencyStore {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String ST_PROCESSING = "processing";
    private static final String ST_COMPLETED = "completed";
    private static final String ST_FAILED = "failed";
    private static final int TTL_HOURS = 72;

    @Resource
    private LhApiIdempotencyMapper idempotencyMapper;

    public LhApiIdempotency find(String scope, String subject, String key) {
        return idempotencyMapper.selectOne(new QueryWrapper<LhApiIdempotency>().lambda()
                .eq(LhApiIdempotency::getDeleteFlag, NOT_DELETE)
                .eq(LhApiIdempotency::getScope, scope)
                .eq(LhApiIdempotency::getSubjectId, subject)
                .eq(LhApiIdempotency::getIdemKey, key)
                .last("LIMIT 1"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public LhApiIdempotency tryBegin(String scope, String subject, String key, String reqHash) {
        Date now = new Date();
        LhApiIdempotency row = new LhApiIdempotency();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setScope(scope);
        row.setIdemKey(key);
        row.setSubjectId(subject);
        row.setReqHash(reqHash);
        row.setStatus(ST_PROCESSING);
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setExpireAt(hoursLater(now, TTL_HOURS));
        try {
            idempotencyMapper.insert(row);
            return row;
        } catch (Exception e) {
            if (isDuplicate(e)) {
                return null;
            }
            throw e instanceof RuntimeException re ? re : new CommonException(e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markCompleted(String id, String resourceType, String resourceId, String responseJson) {
        LhApiIdempotency row = idempotencyMapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setStatus(ST_COMPLETED);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setResponseJson(responseJson);
        row.setErrorMsg(null);
        row.setUpdateTime(new Date());
        idempotencyMapper.updateById(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(String id, String msg) {
        LhApiIdempotency row = idempotencyMapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setStatus(ST_FAILED);
        row.setErrorMsg(StrUtil.maxLength(StrUtil.blankToDefault(msg, "failed"), 500));
        row.setUpdateTime(new Date());
        idempotencyMapper.updateById(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void retire(LhApiIdempotency row) {
        if (row == null) {
            return;
        }
        LhApiIdempotency fresh = idempotencyMapper.selectById(row.getId());
        if (fresh == null) {
            return;
        }
        fresh.setDeleteFlag("DELETED");
        fresh.setIdemKey(fresh.getIdemKey() + "#retired#" + fresh.getId());
        fresh.setUpdateTime(new Date());
        idempotencyMapper.updateById(fresh);
    }

    public static boolean expired(LhApiIdempotency row) {
        return row.getExpireAt() != null && row.getExpireAt().before(new Date());
    }

    private static Date hoursLater(Date from, int hours) {
        Calendar c = Calendar.getInstance();
        c.setTime(from);
        c.add(Calendar.HOUR_OF_DAY, hours);
        return c.getTime();
    }

    private static boolean isDuplicate(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String msg = StrUtil.blankToDefault(t.getMessage(), "").toLowerCase();
            if (msg.contains("duplicate") || msg.contains("unique")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
