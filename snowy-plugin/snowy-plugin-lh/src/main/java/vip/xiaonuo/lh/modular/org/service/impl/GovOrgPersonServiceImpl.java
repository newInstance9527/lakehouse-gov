package vip.xiaonuo.lh.modular.org.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.org.entity.GovOrgPerson;
import vip.xiaonuo.lh.modular.org.mapper.GovOrgPersonMapper;
import vip.xiaonuo.lh.modular.org.param.GovOrgPersonSaveParam;
import vip.xiaonuo.lh.modular.org.result.GovOrgPersonVo;
import vip.xiaonuo.lh.modular.org.service.GovOrgPersonService;
import vip.xiaonuo.sys.api.SysOrgApi;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GovOrgPersonServiceImpl implements GovOrgPersonService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String STATUS_ACTIVE = "active";

    @Resource
    private GovOrgPersonMapper personMapper;

    @Resource
    private SysOrgApi sysOrgApi;

    @Override
    public List<GovOrgPersonVo> listByOrg(String orgId, String kw) {
        return listByOrg(orgId, kw, true);
    }

    @Override
    public List<GovOrgPersonVo> listByOrg(String orgId, String kw, boolean includeChild) {
        if (StrUtil.isBlank(orgId)) {
            throw new CommonException("orgId不能为空");
        }
        List<String> orgIds;
        if (includeChild) {
            orgIds = sysOrgApi.getChildOrgIdListById(orgId);
            if (orgIds == null || orgIds.isEmpty()) {
                orgIds = List.of(orgId.trim());
            }
        } else {
            orgIds = List.of(orgId.trim());
        }
        LambdaQueryWrapper<GovOrgPerson> q = new LambdaQueryWrapper<GovOrgPerson>()
                .eq(GovOrgPerson::getDeleteFlag, NOT_DELETE)
                .in(GovOrgPerson::getOrgId, orgIds)
                .orderByDesc(GovOrgPerson::getCreateTime);
        if (StrUtil.isNotBlank(kw)) {
            q.and(w -> w.like(GovOrgPerson::getName, kw)
                    .or().like(GovOrgPerson::getPhone, kw)
                    .or().like(GovOrgPerson::getEmail, kw));
        }
        return personMapper.selectList(q).stream().map(this::toVo).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public GovOrgPersonVo create(GovOrgPersonSaveParam param) {
        assertOrgExists(param.getOrgId());
        GovOrgPerson row = new GovOrgPerson();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(new Date());
        apply(row, param);
        personMapper.insert(row);
        return toVo(row);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public GovOrgPersonVo update(String id, GovOrgPersonSaveParam param) {
        GovOrgPerson row = require(id);
        assertOrgExists(param.getOrgId());
        apply(row, param);
        row.setUpdateTime(new Date());
        personMapper.updateById(row);
        return toVo(row);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void remove(String id) {
        GovOrgPerson row = require(id);
        row.setDeleteFlag("DELETED");
        row.setUpdateTime(new Date());
        personMapper.updateById(row);
    }

    @Override
    public long countByOrgId(String orgId) {
        if (StrUtil.isBlank(orgId)) {
            return 0;
        }
        return personMapper.selectCount(new LambdaQueryWrapper<GovOrgPerson>()
                .eq(GovOrgPerson::getDeleteFlag, NOT_DELETE)
                .eq(GovOrgPerson::getOrgId, orgId));
    }

    private void apply(GovOrgPerson row, GovOrgPersonSaveParam param) {
        row.setOrgId(param.getOrgId().trim());
        row.setName(param.getName().trim());
        row.setPhone(StrUtil.trim(param.getPhone()));
        row.setEmail(StrUtil.trim(param.getEmail()));
        row.setJobTitle(StrUtil.trim(param.getJobTitle()));
        row.setRemark(StrUtil.trim(param.getRemark()));
        String status = StrUtil.blankToDefault(StrUtil.trim(param.getStatus()), STATUS_ACTIVE);
        if (!STATUS_ACTIVE.equals(status) && !"disabled".equals(status)) {
            throw new CommonException("status仅支持 active/disabled");
        }
        row.setStatus(status);
    }

    private void assertOrgExists(String orgId) {
        String name = sysOrgApi.getNameById(orgId);
        if (StrUtil.isBlank(name)) {
            throw new CommonException("部门不存在：{}", orgId);
        }
    }

    private GovOrgPerson require(String id) {
        GovOrgPerson row = personMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("人员不存在：{}", id);
        }
        return row;
    }

    private GovOrgPersonVo toVo(GovOrgPerson row) {
        GovOrgPersonVo vo = new GovOrgPersonVo();
        vo.setId(row.getId());
        vo.setOrgId(row.getOrgId());
        try {
            vo.setOrgName(sysOrgApi.getNameById(row.getOrgId()));
        } catch (Exception ignored) {
            vo.setOrgName(null);
        }
        vo.setName(row.getName());
        vo.setPhone(row.getPhone());
        vo.setEmail(row.getEmail());
        vo.setJobTitle(row.getJobTitle());
        vo.setRemark(row.getRemark());
        vo.setStatus(row.getStatus());
        return vo;
    }
}
