package vip.xiaonuo.lh.modular.standard.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCode;
import vip.xiaonuo.lh.modular.standard.entity.GovStdField;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdFieldMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准字段/码值 → OM Glossary soft-fail（只写术语头；不双写全量枚举）。
 *
 * @author lakehouse
 * @date 2026/9/23
 */
@Component
public class GovStdGlossarySync {

    private static final Logger log = LoggerFactory.getLogger(GovStdGlossarySync.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private GovStdFieldMapper fieldMapper;
    @Resource
    private GovStdCodeMapper codeMapper;

    /**
     * 字段 upsert 后 soft-fail 写 Glossary；成功则回写 {@code om_glossary_fqn}。
     */
    public Map<String, Object> syncField(GovStdField field) {
        Map<String, Object> out = baseSkipped();
        if (field == null || StrUtil.isBlank(field.getFieldName())) {
            out.put("reason", "no_field");
            return out;
        }
        if (!enabled()) {
            out.put("reason", "disabled");
            return out;
        }
        String glossary = glossaryName();
        if (StrUtil.isBlank(glossary)) {
            out.put("reason", "no_glossary_name");
            return out;
        }
        try {
            String desc = StrUtil.blankToDefault(field.getDescription(), "");
            if (StrUtil.isNotBlank(field.getDataType()) || StrUtil.isNotBlank(field.getUnit())) {
                desc = (StrUtil.isBlank(desc) ? "" : desc + "\n")
                        + "type=" + StrUtil.blankToDefault(field.getDataType(), "—")
                        + " unit=" + StrUtil.blankToDefault(field.getUnit(), "—");
            }
            if (StrUtil.isNotBlank(field.getDomainCode())) {
                desc = (StrUtil.isBlank(desc) ? "" : desc + "\n") + "domain=" + field.getDomainCode();
            }
            Map<String, Object> r = openMetadataClient.upsertGlossaryTerm(
                    glossary, field.getFieldName(), field.getFieldName(), desc);
            String fqn = r == null ? null : String.valueOf(r.get("fqn"));
            if (StrUtil.isNotBlank(fqn) && !fqn.equals(field.getOmGlossaryFqn())) {
                field.setOmGlossaryFqn(fqn);
                fieldMapper.updateById(field);
            } else if (StrUtil.isNotBlank(fqn) && StrUtil.isBlank(field.getOmGlossaryFqn())) {
                field.setOmGlossaryFqn(fqn);
                fieldMapper.updateById(field);
            }
            out.put("skipped", false);
            out.put("ok", true);
            out.put("fqn", fqn);
            out.put("created", r == null ? null : r.get("created"));
            return out;
        } catch (Exception e) {
            log.warn("std glossary soft-fail field={}: {}", field.getFieldName(), e.getMessage());
            out.put("skipped", false);
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 码值头 upsert 后 soft-fail 写 Glossary；<strong>不</strong>同步枚举行到 OM。
     */
    public Map<String, Object> syncCode(GovStdCode code) {
        Map<String, Object> out = baseSkipped();
        if (code == null || StrUtil.isBlank(code.getCodeSetId())) {
            out.put("reason", "no_code");
            return out;
        }
        if (!enabled()) {
            out.put("reason", "disabled");
            return out;
        }
        String glossary = glossaryName();
        if (StrUtil.isBlank(glossary)) {
            out.put("reason", "no_glossary_name");
            return out;
        }
        try {
            String display = StrUtil.blankToDefault(code.getName(), code.getCodeSetId());
            String desc = "标准码值集 " + code.getCodeSetId()
                    + (StrUtil.isNotBlank(code.getFieldName()) ? " · 绑定字段 " + code.getFieldName() : "")
                    + "（枚举仅存门户 gov_std_code_item，不双写 OM）";
            Map<String, Object> r = openMetadataClient.upsertGlossaryTerm(
                    glossary, code.getCodeSetId(), display, desc);
            String fqn = r == null ? null : String.valueOf(r.get("fqn"));
            if (StrUtil.isNotBlank(fqn) && !fqn.equals(StrUtil.blankToDefault(code.getOmGlossaryFqn(), ""))) {
                code.setOmGlossaryFqn(fqn);
                codeMapper.updateById(code);
            }
            out.put("skipped", false);
            out.put("ok", true);
            out.put("fqn", fqn);
            out.put("created", r == null ? null : r.get("created"));
            out.put("enumsSynced", false);
            return out;
        } catch (Exception e) {
            log.warn("std glossary soft-fail code={}: {}", code.getCodeSetId(), e.getMessage());
            out.put("skipped", false);
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            out.put("enumsSynced", false);
            return out;
        }
    }

    /** soft-fail 读术语描述；失败返回 null */
    public String loadTermDescription(String omGlossaryFqn) {
        if (StrUtil.isBlank(omGlossaryFqn) || !enabled()) {
            return null;
        }
        try {
            JSONObject term = openMetadataClient.getGlossaryTermByFqn(omGlossaryFqn.trim());
            return term == null ? null : term.getStr("description");
        } catch (Exception e) {
            log.warn("std glossary read soft-fail fqn={}: {}", omGlossaryFqn, e.getMessage());
            return null;
        }
    }

    private boolean enabled() {
        LhProperties.Openmetadata om = lhProperties.getOpenmetadata();
        return om != null && om.isGlossarySync();
    }

    private String glossaryName() {
        LhProperties.Openmetadata om = lhProperties.getOpenmetadata();
        return om == null ? null : om.getGlossaryName();
    }

    private static Map<String, Object> baseSkipped() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skipped", true);
        out.put("ok", false);
        return out;
    }
}
