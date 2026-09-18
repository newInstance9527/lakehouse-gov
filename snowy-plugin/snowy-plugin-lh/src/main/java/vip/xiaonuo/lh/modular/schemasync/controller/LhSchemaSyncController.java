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
package vip.xiaonuo.lh.modular.schemasync.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.schemasync.param.LhSchemaSyncRunParam;
import vip.xiaonuo.lh.modular.schemasync.service.LhSchemaSyncService;

import java.util.Map;

/**
 * Grav→OM Schema Sync 触发接口（与数据源登记解耦）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Tag(name = "Schema Sync控制器")
@RestController
@Validated
public class LhSchemaSyncController {

    @Resource
    private LhSchemaSyncService schemaSyncService;

    @Operation(summary = "触发Grav→OM结构同步")
    @CommonLog("Schema Sync")
    @SaCheckPermission("/lh/schemasync/run")
    @PostMapping("/lh/schemasync/run")
    public CommonResult<Map<String, Object>> run(@RequestBody(required = false) LhSchemaSyncRunParam param) {
        return CommonResult.data(schemaSyncService.run(param == null ? new LhSchemaSyncRunParam() : param));
    }
}
