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
package vip.xiaonuo.lh.modular.datasource.provider;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.api.LhDatasourceApi;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;

import java.util.Map;

/**
 * 数据源 API 提供者（供 DAG / 作业侧按 dsId 解析凭证）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Service
public class LhDatasourceApiProvider implements LhDatasourceApi {

    @Resource
    private LhDatasourceService datasourceService;

    /**
     * 解析数据源连接与凭证
     *
     * @param dsId 数据源 ID
     * @param mode read / write
     * @return meta + secret
     */
    @Override
    public Map<String, Object> resolveDs(String dsId, String mode) {
        return datasourceService.resolveDs(dsId, mode);
    }
}
