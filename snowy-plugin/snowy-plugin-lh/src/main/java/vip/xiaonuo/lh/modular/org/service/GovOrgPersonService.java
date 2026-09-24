package vip.xiaonuo.lh.modular.org.service;

import vip.xiaonuo.lh.modular.org.param.GovOrgPersonSaveParam;
import vip.xiaonuo.lh.modular.org.result.GovOrgPersonVo;

import java.util.List;

public interface GovOrgPersonService {

    List<GovOrgPersonVo> listByOrg(String orgId, String kw);

    /** @param includeChild true=本部门及所有下级部门挂接人员 */
    List<GovOrgPersonVo> listByOrg(String orgId, String kw, boolean includeChild);

    GovOrgPersonVo create(GovOrgPersonSaveParam param);

    GovOrgPersonVo update(String id, GovOrgPersonSaveParam param);

    void remove(String id);

    long countByOrgId(String orgId);
}
