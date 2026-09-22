package vip.xiaonuo.lh.modular.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.xiaonuo.common.annotation.CommonLog;
import vip.xiaonuo.common.pojo.CommonResult;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbCiteAckParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbPageParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbUpsertParam;
import vip.xiaonuo.lh.modular.knowledge.result.GovKbEntryVo;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;

import java.util.List;
import java.util.Map;

/**
 * 知识库（对齐 doc/知识库.md P0）
 */
@Tag(name = "知识库控制器")
@RestController
@Validated
public class GovKbController {

    @Resource
    private GovKbService govKbService;

    @Operation(summary = "知识库 KPI")
    @GetMapping("/lh/knowledge/overview")
    public CommonResult<Map<String, Object>> overview(@RequestParam(required = false) String ws) {
        return CommonResult.data(govKbService.overview(ws));
    }

    @Operation(summary = "Milvus 向量探针（D2；未启用则关键词降级）")
    @GetMapping("/lh/knowledge/vector/probe")
    public CommonResult<Map<String, Object>> vectorProbe() {
        return CommonResult.data(govKbService.vectorProbe());
    }

    @Operation(summary = "知识条目分页")
    @GetMapping("/lh/knowledge/entries")
    public CommonResult<Page<GovKbEntryVo>> page(GovKbPageParam param) {
        return CommonResult.data(govKbService.page(param));
    }

    @Operation(summary = "新建知识条目")
    @CommonLog("新建知识条目")
    @PostMapping("/lh/knowledge/entries")
    public CommonResult<GovKbEntryVo> create(@RequestBody GovKbUpsertParam param) {
        return CommonResult.data(govKbService.create(param));
    }

    @Operation(summary = "知识条目详情")
    @GetMapping("/lh/knowledge/entries/{id}")
    public CommonResult<GovKbEntryVo> detail(@PathVariable("id") String id) {
        return CommonResult.data(govKbService.detail(id));
    }

    @Operation(summary = "更新知识条目")
    @CommonLog("更新知识条目")
    @PutMapping("/lh/knowledge/entries/{id}")
    public CommonResult<GovKbEntryVo> update(
            @PathVariable("id") String id,
            @RequestBody GovKbUpsertParam param) {
        return CommonResult.data(govKbService.update(id, param));
    }

    @Operation(summary = "删除知识条目（软删）")
    @CommonLog("删除知识条目")
    @DeleteMapping("/lh/knowledge/entries/{id}")
    public CommonResult<String> delete(@PathVariable("id") String id) {
        govKbService.delete(id);
        return CommonResult.ok();
    }

    @Operation(summary = "删除知识条目（软删，前端 POST 兼容）")
    @CommonLog("删除知识条目")
    @PostMapping("/lh/knowledge/entries/{id}/delete")
    public CommonResult<String> deletePost(@PathVariable("id") String id) {
        govKbService.delete(id);
        return CommonResult.ok();
    }

    @Operation(summary = "重建索引")
    @CommonLog("重建知识索引")
    @PostMapping("/lh/knowledge/index/rebuild")
    public CommonResult<Map<String, Object>> rebuild(@RequestParam(required = false) String entryId) {
        return CommonResult.data(govKbService.rebuild(entryId));
    }

    @Operation(summary = "混合检索（向量优先；未启用 Milvus 则关键词）")
    @PostMapping("/lh/knowledge/search")
    public CommonResult<List<Map<String, Object>>> search(@RequestBody GovKbSearchParam param) {
        return CommonResult.data(govKbService.search(param));
    }

    @Operation(summary = "引用热度统计")
    @GetMapping("/lh/knowledge/stats")
    public CommonResult<Map<String, Object>> stats(@RequestParam(required = false) String ws) {
        return CommonResult.data(govKbService.stats(ws));
    }

    @Operation(summary = "回写引用计数")
    @PostMapping("/lh/knowledge/citations/ack")
    public CommonResult<Map<String, Object>> ack(@RequestBody GovKbCiteAckParam param) {
        return CommonResult.data(govKbService.ackCitation(param));
    }
}
