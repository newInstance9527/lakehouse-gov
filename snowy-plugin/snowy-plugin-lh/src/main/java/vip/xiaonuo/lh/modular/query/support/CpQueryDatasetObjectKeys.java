package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;

/**
 * 即席数据集抽样对象路径约定（禁止标识符含 cursor）。
 *
 * <p>key：{@code {prefix}/{ws}/{dsCode}/sample.json}</p>
 */
public final class CpQueryDatasetObjectKeys {

    public static final String DEFAULT_BUCKET = "lh-portal";
    public static final String DEFAULT_PREFIX = "adhoc/dataset";
    public static final String SAMPLE_FILE = "sample.json";
    public static final String STORAGE_OBJECT = "object";
    public static final String STORAGE_DB = "db";

    private CpQueryDatasetObjectKeys() {
    }

    public static String sanitizeSegment(String raw) {
        String s = StrUtil.blankToDefault(raw, "default").trim();
        s = s.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (s.isEmpty() || ".".equals(s) || "..".equals(s) || s.contains("..")) {
            return "default";
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    public static String objectKey(String prefix, String ws, String dsCode) {
        String p = StrUtil.removeSuffix(StrUtil.blankToDefault(prefix, DEFAULT_PREFIX).trim(), "/");
        if (p.isEmpty()) {
            p = DEFAULT_PREFIX;
        }
        return p + "/" + sanitizeSegment(ws) + "/" + sanitizeSegment(dsCode) + "/" + SAMPLE_FILE;
    }

    public static String uri(String bucket, String objectKey) {
        String b = StrUtil.blankToDefault(bucket, DEFAULT_BUCKET).trim();
        String k = StrUtil.removePrefix(StrUtil.blankToDefault(objectKey, ""), "/");
        return "s3a://" + b + "/" + k;
    }
}
