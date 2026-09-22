package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcStorageAdvice;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcStorageAdviceMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 日批派生 {@code gov_lc_storage_advice}：按可回收/小文件/采集失败生成建议；同日同表同 kind 覆盖（幂等）。
 * days-to-full 由 {@link GovLcStorageDaysToFullDeriver} 写 VM，不在本类。
 */
@Component
public class GovLcStorageAdviceWriter {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final long RECLAIM_HIGH = 10L * 1024 * 1024 * 1024; // 10 GiB

    @Resource
    private GovLcStorageAdviceMapper adviceMapper;

    public List<Map<String, Object>> upsertFromProfile(String ws, Date dt, List<Map<String, Object>> tables) {
        List<Map<String, Object>> written = new ArrayList<>();
        if (tables == null) {
            return written;
        }
        for (Map<String, Object> row : tables) {
            String fqn = str(row.get("tableFqn"));
            if (StrUtil.isBlank(fqn)) {
                continue;
            }
            String status = str(row.get("collectStatus"));
            if ("failed".equals(status)) {
                written.add(upsert(ws, dt, fqn, "collect_fail", 2, 0L, "high",
                        "采集失败：" + StrUtil.maxLength(str(row.get("error")), 200)));
                continue;
            }
            long reclaimable = longVal(row.get("reclaimableBytes"));
            long total = longVal(row.get("totalBytes"));
            long fileCount = longVal(row.get("fileCount"));
            long small = longVal(row.get("smallFileCount"));
            double ratio = fileCount <= 0 ? 0.0 : small * 1.0 / fileCount;

            if (reclaimable > 0 && (reclaimable >= RECLAIM_HIGH || (total > 0 && reclaimable * 1.0 / total > 0.40))) {
                written.add(upsert(ws, dt, fqn, "expire", reclaimable >= RECLAIM_HIGH ? 1 : 2, reclaimable,
                        reclaimable >= RECLAIM_HIGH ? "high" : "medium",
                        "可回收 " + human(reclaimable) + "（物理差额），建议快照过期/孤儿清理"));
            }
            if (ratio > 0.30) {
                long est = Math.max(0, (long) (fileCount * 0.3 * 16L * 1024 * 1024));
                written.add(upsert(ws, dt, fqn, "compact", 1, est, "medium",
                        String.format("小文件占比 %.1f%%（>%s），建议 compaction", ratio * 100, "30%")));
            }
        }
        return written;
    }

    private Map<String, Object> upsert(String ws, Date dt, String fqn, String kind, int priority,
                                       long estReclaim, String confidence, String reason) {
        GovLcStorageAdvice existing = adviceMapper.selectOne(new QueryWrapper<GovLcStorageAdvice>().lambda()
                .eq(GovLcStorageAdvice::getWs, ws)
                .eq(GovLcStorageAdvice::getFqtn, fqn)
                .eq(GovLcStorageAdvice::getKind, kind)
                .eq(GovLcStorageAdvice::getDt, dt)
                .eq(GovLcStorageAdvice::getDeleteFlag, NOT_DELETE)
                .in(GovLcStorageAdvice::getStatus, List.of("open", "linked"))
                .last("LIMIT 1"));
        Date now = new Date();
        boolean insert = existing == null;
        GovLcStorageAdvice row = insert ? new GovLcStorageAdvice() : existing;
        if (insert) {
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setWs(ws);
            row.setDt(dt);
            row.setFqtn(fqn);
            row.setKind(kind);
            row.setStatus("open");
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
        } else {
            row.setRevision(row.getRevision() == null ? 2 : row.getRevision() + 1);
        }
        row.setPriority(priority);
        row.setEstReclaimBytes(estReclaim);
        row.setConfidence(confidence);
        row.setReason(StrUtil.maxLength(reason, 1000));
        row.setUpdateTime(now);
        if (insert) {
            adviceMapper.insert(row);
        } else {
            adviceMapper.updateById(row);
        }
        return Map.of(
                "id", row.getId(),
                "fqtn", fqn,
                "kind", kind,
                "estReclaimBytes", estReclaim,
                "inserted", insert
        );
    }

    private static String human(long bytes) {
        if (bytes >= 1L << 40) {
            return String.format("%.1f TiB", bytes / (double) (1L << 40));
        }
        if (bytes >= 1L << 30) {
            return String.format("%.1f GiB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format("%.1f MiB", bytes / (double) (1L << 20));
        }
        return bytes + " B";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long longVal(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
