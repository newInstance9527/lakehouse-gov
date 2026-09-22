package vip.xiaonuo.lh.modular.observability.support;

import vip.xiaonuo.lh.config.LhProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §24.3 FinOps 单价与金额换算：存储趋势 showback 与 querygov 成本卡<strong>同源</strong>，禁止各页自拟。
 */
public final class LhFinOpsRates {

    public static final long GB = 1024L * 1024 * 1024;
    public static final long TB = GB * 1024;

    /** 与历史演示一致的默认：¥100 / TB·月 */
    public static final BigDecimal DEFAULT_STORAGE_PER_TB_MONTH = new BigDecimal("100");
    /** 与历史演示一致的默认：¥0.50 / GB 扫描 */
    public static final BigDecimal DEFAULT_COMPUTE_PER_GB = new BigDecimal("0.50");
    public static final String DEFAULT_CURRENCY = "CNY";

    private LhFinOpsRates() {
    }

    public static BigDecimal storagePerTbMonth(LhProperties props) {
        if (props != null && props.getFinops() != null && props.getFinops().getStoragePerTbMonth() != null) {
            return props.getFinops().getStoragePerTbMonth();
        }
        return DEFAULT_STORAGE_PER_TB_MONTH;
    }

    public static BigDecimal computePerGbScan(LhProperties props) {
        if (props != null && props.getFinops() != null && props.getFinops().getComputePerGbScan() != null) {
            return props.getFinops().getComputePerGbScan();
        }
        return DEFAULT_COMPUTE_PER_GB;
    }

    public static String currency(LhProperties props) {
        if (props != null && props.getFinops() != null && props.getFinops().getCurrency() != null
                && !props.getFinops().getCurrency().isBlank()) {
            return props.getFinops().getCurrency().trim();
        }
        return DEFAULT_CURRENCY;
    }

    /**
     * 物理占用按窗口折算月成本：{@code bytes/TB × 单价 × (days/30)}。
     */
    public static BigDecimal storageCost(long bytes, int days, BigDecimal perTbMonth) {
        BigDecimal rate = perTbMonth == null ? DEFAULT_STORAGE_PER_TB_MONTH : perTbMonth;
        BigDecimal tb = BigDecimal.valueOf(Math.max(0L, bytes))
                .divide(BigDecimal.valueOf(TB), 6, RoundingMode.HALF_UP);
        BigDecimal monthFactor = BigDecimal.valueOf(Math.max(1, days))
                .divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
        return tb.multiply(rate).multiply(monthFactor).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal computeCost(long scanBytes, BigDecimal perGb) {
        BigDecimal rate = perGb == null ? DEFAULT_COMPUTE_PER_GB : perGb;
        BigDecimal gb = BigDecimal.valueOf(Math.max(0L, scanBytes))
                .divide(BigDecimal.valueOf(GB), 6, RoundingMode.HALF_UP);
        return gb.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public static Map<String, Object> ratesMap(LhProperties props) {
        Map<String, Object> rates = new LinkedHashMap<>();
        rates.put("storagePerTbMonth", storagePerTbMonth(props));
        rates.put("computePerGbScan", computePerGbScan(props));
        rates.put("currency", currency(props));
        rates.put("source", "lh.finops (§24.3)");
        rates.put("note", "存储/扫描单价与 querygov 成本卡同源；非外部账单对接");
        return rates;
    }

    public static String formatCny(BigDecimal v) {
        if (v == null) {
            return "¥0.00";
        }
        return "¥" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
