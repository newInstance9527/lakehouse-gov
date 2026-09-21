package vip.xiaonuo.lh.modular.workspace.service;

import vip.xiaonuo.lh.modular.workspace.param.GovWsCreateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCurrentParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMemberItemParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMembersReplaceParam;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsQuotaVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;

import java.util.List;
import java.util.Map;

public interface GovWsService {

    Map<String, Object> overview();

    List<GovWsVo> listSpaces();

    GovWsVo detail(String wsCode);

    GovWsVo create(GovWsCreateParam param);

    GovWsVo archive(String wsCode);

    List<GovWsMemberVo> listMembers(String wsCode);

    List<GovWsMemberVo> replaceMembers(String wsCode, GovWsMembersReplaceParam param);

    GovWsMemberVo addMember(String wsCode, GovWsMemberItemParam param);

    void removeMember(String wsCode, String memberId);

    GovWsQuotaVo quota(String wsCode);

    List<GovWsQuotaVo> listQuotas();

    Map<String, Object> getCurrent();

    Map<String, Object> setCurrent(GovWsCurrentParam param);
}
