/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按 {@link GovAssetPreviewAdapter#order()} 依次尝试预览适配器。
 */
@Slf4j
@Component
public class GovAssetPreviewRouter {

    @Resource
    private List<GovAssetPreviewAdapter> adapters;

    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        List<GovAssetPreviewAdapter> ordered = adapters.stream()
                .sorted(Comparator.comparingInt(GovAssetPreviewAdapter::order))
                .collect(Collectors.toList());
        Map<String, Object> last = null;
        for (GovAssetPreviewAdapter adapter : ordered) {
            if (!adapter.supports(ctx)) {
                continue;
            }
            try {
                Map<String, Object> r = adapter.preview(ctx);
                last = r;
                if (r == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    r.remove("tryNext");
                    return r;
                }
                // 明确结束（不支持行预览等）且未要求 tryNext
                if (!Boolean.TRUE.equals(r.get("tryNext"))) {
                    return r;
                }
            } catch (Exception e) {
                log.warn("preview adapter {} soft-fail: {}", adapter.getClass().getSimpleName(), e.getMessage());
                last = ctx.newResult();
                last.put("ok", false);
                last.put("source", "degraded");
                last.put("degraded", true);
                last.put("tryNext", true);
                last.put("message", e.getMessage());
            }
        }
        if (last != null) {
            last.remove("tryNext");
            return last;
        }
        Map<String, Object> none = ctx.newResult();
        none.put("ok", false);
        none.put("source", "none");
        none.put("message", "无可用预览适配器：请确认源类型、objectName 或湖表已 refresh 挂接 Grav");
        none.put("hint", "湖表→Gravitino(+Trino回退)；RDB→JDBC；Kafka/Redis→各自 sample；其余仅元数据");
        none.put("columns", List.of());
        none.put("rows", List.of());
        none.put("rowCount", 0);
        return none;
    }

    public List<String> adapterNames() {
        return adapters.stream()
                .sorted(Comparator.comparingInt(GovAssetPreviewAdapter::order))
                .map(a -> a.order() + ":" + a.getClass().getSimpleName())
                .collect(Collectors.toList());
    }
}
