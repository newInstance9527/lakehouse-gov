package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageChangePoint;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageChangePointMapper;

import java.util.Date;

/**
 * 策略变更等写入 {@code gov_lc_storage_change_point}，供 days-to-full 分段截断与趋势标注。
 */
@Component
public class GovLcStorageChangePointWriter {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovLcStorageChangePointMapper changePointMapper;

    /**
     * @param kind 如 policy_upsert / compact / expire
     * @param sourceRef 关联实体 id（策略 id / run id）
     */
    public GovLcStorageChangePoint writeTablePoint(String ws, String tableFqn, String kind,
                                                   String note, String sourceRef) {
        if (StrUtil.isBlank(tableFqn)) {
            return null;
        }
        Date now = new Date();
        GovLcStorageChangePoint cp = new GovLcStorageChangePoint();
        cp.setId(IdUtil.getSnowflakeNextIdStr());
        cp.setRevision(1);
        cp.setStatus("active");
        cp.setWs(StrUtil.blankToDefault(ws, "default").trim());
        cp.setDt(now);
        cp.setScopeType("table");
        cp.setScopeKey(tableFqn.trim());
        cp.setKind(StrUtil.blankToDefault(kind, "policy_upsert"));
        cp.setNote(note);
        cp.setSourceRef(sourceRef);
        cp.setDeleteFlag(NOT_DELETE);
        cp.setCreateTime(now);
        cp.setUpdateTime(now);
        changePointMapper.insert(cp);
        return cp;
    }
}
