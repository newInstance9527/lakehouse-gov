package vip.xiaonuo.lh.modular.lifecycle.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Spark {@code remove_orphan_files(dry_run)} 任务日志里数候选文件。
 * 解析不到返回 -1，调用方不得用种子数冒充。
 */
public final class GovLcOrphanLogParser {

    private static final Pattern COUNT = Pattern.compile("(?i)(\\d+)\\s+orphan\\s+files?");
    private static final Pattern PATH = Pattern.compile("(?i)(s3a?://|hdfs://|file:|gs://|abfs://|wasb://)\\S+");

    private GovLcOrphanLogParser() {
    }

    public static long countCandidates(String log) {
        if (log == null || log.isBlank()) {
            return -1L;
        }
        Matcher counted = COUNT.matcher(log);
        if (counted.find()) {
            try {
                return Long.parseLong(counted.group(1));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        Matcher paths = PATH.matcher(log);
        long n = 0;
        while (paths.find()) {
            n++;
        }
        return n > 0 ? n : -1L;
    }
}
