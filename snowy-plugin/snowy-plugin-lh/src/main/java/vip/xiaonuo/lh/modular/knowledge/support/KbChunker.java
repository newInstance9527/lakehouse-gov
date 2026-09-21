package vip.xiaonuo.lh.modular.knowledge.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分片（与前端 estimateChunkCount 同源算法）
 */
public final class KbChunker {

    private KbChunker() {
    }

    /**
     * 估算分片数（对齐 knowledge.js estimateChunkCount）
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
     * 固定窗口切分；paragraph/heading 在 P0 回落到 fixed
     *
     * @param text      正文
     * @param chunkSize 窗口
     * @param overlap   重叠
     * @return 分片文本
     */
    public static List<String> splitFixed(String text, int chunkSize, int overlap) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return out;
        }
        String body = text;
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
