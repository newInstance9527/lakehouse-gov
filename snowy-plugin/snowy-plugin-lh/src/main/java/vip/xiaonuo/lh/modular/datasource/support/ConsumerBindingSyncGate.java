package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据源消费者投影同步门禁：{@code sync_state=stale|error|revoked} 时拒绝 deploy/publish。
 * <p>对齐双 Catalog §4.3 / 验收：rotate 后未重投影前新发布失败。</p>
 */
@Component
public class ConsumerBindingSyncGate {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Set<String> BLOCKED = Set.of("stale", "error", "revoked");

    @Resource
    private LhConsumerBindingMapper consumerBindingMapper;

    /**
     * 任一数据源存在非 synced 绑定则硬失败。
     * 无绑定行不阻断（未投影过的源仍可走仅门户登记路径）。
     */
    public void assertSyncedForDsIds(Collection<String> dsIds) {
        if (dsIds == null || dsIds.isEmpty()) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : dsIds) {
            if (StrUtil.isNotBlank(id)) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        List<LhConsumerBinding> rows = consumerBindingMapper.selectList(new QueryWrapper<LhConsumerBinding>().lambda()
                .in(LhConsumerBinding::getDsId, ids)
                .eq(LhConsumerBinding::getDeleteFlag, NOT_DELETE));
        for (LhConsumerBinding b : rows) {
            String state = StrUtil.blankToDefault(b.getSyncState(), "synced").trim().toLowerCase(Locale.ROOT);
            if (BLOCKED.contains(state)) {
                throw new CommonException("数据源投影未就绪（ds=" + b.getDsId()
                        + " · consumer=" + b.getConsumerType()
                        + " · sync_state=" + state
                        + "），请先重新投影后再发布");
            }
        }
    }

    /** 单数据源（如 SQLREST 发布绑定的 portalDsId）。 */
    public void assertSyncedForDs(String dsId) {
        if (StrUtil.isBlank(dsId)) {
            return;
        }
        assertSyncedForDsIds(List.of(dsId));
    }
}
