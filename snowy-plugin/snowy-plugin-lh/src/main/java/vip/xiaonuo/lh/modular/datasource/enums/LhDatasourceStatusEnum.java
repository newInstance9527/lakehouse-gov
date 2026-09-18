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
package vip.xiaonuo.lh.modular.datasource.enums;

import lombok.Getter;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Arrays;

/**
 * 数据源生命周期状态
 * <p>online=在线 · warn=告警 · paused=停用 · revoked=已吊销（软删前标记）</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
public enum LhDatasourceStatusEnum {

    ONLINE("online", "在线"),
    WARN("warn", "告警"),
    PAUSED("paused", "停用"),
    REVOKED("revoked", "已吊销");

    private final String value;
    private final String label;

    LhDatasourceStatusEnum(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 校验状态
     *
     * @param status 状态值
     */
    public static void validate(String status) {
        boolean ok = Arrays.stream(values()).anyMatch(e -> e.value.equals(status));
        if (!ok) {
            throw new CommonException("不支持的数据源状态: {}", status);
        }
    }
}
