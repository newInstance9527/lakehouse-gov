package vip.xiaonuo.lh.modular.export.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.export.entity.GovExportAudit;
import vip.xiaonuo.lh.modular.export.mapper.GovExportAuditMapper;
import vip.xiaonuo.lh.modular.export.service.ExportAuditService;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ExportAuditServiceImpl implements ExportAuditService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovExportAuditMapper auditMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;

    @Override
    public Map<String, Object> record(String eventType, ApplyTicket ticket, Map<String, Object> extras) {
        Map<String, Object> extra = extras == null ? Map.of() : extras;
        Date now = new Date();
        JSONObject payload = parsePayload(ticket);
        String src = firstNonBlank(
                str(extra.get("src")),
                payload.getStr("exportTable"),
                payload.getStr("assetCode"),
                ticket == null ? null : ticket.getTitle());
        String target = firstNonBlank(str(extra.get("target")), payload.getStr("exportTarget"));
        String purpose = firstNonBlank(
                str(extra.get("purpose")),
                ticket == null ? null : ticket.getReason(),
                payload.getStr("purpose"));
        String approver = firstNonBlank(
                str(extra.get("approver")),
                ticket == null ? null : ticket.getApprovedBy(),
                ticket == null ? null : ticket.getUpdateUser());

        Map<String, Object> grav = tryMarkGravitino(eventType, ticket, src, payload);

        GovExportAudit row = new GovExportAudit();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setWs(ticket == null ? "default" : StrUtil.blankToDefault(ticket.getWs(), "default"));
        row.setTicketId(ticket == null ? null : ticket.getId());
        row.setTicketNo(ticket == null ? str(extra.get("ticketNo")) : ticket.getTicketNo());
        row.setEventType(StrUtil.blankToDefault(eventType, "unknown"));
        row.setSrcTable(src);
        row.setTarget(target);
        row.setPurpose(purpose);
        row.setApprover(approver);
        row.setDagId(str(extra.get("dagId")));
        row.setDagCode(str(extra.get("dagCode")));
        row.setNodeKey(str(extra.get("nodeKey")));
        row.setGravOk(Boolean.TRUE.equals(grav.get("ok")) ? 1 : 0);
        row.setGravRef(str(grav.get("ref")));
        row.setGravMessage(str(grav.get("message")));
        JSONObject detail = new JSONObject();
        if (extra.get("detail") != null) {
            detail.set("extra", extra.get("detail"));
        }
        detail.set("grav", grav);
        if (extra.containsKey("pausedDags")) {
            detail.set("pausedDags", extra.get("pausedDags"));
        }
        if (extra.containsKey("purgeNotice")) {
            detail.set("purgeNotice", extra.get("purgeNotice"));
        }
        row.setDetailJson(detail.toString());
        row.setEventTime(now);
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setCreateUser(approver);
        auditMapper.insert(row);

        Map<String, Object> out = toLine(row);
        out.put("id", row.getId());
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String ws, String ticketNo) {
        QueryWrapper<GovExportAudit> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovExportAudit::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(ws), GovExportAudit::getWs, ws)
                .eq(StrUtil.isNotBlank(ticketNo), GovExportAudit::getTicketNo, ticketNo)
                .orderByDesc(GovExportAudit::getEventTime)
                .last("LIMIT 500");
        List<GovExportAudit> rows = auditMapper.selectList(qw);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (GovExportAudit r : rows) {
            lines.add(toLine(r));
        }
        return lines;
    }

    private Map<String, Object> tryMarkGravitino(String eventType, ApplyTicket ticket, String src, JSONObject payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        LhProperties.Export cfg = lhProperties.getExport();
        if (cfg == null || !cfg.isGravAuditEnabled()) {
            out.put("message", "lh.export.grav-audit-enabled=false");
            return out;
        }
        CbGravAssetRef ref = resolveGravRef(ticket, src, payload);
        if (ref == null) {
            out.put("message", "源表未挂接 Gravitino，仅门户落库");
            return out;
        }
        String refLabel = ref.getGravMetalake() + "." + ref.getGravCatalog() + "."
                + ref.getGravSchema() + "." + ref.getGravTable();
        out.put("ref", refLabel);
        Map<String, String> props = new LinkedHashMap<>();
        props.put("lh.export.last_event", StrUtil.blankToDefault(eventType, ""));
        props.put("lh.export.last_ticket", ticket == null ? "" : StrUtil.blankToDefault(ticket.getTicketNo(), ""));
        props.put("lh.export.last_at", String.valueOf(System.currentTimeMillis()));
        try {
            Map<String, Object> grav = gravitinoClient.setTableProperties(
                    ref.getGravMetalake(), ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable(), props);
            out.put("ok", Boolean.TRUE.equals(grav.get("ok")));
            if (!Boolean.TRUE.equals(grav.get("ok"))) {
                out.put("message", StrUtil.blankToDefault(str(grav.get("message")), "Gravitino setProperty soft-fail"));
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            log.warn("export grav audit soft-fail ticket={} msg={}",
                    ticket == null ? null : ticket.getTicketNo(), e.getMessage());
        }
        return out;
    }

    private CbGravAssetRef resolveGravRef(ApplyTicket ticket, String src, JSONObject payload) {
        String assetId = StrUtil.blankToDefault(payload.getStr("assetId"), "");
        if (StrUtil.isNotBlank(assetId)) {
            GovAsset asset = assetMapper.selectById(assetId);
            if (asset != null && StrUtil.isNotBlank(asset.getGravAssetId())) {
                return gravAssetRefMapper.selectById(asset.getGravAssetId());
            }
        }
        String code = firstNonBlank(payload.getStr("assetCode"), src);
        if (StrUtil.isBlank(code)) {
            return null;
        }
        String bare = code.contains(".") ? code.substring(code.lastIndexOf('.') + 1) : code;
        GovAsset byCode = assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAsset::getAssetCode, code).or().eq(GovAsset::getAssetCode, bare))
                .last("LIMIT 1"));
        if (byCode != null && StrUtil.isNotBlank(byCode.getGravAssetId())) {
            return gravAssetRefMapper.selectById(byCode.getGravAssetId());
        }
        return null;
    }

    private static Map<String, Object> toLine(GovExportAudit r) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", r.getEventTime());
        line.put("ticketNo", r.getTicketNo());
        line.put("eventType", r.getEventType());
        line.put("src", r.getSrcTable());
        line.put("target", r.getTarget());
        line.put("purpose", r.getPurpose());
        line.put("approver", r.getApprover());
        line.put("status", r.getEventType());
        line.put("dagCode", r.getDagCode());
        line.put("nodeKey", r.getNodeKey());
        line.put("gravOk", r.getGravOk() != null && r.getGravOk() == 1);
        line.put("gravRef", r.getGravRef());
        line.put("gravMessage", r.getGravMessage());
        return line;
    }

    private static JSONObject parsePayload(ApplyTicket ticket) {
        if (ticket == null || StrUtil.isBlank(ticket.getPayload())) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(ticket.getPayload());
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
