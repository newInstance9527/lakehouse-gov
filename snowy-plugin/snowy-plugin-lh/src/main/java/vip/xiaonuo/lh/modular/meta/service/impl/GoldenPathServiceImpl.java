package vip.xiaonuo.lh.modular.meta.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.domain.entity.GovDomain;
import vip.xiaonuo.lh.modular.domain.mapper.GovDomainMapper;
import vip.xiaonuo.lh.modular.knowledge.support.KbTermGraphHelper;
import vip.xiaonuo.lh.modular.lineage.entity.GovLineageFieldEdge;
import vip.xiaonuo.lh.modular.lineage.mapper.GovLineageFieldEdgeMapper;
import vip.xiaonuo.lh.modular.meta.service.GoldenPathService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.standard.entity.GovStdField;
import vip.xiaonuo.lh.modular.standard.entity.GovStdMapping;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdFieldMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GoldenPathServiceImpl implements GoldenPathService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovDomainMapper domainMapper;
    @Resource
    private GovStdFieldMapper stdFieldMapper;
    @Resource
    private GovStdMappingMapper stdMappingMapper;
    @Resource
    private GovLineageFieldEdgeMapper lineageMapper;
    @Resource
    private GovDqRuleMapper dqRuleMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private KbTermGraphHelper termGraphHelper;
    @Resource
    private LhProperties lhProperties;

    @Override
    public Map<String, Object> status(String ws) {
        List<Map<String, Object>> items = new ArrayList<>();
        long domains = count(domainMapper.selectCount(new QueryWrapper<GovDomain>().lambda()
                .eq(GovDomain::getDeleteFlag, NOT_DELETE)));
        long stdFields = count(stdFieldMapper.selectCount(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getDeleteFlag, NOT_DELETE)));
        long mappings = count(stdMappingMapper.selectCount(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE)));
        items.add(item("domain_std", "域/标准字段与映射",
                domains > 0 && stdFields > 0,
                "domains=" + domains + " fields=" + stdFields + " mappings=" + mappings));

        long edges = count(lineageMapper.selectCount(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE)));
        items.add(item("lineage", "字段血缘边", edges > 0, "gov_lineage_field_edge=" + edges));

        long rules = count(dqRuleMapper.selectCount(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .eq(GovDqRule::getEnabled, 1)));
        items.add(item("quality", "质量规则启用", rules > 0, "enabledRules=" + rules));

        long assets = count(assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)));
        items.add(item("catalog", "资产目录有表", assets > 0, "assets=" + assets));

        long grants = count(grantMapper.selectCount(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)));
        items.add(item("grant", "存在授权 grant", grants > 0, "grants=" + grants));

        double cov = 0;
        Object covObj = null;
        try {
            Map<String, Object> gloss = termGraphHelper.glossaryStats();
            covObj = gloss.get("metricTermCoveragePct");
            if (covObj instanceof Number n) {
                cov = n.doubleValue();
            }
        } catch (Exception ignored) {
            // soft
        }
        double warn = 80;
        if (lhProperties != null && lhProperties.getMetric() != null
                && lhProperties.getMetric().getTermCoverageWarnPct() > 0) {
            warn = lhProperties.getMetric().getTermCoverageWarnPct();
        }
        items.add(item("glossary", "指标术语覆盖 ≥ " + warn + "%",
                cov >= warn,
                "coverage=" + covObj + "% warn=" + warn));

        long ok = items.stream().filter(i -> Boolean.TRUE.equals(i.get("ok"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("passed", ok);
        out.put("total", items.size());
        out.put("ready", ok == items.size());
        out.put("hint", "仓内软探针，现网 Trino 取数仍需人工勾选 M1.4 消费项");
        out.put("ws", StrUtil.blankToDefault(ws, "*"));
        return out;
    }

    private static long count(Long c) {
        return c == null ? 0 : c;
    }

    private static Map<String, Object> item(String id, String label, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }
}
