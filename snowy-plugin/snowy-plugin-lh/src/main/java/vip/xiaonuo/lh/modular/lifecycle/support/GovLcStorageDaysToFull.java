package vip.xiaonuo.lh.modular.lifecycle.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分段线性 days-to-full（p50/p95）。
 * <p>
 * 规则对齐 {@code doc/存储趋势.md} §4.4：最近 N=min(90, 距变更点天数) 且 N≥15；
 * 对 {@code total_bytes} 线性回归；p50=slope，p95=slope 的 95% 置信上界；
 * TTF=(capacity − total − reclaimable×0.7)/slope；R²&lt;0.5 标不稳定。
 */
public final class GovLcStorageDaysToFull {

    public static final int MIN_SAMPLES = 15;
    public static final int MAX_WINDOW_DAYS = 90;
    public static final double RECLAIM_CONFIDENCE = 0.7;
    public static final double UNSTABLE_R2 = 0.5;
    private static final double Z95 = 1.96;

    private GovLcStorageDaysToFull() {
    }

    /** 日点：UTC epoch day → 物理量字节。 */
    public record Point(long epochDay, long totalBytes) {
    }

    public record Result(
            boolean available,
            String reason,
            Double p50Days,
            Double p95Days,
            double slopeBytesPerDay,
            double slopeP95BytesPerDay,
            double r2,
            boolean unstable,
            int sampleCount
    ) {
        public static Result skip(String reason, int sampleCount) {
            return new Result(false, reason, null, null, 0, 0, 0, false, sampleCount);
        }
    }

    /**
     * @param pointsAsc            升序日点（可含今日）
     * @param changePointEpochDays 变更点（UTC epoch day）；取最近一个做截断
     * @param capacityBytes        容量上限；≤0 则不出预测
     * @param currentTotalBytes    当前物理量
     * @param reclaimableBytes     可回收量
     * @param reclaimConfidence    回收把握系数，默认 0.7
     */
    public static Result compute(List<Point> pointsAsc,
                                 List<Long> changePointEpochDays,
                                 long capacityBytes,
                                 long currentTotalBytes,
                                 long reclaimableBytes,
                                 double reclaimConfidence) {
        if (capacityBytes <= 0) {
            return Result.skip("NO_CAPACITY", pointsAsc == null ? 0 : pointsAsc.size());
        }
        List<Point> window = segmentWindow(pointsAsc, changePointEpochDays);
        if (window.size() < MIN_SAMPLES) {
            return Result.skip("INSUFFICIENT", window.size());
        }

        int n = window.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        long x0 = window.get(0).epochDay();
        for (int i = 0; i < n; i++) {
            xs[i] = window.get(i).epochDay() - x0;
            ys[i] = window.get(i).totalBytes();
        }

        Fit fit = ols(xs, ys);
        if (fit.slope <= 0) {
            return new Result(false, "NON_POSITIVE_SLOPE", null, null,
                    fit.slope, fit.slope, fit.r2, fit.r2 < UNSTABLE_R2, n);
        }

        double seSlope = fit.seSlope;
        double slopeP95 = fit.slope + Z95 * seSlope;
        if (slopeP95 < fit.slope) {
            slopeP95 = fit.slope;
        }

        double headroom = capacityBytes
                - currentTotalBytes
                - Math.max(0, reclaimableBytes) * reclaimConfidence;
        if (headroom <= 0) {
            return new Result(true, "FULL", 0.0, 0.0,
                    fit.slope, slopeP95, fit.r2, fit.r2 < UNSTABLE_R2, n);
        }

        double p50 = headroom / fit.slope;
        double p95 = headroom / slopeP95;
        return new Result(true, "OK", p50, p95,
                fit.slope, slopeP95, fit.r2, fit.r2 < UNSTABLE_R2, n);
    }

    /** 变更点截断后取最近最多 {@link #MAX_WINDOW_DAYS} 个日点。 */
    static List<Point> segmentWindow(List<Point> pointsAsc, List<Long> changePointEpochDays) {
        if (pointsAsc == null || pointsAsc.isEmpty()) {
            return List.of();
        }
        List<Point> sorted = new ArrayList<>(pointsAsc);
        sorted.sort(Comparator.comparingLong(Point::epochDay));

        long cut = Long.MIN_VALUE;
        if (changePointEpochDays != null && !changePointEpochDays.isEmpty()) {
            long lastPointDay = sorted.get(sorted.size() - 1).epochDay();
            for (Long cp : changePointEpochDays) {
                if (cp != null && cp <= lastPointDay && cp > cut) {
                    cut = cp;
                }
            }
        }

        List<Point> after = new ArrayList<>();
        for (Point p : sorted) {
            if (p.epochDay() > cut) {
                after.add(p);
            }
        }
        if (after.isEmpty()) {
            return List.of();
        }
        // N = min(90, 距变更点样本天数)：按点数截取最近窗口
        if (after.size() > MAX_WINDOW_DAYS) {
            return new ArrayList<>(after.subList(after.size() - MAX_WINDOW_DAYS, after.size()));
        }
        return after;
    }

    private record Fit(double slope, double intercept, double r2, double seSlope) {
    }

    private static Fit ols(double[] xs, double[] ys) {
        int n = xs.length;
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0, sumYY = 0;
        for (int i = 0; i < n; i++) {
            sumX += xs[i];
            sumY += ys[i];
            sumXX += xs[i] * xs[i];
            sumXY += xs[i] * ys[i];
            sumYY += ys[i] * ys[i];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double sxx = sumXX - n * meanX * meanX;
        double sxy = sumXY - n * meanX * meanY;
        double syy = sumYY - n * meanY * meanY;
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = meanY - slope * meanX;

        double sse = 0;
        for (int i = 0; i < n; i++) {
            double err = ys[i] - (intercept + slope * xs[i]);
            sse += err * err;
        }
        double r2 = syy <= 0 ? 1.0 : Math.max(0, 1.0 - sse / syy);
        double mse = n > 2 ? sse / (n - 2) : 0;
        double seSlope = (sxx <= 0 || mse <= 0) ? 0 : Math.sqrt(mse / sxx);
        return new Fit(slope, intercept, r2, seSlope);
    }
}
