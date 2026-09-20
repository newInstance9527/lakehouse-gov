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
package vip.xiaonuo.lh.modular.standard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.standard.param.GovStdCodeUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdFieldUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdIdParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdMappingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdNamingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdPageParam;
import vip.xiaonuo.lh.modular.standard.result.GovStdCodeVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdDetectVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdFieldVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdMappingVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdNamingVo;

import java.util.Map;

/**
 * 数据标准 Service
 *
 * @author lakehouse
 * @date 2026/9/19
 */
public interface GovStdService {

    Map<String, Object> overview(String ws);

    Page<GovStdFieldVo> pageFields(GovStdPageParam param);

    GovStdFieldVo upsertField(GovStdFieldUpsertParam param);

    void deleteField(GovStdIdParam param);

    Page<GovStdCodeVo> pageCodes(GovStdPageParam param);

    GovStdCodeVo upsertCode(GovStdCodeUpsertParam param);

    void deleteCode(GovStdIdParam param);

    Page<GovStdNamingVo> pageNamings(GovStdPageParam param);

    GovStdNamingVo upsertNaming(GovStdNamingUpsertParam param);

    void deleteNaming(GovStdIdParam param);

    Page<GovStdMappingVo> pageMappings(GovStdPageParam param);

    GovStdMappingVo upsertMapping(GovStdMappingUpsertParam param);

    void deleteMapping(GovStdIdParam param);

    Page<GovStdDetectVo> pageDetects(GovStdPageParam param);

    Map<String, Object> metaOptions();
}
