package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceSqlrestProjector;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量回填 SQLREST 投影态到数据源 VO（角标 / 重试）。
 */
@Component
public class LhSqlrestBindingEnricher {

    @Resource
    private LhConsumerBindingMapper bindingMapper;

    public void fill(LhDatasourceVo vo) {
        if (vo == null) {
            return;
        }
        fill(List.of(vo));
    }

    public void fill(Collection<LhDatasourceVo> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (LhDatasourceVo vo : vos) {
            if (vo != null && StrUtil.isNotBlank(vo.getId())) {
                ids.add(vo.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        List<LhConsumerBinding> binds = bindingMapper.selectList(new QueryWrapper<LhConsumerBinding>().lambda()
                .in(LhConsumerBinding::getDsId, ids)
                .eq(LhConsumerBinding::getConsumerType, LhDatasourceSqlrestProjector.CONSUMER_TYPE));
        Map<String, LhConsumerBinding> byDs = new HashMap<>();
        for (LhConsumerBinding b : binds) {
            byDs.putIfAbsent(b.getDsId(), b);
        }
        for (LhDatasourceVo vo : vos) {
            if (vo == null) {
                continue;
            }
            boolean projectable = LhDatasourceSqlrestProjector.isProjectable(
                    StrUtil.blankToDefault(vo.getTypeCode(), vo.getType()));
            vo.setSqlrestProjectable(projectable);
            LhConsumerBinding b = byDs.get(vo.getId());
            if (b == null) {
                vo.setSqlrestSyncState(projectable ? "never" : "unsupported");
                vo.setSqlrestProjected(false);
                vo.setSqlrestLastError(null);
                vo.setSqlrestDatasourceId(null);
                continue;
            }
            vo.setSqlrestSyncState(StrUtil.blankToDefault(b.getSyncState(), "unknown"));
            vo.setSqlrestLastError(b.getLastError());
            Long srId = null;
            if (StrUtil.isNotBlank(b.getProjection())) {
                try {
                    srId = JSONUtil.parseObj(b.getProjection()).getLong("sqlrestDatasourceId");
                } catch (Exception ignored) {
                    // ignore malformed projection
                }
            }
            vo.setSqlrestDatasourceId(srId);
            boolean synced = "synced".equalsIgnoreCase(b.getSyncState()) && srId != null;
            vo.setSqlrestProjected(synced);
        }
    }
}
