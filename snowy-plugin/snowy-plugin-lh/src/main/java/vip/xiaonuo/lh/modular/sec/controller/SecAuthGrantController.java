package vip.xiaonuo.lh.modular.sec.controller;

import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

@Tag(name = "表级/操作授权")
@RestController
@Validated
public class SecAuthGrantController {

    @Resource
    private SecAuthGrantService secAuthGrantService;

    @Operation(summary = "检查当前用户读/操作权限；privilege=EDIT|DELETE|MANAGE 为操作权，缺省或其它为表读（仅 asset）")
    @GetMapping("/lh/sec/grants/check")
    public CommonResult<Boolean> check(
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String privilege) {
        LhOpsPrivilegeEnum ops = LhOpsPrivilegeEnum.of(privilege);
        String type = StrUtil.blankToDefault(resourceType, "").trim().toLowerCase();
        String id = StrUtil.blankToDefault(resourceId, assetId).trim();
        if (StrUtil.isBlank(type) && StrUtil.isNotBlank(assetId)) {
            type = LhOpsResourceTypeEnum.ASSET.getValue();
            id = assetId.trim();
        }
        if (StrUtil.isBlank(type) || StrUtil.isBlank(id)) {
            throw new CommonException("须指定 assetId，或 resourceType + resourceId");
        }
        if (ops != null) {
            return CommonResult.data(secAuthGrantService.hasOpsPrivilege(type, id, ops));
        }
        if (!LhOpsResourceTypeEnum.ASSET.getValue().equals(type)) {
            throw new CommonException("表级读权限仅支持 resourceType=asset");
        }
        return CommonResult.data(secAuthGrantService.hasTableReadGrant(id));
    }
}
