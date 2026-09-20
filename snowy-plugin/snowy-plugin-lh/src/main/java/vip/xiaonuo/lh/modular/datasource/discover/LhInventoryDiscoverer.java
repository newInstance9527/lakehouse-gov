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
package vip.xiaonuo.lh.modular.datasource.discover;

import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

/**
 * 源端清单发现器（表 / Topic / Index / Bucket / Queue / Key 前缀等）
 * <p>按类型分派；未实现真实发现的类型走手工摘要（path=manual）。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public interface LhInventoryDiscoverer {

    /** 是否处理该类型编码（小写约定由调用方归一） */
    boolean supports(String typeCode);

    /**
     * 清单对象语义编码：table / topic / index / bucket / queue / key_prefix / path / collection …
     */
    String objectKind(String typeCode);

    /** 发现清单；真实拉取失败应抛 CommonException，勿静默空结果冒充成功 */
    LhInventoryDiscoverResult discover(LhDatasource ds);
}
