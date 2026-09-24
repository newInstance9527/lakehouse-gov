package vip.xiaonuo.lh.modular.knowledge.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库分片：fixed / paragraph / heading（与前端 estimateChunkCount 窗口参数同源）
 */
public final class KbChunker {

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+.+$");

    private KbChunker() {
    }

    /**
     * 估算分片数（对齐 knowledge.js estimateChunkCount；非 fixed 为近似上限）
     */
    public static int estimateChunkCount(int charCount, int chunkSize, int overlap) {
        int chars = Math.max(0, charCount);
        if (chars == 0) {
            return 0;
        }
        int size = Math.max(1, chunkSize);
        int ov = Math.max(0, Math.min(size - 1, overlap));
        int step = Math.max(1, size - ov);
        return Math.max(1, (int) Math.ceil(chars * 1.0 / step));
    }

    /**
     * 按策略切分正文。
     *
     * @param strategy  fixed | paragraph | heading（未知回落 fixed）
     * @param text      正文
     * @param chunkSize 窗口（段落过长时再按 fixed 切）
     * @param overlap   fixed 重叠
     * @param separator fixed 可选分隔符；空则纯窗口滑动
     */
    public static List<String> split(String strategy, String text, int chunkSize, int overlap, String separator) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        String s = StrUtil.blankToDefault(strategy, "fixed").trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "paragraph" -> splitParagraph(text, chunkSize, overlap);
            case "heading" -> splitHeading(text, chunkSize, overlap);
            default -> splitFixed(text, chunkSize, overlap, separator);
        };
    }

    /**
     * 固定窗口切分；若 separator 非空则先按分隔符拆段再对超长段做窗口切。
     */
    public static List<String> splitFixed(String text, int chunkSize, int overlap) {
        return splitFixed(text, chunkSize, overlap, null);
    }

    public static List<String> splitFixed(String text, int chunkSize, int overlap, String separator) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        if (StrUtil.isNotBlank(separator)) {
            List<String> parts = new ArrayList<>();
            for (String seg : text.split(Pattern.quote(separator), -1)) {
                if (StrUtil.isBlank(seg)) {
                    continue;
                }
                parts.addAll(window(seg.trim(), chunkSize, overlap));
            }
            return parts.isEmpty() ? window(text, chunkSize, overlap) : parts;
        }
        return window(text, chunkSize, overlap);
    }

    /** 按空行 / \\n\\n 分段，再合并到接近 chunkSize；超长段回落 fixed */
    public static List<String> splitParagraph(String text, int chunkSize, int overlap) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        int size = Math.max(1, chunkSize <= 0 ? 500 : chunkSize);
        String[] raw = text.split("\\R{2,}");
        List<String> paras = new ArrayList<>();
        for (String p : raw) {
            String t = p == null ? "" : p.trim();
            if (StrUtil.isNotBlank(t)) {
                paras.add(t);
            }
        }
        if (paras.isEmpty()) {
            return window(text, size, overlap);
        }
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String para : paras) {
            if (para.length() > size) {
                flushBuf(buf, out);
                out.addAll(window(para, size, overlap));
                continue;
            }
            if (buf.isEmpty()) {
                buf.append(para);
            } else if (buf.length() + 1 + para.length() <= size) {
                buf.append('\n').append(para);
            } else {
                out.add(buf.toString());
                buf.setLength(0);
                buf.append(para);
            }
        }
        flushBuf(buf, out);
        return out;
    }

    /** 按 Markdown 标题切开；每节超长再 fixed */
    public static List<String> splitHeading(String text, int chunkSize, int overlap) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        int size = Math.max(1, chunkSize <= 0 ? 500 : chunkSize);
        var matcher = HEADING.matcher(text);
        List<Integer> cuts = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.start() > 0) {
                cuts.add(matcher.start());
            }
        }
        if (cuts.isEmpty()) {
            return splitParagraph(text, size, overlap);
        }
        List<String> sections = new ArrayList<>();
        int prev = 0;
        for (int cut : cuts) {
            String sec = text.substring(prev, cut).trim();
            if (StrUtil.isNotBlank(sec)) {
                sections.add(sec);
            }
            prev = cut;
        }
        String tail = text.substring(prev).trim();
        if (StrUtil.isNotBlank(tail)) {
            sections.add(tail);
        }
        List<String> out = new ArrayList<>();
        for (String sec : sections) {
            if (sec.length() <= size) {
                out.add(sec);
            } else {
                out.addAll(window(sec, size, overlap));
            }
        }
        return out;
    }

    private static void flushBuf(StringBuilder buf, List<String> out) {
        if (!buf.isEmpty()) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static List<String> window(String body, int chunkSize, int overlap) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(body)) {
            return out;
        }
        int size = Math.max(1, chunkSize <= 0 ? 500 : chunkSize);
        int ov = Math.max(0, Math.min(size - 1, overlap < 0 ? 50 : overlap));
        int step = Math.max(1, size - ov);
        int len = body.length();
        for (int i = 0; i < len; i += step) {
            int end = Math.min(len, i + size);
            out.add(body.substring(i, end));
            if (end >= len) {
                break;
            }
        }
        return out;
    }

    /** 粗估 token（中英混合约 1.5 字/token） */
    public static int estimateTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 1.5));
    }
}
