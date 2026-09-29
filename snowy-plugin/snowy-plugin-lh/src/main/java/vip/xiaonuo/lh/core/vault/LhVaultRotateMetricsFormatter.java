package vip.xiaonuo.lh.core.vault;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Vault 轮换结果 → VictoriaMetrics（夜莺 {@code lh_vault_rotate_*}）。
 */
public final class LhVaultRotateMetricsFormatter {

    private LhVaultRotateMetricsFormatter() {
    }

    public static List<String> format(String vaultPath, String kind, boolean ok, long timestampMs) {
        String path = esc(StrUtil.blankToDefault(vaultPath, "unknown"));
        String k = esc(StrUtil.blankToDefault(kind, "vault"));
        String base = "vault_path=\"" + path + "\",kind=\"" + k + "\"";
        List<String> lines = new ArrayList<>(2);
        lines.add(gauge("lh_vault_rotate_ok", base, ok ? 1 : 0, timestampMs));
        lines.add(gauge("lh_vault_rotate_fail", base, ok ? 0 : 1, timestampMs));
        return lines;
    }

    public static String joinBody(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    private static String gauge(String name, String labels, double value, long tsMs) {
        return name + "{" + labels + "} " + value + " " + tsMs;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
