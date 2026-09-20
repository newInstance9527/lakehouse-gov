package vip.xiaonuo.lh.core.engine;

/**
 * Worker SHELL 脚本中展开 {@code ${LH_*}} 占位。
 * <p>禁止裸 {@code sed s|…|${URL}|}：URL/密码中的 {@code &} 会被 sed 当成「整段匹配」回插，
 * 导致 JDBC URL 粘连、{@code rewriteBatchedStatements=truerewriteBatchedStatements=true}、
 * 密码变成 {@code SecretValue!!} 等错误。</p>
 */
public final class LhEnvPlaceholderExpand {

    private LhEnvPlaceholderExpand() {
    }

    /**
     * 向脚本追加：python3（优先）→ envsubst → 转义后的 sed，展开 {@code $fileVar} 指向的文件。
     *
     * @param fileVar bash 变量名，如 {@code SQL_FILE} / {@code JOB_FILE}（不要带 $）
     * @param props   环境变量名列表，如 {@code LH_JDBC_URL}
     */
    public static void appendExpandFile(StringBuilder sb, String fileVar, String... props) {
        if (sb == null || props == null || props.length == 0) {
            return;
        }
        StringBuilder pyKeys = new StringBuilder();
        StringBuilder envsubstKeys = new StringBuilder();
        for (int i = 0; i < props.length; i++) {
            if (i > 0) {
                pyKeys.append(", ");
                envsubstKeys.append(' ');
            }
            pyKeys.append('\'').append(props[i]).append('\'');
            envsubstKeys.append("${").append(props[i]).append('}');
        }

        sb.append("# expand ${LH_*} — 勿对含 & 的 URL/密码使用未转义 sed\n");
        sb.append("if command -v python3 >/dev/null 2>&1; then\n");
        sb.append("  export _LH_EXPAND_FILE=\"$").append(fileVar).append("\"\n");
        sb.append("  python3 - <<'LH_EXPAND_PY'\n");
        sb.append("import os\n");
        sb.append("path = os.environ['_LH_EXPAND_FILE']\n");
        sb.append("text = open(path, encoding='utf-8').read()\n");
        sb.append("for k in (").append(pyKeys).append("):\n");
        sb.append("    text = text.replace('${' + k + '}', os.environ.get(k, ''))\n");
        sb.append("open(path, 'w', encoding='utf-8').write(text)\n");
        sb.append("LH_EXPAND_PY\n");
        sb.append("elif command -v envsubst >/dev/null 2>&1; then\n");
        sb.append("  tmp=$(mktemp); envsubst '").append(envsubstKeys).append("' < \"$")
                .append(fileVar).append("\" > \"$tmp\" && mv \"$tmp\" \"$").append(fileVar).append("\"\n");
        sb.append("else\n");
        sb.append("  _lh_sed_esc() { printf '%s' \"$1\" | sed -e 's/[\\\\&|]/\\\\&/g'; }\n");
        for (String p : props) {
            sb.append("  sed -i.bak -e \"s|\\${").append(p).append("}|$(_lh_sed_esc \"${")
                    .append(p).append("}\")|g\" \"$").append(fileVar).append("\" 2>/dev/null || true\n");
        }
        sb.append("fi\n");
    }
}
