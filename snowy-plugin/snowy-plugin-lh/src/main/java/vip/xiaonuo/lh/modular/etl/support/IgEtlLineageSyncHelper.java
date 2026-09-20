package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ETL 发布后触发血缘 fields/sync（soft-fail）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class IgEtlLineageSyncHelper {

    private static final Logger log = LoggerFactory.getLogger(IgEtlLineageSyncHelper.class);

    @Resource
    private GovLineageService govLineageService;

    public Map<String, Object> syncAfterDeploy(String ws, String dagCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> sync = govLineageService.syncFields(
                    StrUtil.blankToDefault(ws, "default"),
                    StrUtil.blankToDefault(dagCode, null));
            out.put("ok", true);
            out.put("degraded", false);
            out.putAll(sync);
            return out;
        } catch (Exception e) {
            log.warn("ETL lineage sync soft-fail dag={}: {}", dagCode, e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }
}
