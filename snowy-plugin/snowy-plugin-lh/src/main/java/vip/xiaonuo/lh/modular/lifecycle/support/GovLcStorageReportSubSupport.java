package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageReportSub;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageReportSubMapper;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcStorageService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储日报定时订阅：生成 report/export 内容并 POST 到 webhook。
 */
@Component
public class GovLcStorageReportSubSupport {

    private static final Logger log = LoggerFactory.getLogger(GovLcStorageReportSubSupport.class);

    @Resource
    private GovLcStorageReportSubMapper subMapper;
    @Resource
    private GovLcStorageService storageService;

    public List<Map<String, Object>> list(String ws) {
        LambdaQueryWrapper<GovLcStorageReportSub> qw = new LambdaQueryWrapper<GovLcStorageReportSub>()
                .eq(GovLcStorageReportSub::getDeleteFlag, "NOT_DELETE")
                .orderByDesc(GovLcStorageReportSub::getCreateTime);
        if (StrUtil.isNotBlank(ws)) {
            qw.and(w -> w.eq(GovLcStorageReportSub::getWs, ws.trim()).or().isNull(GovLcStorageReportSub::getWs)
                    .or().eq(GovLcStorageReportSub::getWs, ""));
        }
        List<GovLcStorageReportSub> rows = subMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovLcStorageReportSub r : rows) {
            out.add(toVo(r));
        }
        return out;
    }

    public Map<String, Object> upsert(Map<String, Object> body) {
        if (body == null) {
            throw new CommonException("body 不能为空");
        }
        String webhook = StrUtil.trim(str(body.get("webhookUrl")));
        if (StrUtil.isBlank(webhook) || !(webhook.startsWith("http://") || webhook.startsWith("https://"))) {
            throw new CommonException("webhookUrl 须为 http(s) URL");
        }
        String id = StrUtil.blankToDefault(str(body.get("id")), IdUtil.getSnowflakeNextIdStr());
        GovLcStorageReportSub row = subMapper.selectById(id);
        boolean insert = row == null;
        if (insert) {
            row = new GovLcStorageReportSub();
            row.setId(id);
            row.setRevision(1);
            row.setDeleteFlag("NOT_DELETE");
            row.setCreateTime(new Date());
        } else {
            row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        }
        row.setWs(blankToNull(str(body.get("ws"))));
        row.setRangeKey(StrUtil.blankToDefault(str(body.get("range")),
                StrUtil.blankToDefault(str(body.get("rangeKey")), "30d")));
        row.setFormat(StrUtil.blankToDefault(str(body.get("format")), "md"));
        row.setWebhookUrl(webhook);
        row.setCronExpr(StrUtil.blankToDefault(str(body.get("cronExpr")), "0 0 8 * * ?"));
        Object en = body.get("enabled");
        row.setEnabled(en == null || Boolean.TRUE.equals(en) || "1".equals(String.valueOf(en)) ? 1 : 0);
        row.setUpdateTime(new Date());
        if (insert) {
            subMapper.insert(row);
        } else {
            subMapper.updateById(row);
        }
        return toVo(row);
    }

    public void delete(String id) {
        if (StrUtil.isBlank(id)) {
            return;
        }
        GovLcStorageReportSub row = subMapper.selectById(id);
        if (row == null) {
            return;
        }
        row.setDeleteFlag("DELETED");
        row.setUpdateTime(new Date());
        subMapper.updateById(row);
    }

    /** 跑所有 enabled 订阅；返回汇总。 */
    public Map<String, Object> runDue() {
        List<GovLcStorageReportSub> rows = subMapper.selectList(new LambdaQueryWrapper<GovLcStorageReportSub>()
                .eq(GovLcStorageReportSub::getDeleteFlag, "NOT_DELETE")
                .eq(GovLcStorageReportSub::getEnabled, 1));
        int ok = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (GovLcStorageReportSub row : rows) {
            Map<String, Object> one = dispatchOne(row);
            details.add(one);
            if (Boolean.TRUE.equals(one.get("ok"))) {
                ok++;
            } else {
                fail++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("ok", ok);
        out.put("failed", fail);
        out.put("details", details);
        return out;
    }

    public Map<String, Object> runOne(String id) {
        GovLcStorageReportSub row = subMapper.selectById(id);
        if (row == null || !"NOT_DELETE".equals(row.getDeleteFlag())) {
            throw new CommonException("订阅不存在: {}", id);
        }
        return dispatchOne(row);
    }

    private Map<String, Object> dispatchOne(GovLcStorageReportSub row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        try {
            Map<String, Object> report = storageService.reportExport(row.getWs(), row.getRangeKey(), row.getFormat());
            String content = String.valueOf(report.getOrDefault("content", ""));
            String filename = String.valueOf(report.getOrDefault("filename", "storage-report.md"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", "存储日报 " + row.getRangeKey());
            payload.put("ws", row.getWs());
            payload.put("range", row.getRangeKey());
            payload.put("filename", filename);
            payload.put("rowCount", report.get("rowCount"));
            payload.put("content", content);
            HttpResponse resp = HttpRequest.post(row.getWebhookUrl())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Lh-Report", "storage")
                    .body(JSONUtil.toJsonStr(payload))
                    .timeout(15_000)
                    .execute();
            boolean ok = resp.getStatus() >= 200 && resp.getStatus() < 300;
            row.setLastStatus(ok ? "ok" : "failed");
            row.setLastMessage(ok ? "HTTP " + resp.getStatus() : "HTTP " + resp.getStatus() + " "
                    + StrUtil.maxLength(resp.body(), 180));
            row.setLastRunTime(new Date());
            subMapper.updateById(row);
            out.put("ok", ok);
            out.put("httpStatus", resp.getStatus());
            out.put("message", row.getLastMessage());
            return out;
        } catch (Exception e) {
            row.setLastStatus("failed");
            row.setLastMessage(StrUtil.maxLength(e.getMessage(), 200));
            row.setLastRunTime(new Date());
            subMapper.updateById(row);
            log.warn("storage report sub {} failed: {}", row.getId(), e.getMessage());
            out.put("ok", false);
            out.put("message", row.getLastMessage());
            return out;
        }
    }

    private static Map<String, Object> toVo(GovLcStorageReportSub r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("ws", r.getWs());
        m.put("range", r.getRangeKey());
        m.put("format", r.getFormat());
        m.put("webhookUrl", r.getWebhookUrl());
        m.put("cronExpr", r.getCronExpr());
        m.put("enabled", r.getEnabled() != null && r.getEnabled() == 1);
        m.put("lastStatus", r.getLastStatus());
        m.put("lastMessage", r.getLastMessage());
        m.put("lastRunTime", r.getLastRunTime());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return StrUtil.isBlank(s) ? null : s.trim();
    }
}
