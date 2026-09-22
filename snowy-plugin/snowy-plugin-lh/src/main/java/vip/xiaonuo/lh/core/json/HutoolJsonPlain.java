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
package vip.xiaonuo.lh.core.json;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hutool JSON → 普通 Map/List/null。
 * <p>Hutool 把 JSON null 存成 {@link JSONNull}，直接放进 Spring MVC 响应会被 Jackson 拒绝
 * （{@code HttpMessageConversionException: Type definition error: … JSONNull}）。</p>
 */
public final class HutoolJsonPlain {

    private HutoolJsonPlain() {
    }

    public static Object plain(Object v) {
        if (v == null || v instanceof JSONNull) {
            return null;
        }
        if (v instanceof JSONObject jo) {
            Map<String, Object> out = new LinkedHashMap<>();
            jo.forEach((k, val) -> out.put(k, plain(val)));
            return out;
        }
        if (v instanceof JSONArray ja) {
            List<Object> out = new ArrayList<>(ja.size());
            for (Object item : ja) {
                out.add(plain(item));
            }
            return out;
        }
        return v;
    }
}
