/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.standard.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCode;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCodeItem;
import vip.xiaonuo.lh.modular.standard.entity.GovStdDetectResult;
import vip.xiaonuo.lh.modular.standard.entity.GovStdField;
import vip.xiaonuo.lh.modular.standard.entity.GovStdMapping;
import vip.xiaonuo.lh.modular.standard.entity.GovStdNaming;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeItemMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdDetectResultMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdFieldMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdNamingMapper;
import vip.xiaonuo.lh.modular.standard.param.GovStdCodeUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdFieldUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdIdParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdMappingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdNamingUpsertParam;
import vip.xiaonuo.lh.modular.standard.param.GovStdPageParam;
import vip.xiaonuo.lh.modular.standard.result.GovStdCodeVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdDetectVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdFieldVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdMappingVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdNamingVo;
import vip.xiaonuo.lh.modular.standard.service.GovStdService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据标准 Service 实现
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Service
public class GovStdServiceImpl implements GovStdService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovStdFieldMapper fieldMapper;
    @Resource
    private GovStdCodeMapper codeMapper;
    @Resource
    private GovStdCodeItemMapper codeItemMapper;
    @Resource
    private GovStdNamingMapper namingMapper;
    @Resource
    private GovStdMappingMapper mappingMapper;
    @Resource
    private GovStdDetectResultMapper detectMapper;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        long fields = fieldMapper.selectCount(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getWs, workspace)
                .eq(GovStdField::getDeleteFlag, NOT_DELETE));
        long codes = codeMapper.selectCount(new QueryWrapper<GovStdCode>().lambda()
                .eq(GovStdCode::getWs, workspace)
                .eq(GovStdCode::getDeleteFlag, NOT_DELETE));
        long mappings = mappingMapper.selectCount(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getWs, workspace)
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE));
        long failOrWarn = detectMapper.selectCount(new QueryWrapper<GovStdDetectResult>().lambda()
                .eq(GovStdDetectResult::getWs, workspace)
                .in(GovStdDetectResult::getStatus, List.of("fail", "warn")));
        long detectTotal = detectMapper.selectCount(new QueryWrapper<GovStdDetectResult>().lambda()
                .eq(GovStdDetectResult::getWs, workspace));
        long okDetect = detectMapper.selectCount(new QueryWrapper<GovStdDetectResult>().lambda()
                .eq(GovStdDetectResult::getWs, workspace)
                .eq(GovStdDetectResult::getStatus, "ok"));
        int rate = detectTotal == 0 ? 100 : (int) Math.round(okDetect * 100.0 / detectTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldCount", fields);
        out.put("codeCount", codes);
        out.put("mappingCount", mappings);
        out.put("complianceRate", rate);
        out.put("pendingFixCount", failOrWarn);
        out.put("ws", workspace);
        return out;
    }

    @Override
    public Page<GovStdFieldVo> pageFields(GovStdPageParam param) {
        QueryWrapper<GovStdField> qw = new QueryWrapper<GovStdField>().checkSqlInjection();
        qw.lambda().eq(GovStdField::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovStdField::getWs, ws);
        String domain = firstNonBlank(param.getDomain(), param.getDomainCode());
        if (StrUtil.isNotBlank(domain)) {
            qw.lambda().eq(GovStdField::getDomainCode, domain.trim());
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovStdField::getComplianceStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovStdField::getFieldName, kw)
                    .or().like(GovStdField::getDataType, kw)
                    .or().like(GovStdField::getUnit, kw)
                    .or().like(GovStdField::getDescription, kw)
                    .or().like(GovStdField::getDomainCode, kw));
        }
        applySort(qw, param, "update_time");
        Page<GovStdField> raw = fieldMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Map<String, Long> mappedCounts = countMappingsByField(ws,
                raw.getRecords().stream().map(GovStdField::getFieldName).toList());
        Page<GovStdFieldVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream()
                .map(f -> toFieldVo(f, mappedCounts.getOrDefault(f.getFieldName(), 0L).intValue()))
                .toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovStdFieldVo upsertField(GovStdFieldUpsertParam param) {
        String fieldName = firstNonBlank(param.getFieldName(), param.getName());
        if (StrUtil.isBlank(fieldName)) {
            throw new CommonException("字段名不能为空");
        }
        fieldName = fieldName.trim();
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        GovStdField existing = fieldMapper.selectOne(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getWs, ws)
                .eq(GovStdField::getFieldName, fieldName)
                .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        String dataType = firstNonBlank(param.getDataType(), param.getType());
        String unit = StrUtil.blankToDefault(param.getUnit(), "—");
        String domain = firstNonBlank(param.getDomainCode(), param.getDomain(), "通用");
        String desc = firstNonBlank(param.getDescription(), param.getDesc());

        if (existing == null) {
            existing = new GovStdField();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(ws);
            existing.setFieldName(fieldName);
            existing.setComplianceStatus("ok");
            existing.setDeleteFlag(NOT_DELETE);
            existing.setDataType(dataType);
            existing.setUnit(unit);
            existing.setDomainCode(domain);
            existing.setDescription(desc);
            existing.setRemark(param.getRemark());
            fieldMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            // 显式传空字符串时清空类型；null 表示不改（本 upsert 始终带字段，空则置 null）
            existing.setDataType(StrUtil.isBlank(dataType) ? null : dataType);
            existing.setUnit(unit);
            existing.setDomainCode(domain);
            existing.setDescription(desc);
            if (param.getRemark() != null) {
                existing.setRemark(param.getRemark());
            }
            fieldMapper.updateById(existing);
        }
        Map<String, Long> mapped = countMappingsByField(ws, List.of(fieldName));
        return toFieldVo(existing, mapped.getOrDefault(fieldName, 0L).intValue());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteField(GovStdIdParam param) {
        GovStdField row = resolveField(param.getId(), param.getWs());
        // 释放唯一键，便于同名重建
        row.setFieldName(truncateKey(row.getFieldName() + "__del_" + row.getId(), 128));
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        fieldMapper.updateById(row);
        fieldMapper.deleteById(row.getId());
    }

    @Override
    public Page<GovStdCodeVo> pageCodes(GovStdPageParam param) {
        QueryWrapper<GovStdCode> qw = new QueryWrapper<GovStdCode>().checkSqlInjection();
        qw.lambda().eq(GovStdCode::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovStdCode::getWs, ws);
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovStdCode::getComplianceStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovStdCode::getCodeSetId, kw)
                    .or().like(GovStdCode::getName, kw)
                    .or().like(GovStdCode::getFieldName, kw)
                    .or().like(GovStdCode::getMappedSummary, kw));
        }
        applySort(qw, param, "update_time");
        Page<GovStdCode> raw = codeMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        List<String> codeIds = raw.getRecords().stream().map(GovStdCode::getId).toList();
        Map<String, List<GovStdCodeItem>> itemsByCode = loadItems(codeIds);
        Page<GovStdCodeVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream()
                .map(c -> toCodeVo(c, itemsByCode.getOrDefault(c.getId(), List.of())))
                .toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovStdCodeVo upsertCode(GovStdCodeUpsertParam param) {
        String codeSetId = firstNonBlank(param.getCodeSetId(), param.getId());
        String fieldName = firstNonBlank(param.getFieldName(), param.getField());
        if (StrUtil.isBlank(codeSetId) || StrUtil.isBlank(param.getName()) || StrUtil.isBlank(fieldName)) {
            throw new CommonException("码值 ID、名称与绑定字段不能为空");
        }
        codeSetId = codeSetId.trim();
        fieldName = fieldName.trim();
        List<GovStdCodeUpsertParam.Item> items = resolveItems(param);
        if (items.isEmpty()) {
            throw new CommonException("请至少提供一条枚举值");
        }
        for (GovStdCodeUpsertParam.Item it : items) {
            if (StrUtil.isBlank(it.getCode()) || StrUtil.isBlank(it.getLabel())) {
                throw new CommonException("枚举值需同时填写编码与含义");
            }
        }
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        GovStdCode existing = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                .eq(GovStdCode::getWs, ws)
                .eq(GovStdCode::getCodeSetId, codeSetId)
                .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        String mapped = firstNonBlank(param.getMappedSummary(), param.getMapped());

        if (existing == null) {
            existing = new GovStdCode();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(ws);
            existing.setCodeSetId(codeSetId);
            existing.setName(param.getName().trim());
            existing.setFieldName(fieldName);
            existing.setMappedSummary(mapped);
            existing.setComplianceStatus("ok");
            existing.setDeleteFlag(NOT_DELETE);
            existing.setRemark(param.getRemark());
            codeMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setName(param.getName().trim());
            existing.setFieldName(fieldName);
            if (mapped != null) {
                existing.setMappedSummary(mapped);
            }
            if (param.getRemark() != null) {
                existing.setRemark(param.getRemark());
            }
            codeMapper.updateById(existing);
        }

        codeItemMapper.physicalDeleteByCodeId(existing.getId());
        int sort = 0;
        List<GovStdCodeItem> savedItems = new ArrayList<>();
        for (GovStdCodeUpsertParam.Item it : items) {
            GovStdCodeItem row = new GovStdCodeItem();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setStatus("active");
            row.setWs(ws);
            row.setCodeId(existing.getId());
            row.setItemCode(it.getCode().trim());
            row.setItemLabel(it.getLabel().trim());
            row.setSortNo(sort++);
            row.setDeleteFlag(NOT_DELETE);
            codeItemMapper.insert(row);
            savedItems.add(row);
        }
        return toCodeVo(existing, savedItems);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCode(GovStdIdParam param) {
        GovStdCode row = resolveCode(param.getId(), param.getWs());
        codeItemMapper.physicalDeleteByCodeId(row.getId());
        row.setCodeSetId(truncateKey(row.getCodeSetId() + "__del_" + row.getId(), 64));
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        codeMapper.updateById(row);
        codeMapper.deleteById(row.getId());
    }

    @Override
    public Page<GovStdNamingVo> pageNamings(GovStdPageParam param) {
        QueryWrapper<GovStdNaming> qw = new QueryWrapper<GovStdNaming>().checkSqlInjection();
        qw.lambda().eq(GovStdNaming::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovStdNaming::getWs, ws);
        if (StrUtil.isNotBlank(param.getLayer())) {
            qw.lambda().eq(GovStdNaming::getLayer, param.getLayer().trim());
        }
        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovStdNaming::getPattern, kw)
                    .or().like(GovStdNaming::getExample, kw)
                    .or().like(GovStdNaming::getLayer, kw));
        }
        applySort(qw, param, "update_time");
        Page<GovStdNaming> raw = namingMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovStdNamingVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(this::toNamingVo).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovStdNamingVo upsertNaming(GovStdNamingUpsertParam param) {
        String pattern = param.getPattern().trim();
        String layer = param.getLayer().trim();
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        GovStdNaming existing = namingMapper.selectOne(new QueryWrapper<GovStdNaming>().lambda()
                .eq(GovStdNaming::getWs, ws)
                .eq(GovStdNaming::getLayer, layer)
                .eq(GovStdNaming::getPattern, pattern)
                .eq(GovStdNaming::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        String example = StrUtil.blankToDefault(param.getExample(), "—");
        String status = StrUtil.blankToDefault(param.getStatus(), "ok");

        if (existing == null) {
            existing = new GovStdNaming();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setWs(ws);
            existing.setPattern(pattern);
            existing.setLayer(layer);
            existing.setExample(example);
            existing.setStatus(status);
            existing.setDeleteFlag(NOT_DELETE);
            existing.setRemark(param.getRemark());
            namingMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setExample(example);
            existing.setStatus(status);
            if (param.getRemark() != null) {
                existing.setRemark(param.getRemark());
            }
            namingMapper.updateById(existing);
        }
        return toNamingVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNaming(GovStdIdParam param) {
        GovStdNaming row = resolveNaming(param.getId(), param.getWs());
        row.setPattern(truncateKey(row.getPattern() + "__del_" + row.getId(), 256));
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        namingMapper.updateById(row);
        namingMapper.deleteById(row.getId());
    }

    @Override
    public Page<GovStdMappingVo> pageMappings(GovStdPageParam param) {
        QueryWrapper<GovStdMapping> qw = new QueryWrapper<GovStdMapping>().checkSqlInjection();
        qw.lambda().eq(GovStdMapping::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovStdMapping::getWs, ws);
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovStdMapping::getStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(param.getDsId())) {
            qw.lambda().eq(GovStdMapping::getDsId, param.getDsId().trim());
        }
        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovStdMapping::getSrcObject, kw)
                    .or().like(GovStdMapping::getSrcField, kw)
                    .or().like(GovStdMapping::getStdFieldName, kw)
                    .or().like(GovStdMapping::getTargetTable, kw)
                    .or().like(GovStdMapping::getRuleText, kw)
                    .or().like(GovStdMapping::getCodeSetId, kw));
        }
        applySort(qw, param, "update_time");
        Page<GovStdMapping> raw = mappingMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovStdMappingVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(this::toMappingVo).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovStdMappingVo upsertMapping(GovStdMappingUpsertParam param) {
        String srcObject = param.getSrcObject();
        String srcField = param.getSrcField();
        if (StrUtil.isBlank(srcObject) || StrUtil.isBlank(srcField)) {
            String[] parts = splitSrc(param.getSrc());
            if (parts != null) {
                srcObject = parts[0];
                srcField = parts[1];
            }
        }
        if (StrUtil.isBlank(srcObject) || StrUtil.isBlank(srcField)) {
            throw new CommonException("源对象与源字段不能为空（src 或 srcObject+srcField）");
        }
        String stdFieldName = firstNonBlank(param.getStdFieldName(), parseStdField(param.getStd()));
        if (StrUtil.isBlank(stdFieldName)) {
            throw new CommonException("标准字段名不能为空");
        }
        stdFieldName = stdFieldName.trim();
        String codeSetId = firstNonBlank(param.getCodeSetId(), parseCodeSet(param.getStd()));
        String targetTable = StrUtil.blankToDefault(firstNonBlank(param.getTargetTable(), param.getTable()), "");
        String ruleText = firstNonBlank(param.getRuleText(), param.getRule());
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);

        GovStdMapping existing = mappingMapper.selectOne(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getWs, ws)
                .eq(GovStdMapping::getSrcObject, srcObject.trim())
                .eq(GovStdMapping::getSrcField, srcField.trim())
                .eq(GovStdMapping::getTargetTable, targetTable)
                .eq(GovStdMapping::getStdFieldName, stdFieldName)
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));

        if (existing == null) {
            existing = new GovStdMapping();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setWs(ws);
            existing.setSrcObject(srcObject.trim());
            existing.setSrcField(srcField.trim());
            existing.setStdFieldName(stdFieldName);
            existing.setCodeSetId(codeSetId);
            existing.setTargetTable(targetTable);
            existing.setRuleText(ruleText);
            existing.setStatus(StrUtil.blankToDefault(param.getStatus(), "ok"));
            existing.setDsId(param.getDsId());
            existing.setAssetId(param.getAssetId());
            existing.setEtlJobId(param.getEtlJobId());
            existing.setDeleteFlag(NOT_DELETE);
            existing.setRemark(param.getRemark());
            mappingMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setCodeSetId(codeSetId);
            existing.setRuleText(ruleText);
            if (StrUtil.isNotBlank(param.getStatus())) {
                existing.setStatus(param.getStatus());
            }
            if (param.getDsId() != null) {
                existing.setDsId(param.getDsId());
            }
            if (param.getAssetId() != null) {
                existing.setAssetId(param.getAssetId());
            }
            if (param.getEtlJobId() != null) {
                existing.setEtlJobId(param.getEtlJobId());
            }
            if (param.getRemark() != null) {
                existing.setRemark(param.getRemark());
            }
            mappingMapper.updateById(existing);
        }
        return toMappingVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMapping(GovStdIdParam param) {
        if (StrUtil.isBlank(param.getId())) {
            throw new CommonException("id不能为空");
        }
        GovStdMapping row = mappingMapper.selectById(param.getId());
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("映射不存在: {}", param.getId());
        }
        row.setSrcField(truncateKey(row.getSrcField() + "__del_" + row.getId(), 128));
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        mappingMapper.updateById(row);
        mappingMapper.deleteById(row.getId());
    }

    @Override
    public Page<GovStdDetectVo> pageDetects(GovStdPageParam param) {
        QueryWrapper<GovStdDetectResult> qw = new QueryWrapper<GovStdDetectResult>().checkSqlInjection();
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovStdDetectResult::getWs, ws);
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovStdDetectResult::getStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovStdDetectResult::getTableName, kw)
                    .or().like(GovStdDetectResult::getFieldName, kw)
                    .or().like(GovStdDetectResult::getStdRef, kw)
                    .or().like(GovStdDetectResult::getCheckType, kw)
                    .or().like(GovStdDetectResult::getResultText, kw));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            boolean asc = CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder());
            qw.orderBy(true, asc, StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovStdDetectResult::getCheckedAt);
        }
        Page<GovStdDetectResult> raw = detectMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovStdDetectVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(this::toDetectVo).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runLandingDetect(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String runId = "std-detect-" + IdUtil.getSnowflakeNextIdStr();
        Date now = new Date();
        List<GovStdMapping> mappings = mappingMapper.selectList(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getWs, workspace)
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE));
        int ok = 0;
        int warn = 0;
        int fail = 0;
        int written = 0;
        for (GovStdMapping m : mappings) {
            String table = StrUtil.blankToDefault(m.getTargetTable(), m.getSrcObject());
            String field = StrUtil.blankToDefault(m.getStdFieldName(), m.getSrcField());
            String stdRef = m.getStdFieldName();

            // 1) 标准字段是否登记
            GovStdField stdField = fieldMapper.selectOne(new QueryWrapper<GovStdField>().lambda()
                    .eq(GovStdField::getWs, workspace)
                    .eq(GovStdField::getFieldName, m.getStdFieldName())
                    .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (stdField == null) {
                recordDetectResult(workspace, table, field, stdRef, "标准字段存在性",
                        "标准字段未登记: " + m.getStdFieldName(), "fail", m.getAssetId(), runId);
                fail++;
                written++;
                continue;
            }
            recordDetectResult(workspace, table, field, stdRef, "标准字段存在性",
                    "标准字段已登记 · type=" + StrUtil.blankToDefault(stdField.getDataType(), "—"),
                    "ok", m.getAssetId(), runId);
            ok++;
            written++;

            // 2) 类型/单位
            boolean hasType = StrUtil.isNotBlank(stdField.getDataType());
            boolean hasUnit = StrUtil.isNotBlank(stdField.getUnit()) && !"—".equals(stdField.getUnit());
            String typeStatus = hasType ? "ok" : "warn";
            if (!hasType) {
                warn++;
            } else {
                ok++;
            }
            recordDetectResult(workspace, table, field, stdRef, "单位+类型",
                    "type=" + StrUtil.blankToDefault(stdField.getDataType(), "缺失")
                            + " · unit=" + StrUtil.blankToDefault(stdField.getUnit(), "—")
                            + (hasUnit ? "" : "（单位未填）"),
                    typeStatus, m.getAssetId(), runId);
            written++;

            // 3) 码值合规（映射绑定码值集时）
            if (StrUtil.isNotBlank(m.getCodeSetId())) {
                GovStdCode code = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                        .eq(GovStdCode::getWs, workspace)
                        .eq(GovStdCode::getCodeSetId, m.getCodeSetId().trim())
                        .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                        .last("LIMIT 1"));
                if (code == null) {
                    recordDetectResult(workspace, table, field, m.getCodeSetId(), "码值合规",
                            "映射绑定码值集不存在: " + m.getCodeSetId(), "fail", m.getAssetId(), runId);
                    fail++;
                    written++;
                } else {
                    long itemCnt = codeItemMapper.selectCount(new QueryWrapper<GovStdCodeItem>().lambda()
                            .eq(GovStdCodeItem::getCodeId, code.getId())
                            .eq(GovStdCodeItem::getDeleteFlag, NOT_DELETE));
                    String st = itemCnt > 0 ? "ok" : "warn";
                    if (itemCnt > 0) {
                        ok++;
                    } else {
                        warn++;
                    }
                    recordDetectResult(workspace, table, field, m.getCodeSetId(), "码值合规",
                            "码值集 " + m.getCodeSetId() + " · 枚举 " + itemCnt + " 条"
                                    + (itemCnt > 0 ? "（元数据抽检通过；行级抽检待质量作业）" : "（无枚举项）"),
                            st, m.getAssetId(), runId);
                    written++;
                }
            }

            // 4) 映射自身状态
            String mapSt = StrUtil.blankToDefault(m.getStatus(), "ok").toLowerCase(Locale.ROOT);
            if ("fail".equals(mapSt) || "warn".equals(mapSt)) {
                recordDetectResult(workspace, table, field, stdRef, "映射健康度",
                        "映射状态=" + mapSt + " · " + StrUtil.blankToDefault(m.getRuleText(), "无规则说明"),
                        mapSt, m.getAssetId(), runId);
                if ("fail".equals(mapSt)) {
                    fail++;
                } else {
                    warn++;
                }
                written++;
            }
        }

        // 无映射时：对已登记标准字段做存在性占位检测，避免空跑无反馈
        if (mappings.isEmpty()) {
            List<GovStdField> fields = fieldMapper.selectList(new QueryWrapper<GovStdField>().lambda()
                    .eq(GovStdField::getWs, workspace)
                    .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 50"));
            for (GovStdField f : fields) {
                recordDetectResult(workspace, "_registry", f.getFieldName(), f.getFieldName(),
                        "标准字段登记", "已登记但无源→标准映射；请先补录映射", "warn", null, runId);
                warn++;
                written++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("ws", workspace);
        out.put("mappingCount", mappings.size());
        out.put("written", written);
        out.put("ok", ok);
        out.put("warn", warn);
        out.put("fail", fail);
        out.put("checkedAt", now);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordDetectResult(String ws, String tableName, String fieldName, String stdRef,
                                   String checkType, String resultText, String status,
                                   String assetId, String runId) {
        GovStdDetectResult d = new GovStdDetectResult();
        d.setId(IdUtil.getSnowflakeNextIdStr());
        d.setWs(StrUtil.blankToDefault(ws, WS_DEFAULT));
        d.setTableName(StrUtil.blankToDefault(tableName, "_"));
        d.setFieldName(StrUtil.blankToDefault(fieldName, "_"));
        d.setStdRef(StrUtil.blankToDefault(stdRef, fieldName));
        d.setCheckType(StrUtil.blankToDefault(checkType, "合规"));
        d.setResultText(resultText);
        String st = StrUtil.blankToDefault(status, "ok").toLowerCase(Locale.ROOT);
        if (!List.of("ok", "warn", "fail").contains(st)) {
            st = "warn";
        }
        d.setStatus(st);
        d.setAssetId(assetId);
        d.setCheckedAt(new Date());
        d.setRunId(runId);
        d.setCreateTime(new Date());
        try {
            d.setCreateUser(vip.xiaonuo.lh.core.auth.LhLoginUsers.requireUserId());
        } catch (Exception e) {
            d.setCreateUser("system");
        }
        detectMapper.insert(d);
    }

    @Override
    public Map<String, Object> metaOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domains", List.of(
                Map.of("value", "交易", "label", "交易"),
                Map.of("value", "用户", "label", "用户"),
                Map.of("value", "商品", "label", "商品"),
                Map.of("value", "通用", "label", "通用"),
                Map.of("value", "营销", "label", "营销"),
                Map.of("value", "财务", "label", "财务"),
                Map.of("value", "门店", "label", "门店")));
        out.put("layers", List.of(
                "ODS", "DWD", "DWS", "ADS", "DIM", "任务", "消息", "临时", "视图", "接口", "指标", "质量", "其他"));
        out.put("complianceStatuses", List.of(
                Map.of("value", "ok", "label", "合规"),
                Map.of("value", "warn", "label", "告警/待修复"),
                Map.of("value", "fail", "label", "阻断")));
        out.put("checkTypes", List.of("码值合规", "单位+类型", "类型+非空", "脱敏合规", "主键唯一", "时区", "源码值覆盖"));
        return out;
    }

    // ---- helpers ----

    private Map<String, Long> countMappingsByField(String ws, List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return Map.of();
        }
        List<String> names = fieldNames.stream().filter(StrUtil::isNotBlank).distinct().toList();
        if (names.isEmpty()) {
            return Map.of();
        }
        List<GovStdMapping> list = mappingMapper.selectList(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getWs, ws)
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE)
                .in(GovStdMapping::getStdFieldName, names));
        return list.stream().collect(Collectors.groupingBy(GovStdMapping::getStdFieldName, Collectors.counting()));
    }

    private Map<String, List<GovStdCodeItem>> loadItems(List<String> codeIds) {
        if (codeIds == null || codeIds.isEmpty()) {
            return Map.of();
        }
        List<GovStdCodeItem> items = codeItemMapper.selectList(new QueryWrapper<GovStdCodeItem>().lambda()
                .in(GovStdCodeItem::getCodeId, codeIds)
                .eq(GovStdCodeItem::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovStdCodeItem::getSortNo));
        return items.stream().collect(Collectors.groupingBy(GovStdCodeItem::getCodeId));
    }

    private List<GovStdCodeUpsertParam.Item> resolveItems(GovStdCodeUpsertParam param) {
        if (param.getItems() != null && !param.getItems().isEmpty()) {
            return param.getItems().stream()
                    .filter(i -> i != null && (StrUtil.isNotBlank(i.getCode()) || StrUtil.isNotBlank(i.getLabel())))
                    .toList();
        }
        return parseValues(param.getValues());
    }

    private List<GovStdCodeUpsertParam.Item> parseValues(String values) {
        List<GovStdCodeUpsertParam.Item> list = new ArrayList<>();
        if (StrUtil.isBlank(values)) {
            return list;
        }
        for (String part : values.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int i = p.indexOf('=');
            GovStdCodeUpsertParam.Item it = new GovStdCodeUpsertParam.Item();
            if (i < 0) {
                it.setCode(p);
                it.setLabel(p);
            } else {
                it.setCode(p.substring(0, i).trim());
                it.setLabel(p.substring(i + 1).trim());
            }
            list.add(it);
        }
        return list;
    }

    private String[] splitSrc(String src) {
        if (StrUtil.isBlank(src)) {
            return null;
        }
        int i = src.lastIndexOf('.');
        if (i <= 0 || i >= src.length() - 1) {
            return null;
        }
        return new String[]{src.substring(0, i).trim(), src.substring(i + 1).trim()};
    }

    private String parseStdField(String std) {
        if (StrUtil.isBlank(std)) {
            return null;
        }
        int i = std.indexOf('(');
        return (i > 0 ? std.substring(0, i) : std).trim();
    }

    private String parseCodeSet(String std) {
        if (StrUtil.isBlank(std)) {
            return null;
        }
        int a = std.indexOf('(');
        int b = std.indexOf(')');
        if (a >= 0 && b > a) {
            return std.substring(a + 1, b).trim();
        }
        return null;
    }

    private <T> void applySort(QueryWrapper<T> qw, GovStdPageParam param, String defaultCol) {
        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            boolean asc = CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder());
            qw.orderBy(true, asc, StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.orderBy(true, false, defaultCol);
        }
    }

    private GovStdFieldVo toFieldVo(GovStdField f, int mapped) {
        GovStdFieldVo vo = new GovStdFieldVo();
        vo.setId(f.getId());
        vo.setName(f.getFieldName());
        vo.setFieldName(f.getFieldName());
        vo.setType(f.getDataType());
        vo.setDataType(f.getDataType());
        vo.setUnit(f.getUnit());
        vo.setDesc(f.getDescription());
        vo.setDescription(f.getDescription());
        vo.setDomain(f.getDomainCode());
        vo.setDomainCode(f.getDomainCode());
        vo.setMapped(mapped);
        vo.setStatus(f.getComplianceStatus());
        vo.setComplianceStatus(f.getComplianceStatus());
        vo.setLifecycleStatus(f.getStatus());
        vo.setOmGlossaryFqn(f.getOmGlossaryFqn());
        vo.setRevision(f.getRevision());
        vo.setUpdateTime(f.getUpdateTime());
        return vo;
    }

    private GovStdCodeVo toCodeVo(GovStdCode c, List<GovStdCodeItem> items) {
        GovStdCodeVo vo = new GovStdCodeVo();
        vo.setPkId(c.getId());
        vo.setId(c.getCodeSetId());
        vo.setCodeSetId(c.getCodeSetId());
        vo.setName(c.getName());
        vo.setField(c.getFieldName());
        vo.setFieldName(c.getFieldName());
        List<GovStdCodeVo.Item> itemVos = items.stream().map(i -> {
            GovStdCodeVo.Item it = new GovStdCodeVo.Item();
            it.setCode(i.getItemCode());
            it.setLabel(i.getItemLabel());
            return it;
        }).toList();
        vo.setItems(itemVos);
        vo.setValueList(itemVos);
        vo.setCount(itemVos.size());
        vo.setValues(itemVos.stream()
                .map(i -> i.getCode() + "=" + i.getLabel())
                .collect(Collectors.joining(", ")));
        vo.setMapped(c.getMappedSummary());
        vo.setMappedSummary(c.getMappedSummary());
        vo.setStatus(c.getComplianceStatus());
        vo.setComplianceStatus(c.getComplianceStatus());
        vo.setRevision(c.getRevision());
        vo.setUpdateTime(c.getUpdateTime());
        return vo;
    }

    private GovStdNamingVo toNamingVo(GovStdNaming n) {
        GovStdNamingVo vo = new GovStdNamingVo();
        vo.setId(n.getId());
        vo.setPattern(n.getPattern());
        vo.setExample(n.getExample());
        vo.setLayer(n.getLayer());
        vo.setStatus(n.getStatus());
        vo.setRevision(n.getRevision());
        vo.setUpdateTime(n.getUpdateTime());
        return vo;
    }

    private GovStdMappingVo toMappingVo(GovStdMapping m) {
        GovStdMappingVo vo = new GovStdMappingVo();
        vo.setId(m.getId());
        vo.setSrcObject(m.getSrcObject());
        vo.setSrcField(m.getSrcField());
        vo.setSrc(m.getSrcObject() + "." + m.getSrcField());
        vo.setStdFieldName(m.getStdFieldName());
        vo.setCodeSetId(m.getCodeSetId());
        if (StrUtil.isNotBlank(m.getCodeSetId())) {
            vo.setStd(m.getStdFieldName() + "(" + m.getCodeSetId() + ")");
        } else {
            vo.setStd(m.getStdFieldName());
        }
        vo.setTargetTable(m.getTargetTable());
        vo.setTable(m.getTargetTable());
        vo.setRuleText(m.getRuleText());
        vo.setRule(m.getRuleText());
        vo.setStatus(m.getStatus());
        vo.setDsId(m.getDsId());
        vo.setAssetId(m.getAssetId());
        vo.setEtlJobId(m.getEtlJobId());
        vo.setRevision(m.getRevision());
        vo.setUpdateTime(m.getUpdateTime());
        return vo;
    }

    private GovStdDetectVo toDetectVo(GovStdDetectResult d) {
        GovStdDetectVo vo = new GovStdDetectVo();
        vo.setId(d.getId());
        vo.setTable(d.getTableName());
        vo.setTableName(d.getTableName());
        vo.setField(d.getFieldName());
        vo.setFieldName(d.getFieldName());
        vo.setStd(d.getStdRef());
        vo.setStdRef(d.getStdRef());
        vo.setCheck(d.getCheckType());
        vo.setCheckType(d.getCheckType());
        vo.setResult(d.getResultText());
        vo.setResultText(d.getResultText());
        vo.setStatus(d.getStatus());
        vo.setAssetId(d.getAssetId());
        vo.setCheckedAt(d.getCheckedAt());
        vo.setRunId(d.getRunId());
        return vo;
    }

    private GovStdField resolveField(String idOrName, String ws) {
        if (StrUtil.isBlank(idOrName)) {
            throw new CommonException("id不能为空");
        }
        GovStdField byId = fieldMapper.selectById(idOrName);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return byId;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        GovStdField byName = fieldMapper.selectOne(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getWs, workspace)
                .eq(GovStdField::getFieldName, idOrName.trim())
                .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (byName == null) {
            throw new CommonException("标准字段不存在: {}", idOrName);
        }
        return byName;
    }

    private GovStdCode resolveCode(String idOrCodeSetId, String ws) {
        if (StrUtil.isBlank(idOrCodeSetId)) {
            throw new CommonException("id不能为空");
        }
        GovStdCode byId = codeMapper.selectById(idOrCodeSetId);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return byId;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        GovStdCode bySet = codeMapper.selectOne(new QueryWrapper<GovStdCode>().lambda()
                .eq(GovStdCode::getWs, workspace)
                .eq(GovStdCode::getCodeSetId, idOrCodeSetId.trim())
                .eq(GovStdCode::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (bySet == null) {
            throw new CommonException("标准码值不存在: {}", idOrCodeSetId);
        }
        return bySet;
    }

    private GovStdNaming resolveNaming(String id, String ws) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("id不能为空");
        }
        GovStdNaming byId = namingMapper.selectById(id);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return byId;
        }
        throw new CommonException("命名规范不存在: {}", id);
    }

    private String truncateKey(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
