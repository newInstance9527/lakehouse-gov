package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCode;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCodeItem;
import vip.xiaonuo.lh.modular.standard.entity.GovStdMapping;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeItemMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 枚举/码值质量规则 → {@code gov_std_code} / {@code gov_std_code_item}。
 */
@Component
public class GovDqStdCodeResolver {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Pattern CODE_SET_IN_EXPR = Pattern.compile(
            "(?i)(?:code[_\\s-]?set(?:[_\\s-]?id)?|std[_\\s-]?code)\\s*[=:]\\s*([A-Za-z0-9_\\-.]+)");

    @Resource
    private GovStdCodeMapper codeMapper;
    @Resource
    private GovStdCodeItemMapper codeItemMapper;
    @Resource
    private GovStdMappingMapper mappingMapper;

    public static final class Resolved {
        public String codeSetId;
        public String codeId;
        public List<String> itemCodes = new ArrayList<>();
        public String source;
    }

    public static boolean isEnumRule(GovDqRule rule) {
        if (rule == null) {
            return false;
        }
        String raw = StrUtil.blankToDefault(rule.getRuleType(), rule.getRuleCode()).toLowerCase(Locale.ROOT);
        return raw.contains("枚举") || raw.contains("enum") || raw.contains("码值");
    }

    /** 从规则显式字段 / expr / 映射 / 字段名解析码值集并加载枚举项。 */
    public Resolved resolve(GovDqRule rule) {
        if (rule == null) {
            return null;
        }
        String ws = StrUtil.blankToDefault(rule.getWs(), "default");
        String codeSetId = firstNonBlank(
                rule.getStdCodeSetId(),
                parseCodeSetFromExpr(rule.getExprText()));
        String source = StrUtil.isNotBlank(rule.getStdCodeSetId()) ? "rule.std_code_set_id"
                : (StrUtil.isNotBlank(codeSetId) ? "expr" : null);

        if (StrUtil.isBlank(codeSetId)) {
            Resolved fromMap = resolveViaMapping(ws, rule.getTableName(), rule.getFieldName());
            if (fromMap != null) {
                return fromMap;
            }
            Resolved fromField = resolveViaFieldName(ws, rule.getFieldName());
            if (fromField != null) {
                return fromField;
            }
            return null;
        }
        return loadItems(ws, codeSetId, source == null ? "code_set" : source);
    }

    public Resolved loadItems(String ws, String codeSetId, String source) {
        if (StrUtil.isBlank(codeSetId)) {
            return null;
        }
        GovStdCode code = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                .eq(StrUtil.isNotBlank(ws), GovStdCode::getWs, ws)
                .eq(GovStdCode::getCodeSetId, codeSetId.trim())
                .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (code == null) {
            code = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                    .eq(GovStdCode::getId, codeSetId.trim())
                    .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
        }
        if (code == null) {
            Resolved empty = new Resolved();
            empty.codeSetId = codeSetId.trim();
            empty.source = source;
            return empty;
        }
        List<GovStdCodeItem> items = codeItemMapper.selectList(new QueryWrapper<GovStdCodeItem>().lambda()
                .eq(GovStdCodeItem::getCodeId, code.getId())
                .eq(GovStdCodeItem::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovStdCodeItem::getSortNo));
        Resolved r = new Resolved();
        r.codeId = code.getId();
        r.codeSetId = StrUtil.blankToDefault(code.getCodeSetId(), codeSetId.trim());
        r.source = source;
        Set<String> seen = new LinkedHashSet<>();
        for (GovStdCodeItem it : items) {
            if (it != null && StrUtil.isNotBlank(it.getItemCode()) && seen.add(it.getItemCode().trim())) {
                r.itemCodes.add(it.getItemCode().trim());
            }
        }
        return r;
    }

    public static String parseCodeSetFromExpr(String expr) {
        if (StrUtil.isBlank(expr)) {
            return null;
        }
        Matcher m = CODE_SET_IN_EXPR.matcher(expr.trim());
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private Resolved resolveViaMapping(String ws, String tableName, String fieldName) {
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(fieldName)) {
            return null;
        }
        String shortTable = tableName.contains(".")
                ? tableName.substring(tableName.lastIndexOf('.') + 1)
                : tableName;
        String field = fieldName.trim();
        GovStdMapping map = mappingMapper.selectOne(new QueryWrapper<GovStdMapping>().lambda()
                .eq(StrUtil.isNotBlank(ws), GovStdMapping::getWs, ws)
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovStdMapping::getSrcField, field)
                        .or().eq(GovStdMapping::getStdFieldName, field))
                .and(w -> w.eq(GovStdMapping::getSrcObject, tableName.trim())
                        .or().eq(GovStdMapping::getSrcObject, shortTable)
                        .or().likeRight(GovStdMapping::getSrcObject, shortTable)
                        .or().eq(GovStdMapping::getTargetTable, tableName.trim())
                        .or().eq(GovStdMapping::getTargetTable, shortTable)
                        .or().like(GovStdMapping::getTargetTable, shortTable))
                .isNotNull(GovStdMapping::getCodeSetId)
                .ne(GovStdMapping::getCodeSetId, "")
                .last("LIMIT 1"));
        if (map == null || StrUtil.isBlank(map.getCodeSetId())) {
            return null;
        }
        return loadItems(ws, map.getCodeSetId(), "gov_std_mapping");
    }

    private Resolved resolveViaFieldName(String ws, String fieldName) {
        if (StrUtil.isBlank(fieldName)) {
            return null;
        }
        GovStdCode code = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                .eq(StrUtil.isNotBlank(ws), GovStdCode::getWs, ws)
                .eq(GovStdCode::getFieldName, fieldName.trim())
                .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (code == null) {
            return null;
        }
        return loadItems(ws, code.getCodeSetId(), "gov_std_code.field_name");
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
