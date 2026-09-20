package vip.xiaonuo.lh.core.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.sys.api.SysUserApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 批量解析 sys_user.id → name，供 VO 展示字段填充。
 * 存储字段仍为 user_id；查不到（历史显示名/无效 id）时不写 Name 字段，前端回退 raw。
 */
@Component
public class LhUserNameResolver {

    @Resource
    private SysUserApi sysUserApi;

    /**
     * @return id → name；缺失 id 不出现在 map 中
     */
    public Map<String, String> resolveNames(Collection<String> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : userIds) {
            if (StrUtil.isNotBlank(id)) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<JSONObject> users;
        try {
            users = sysUserApi.getUserListByIdListWithoutException(new ArrayList<>(ids));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        if (CollUtil.isEmpty(users)) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new HashMap<>(users.size());
        for (JSONObject u : users) {
            if (u == null) {
                continue;
            }
            String id = u.getStr("id");
            String name = u.getStr("name");
            if (StrUtil.isNotBlank(id) && StrUtil.isNotBlank(name)) {
                out.put(id, name);
            }
        }
        return out;
    }

    public void fillDatasources(List<LhDatasourceVo> vos) {
        if (CollUtil.isEmpty(vos)) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (LhDatasourceVo vo : vos) {
            collect(ids, vo.getOwner());
            collect(ids, vo.getCreateUser());
        }
        Map<String, String> names = resolveNames(ids);
        for (LhDatasourceVo vo : vos) {
            apply(vo::setOwnerName, vo.getOwner(), names);
            apply(vo::setCreateUserName, vo.getCreateUser(), names);
        }
    }

    public void fillDatasource(LhDatasourceVo vo) {
        if (vo == null) {
            return;
        }
        fillDatasources(List.of(vo));
    }

    public void fillAssets(List<GovAssetVo> vos) {
        if (CollUtil.isEmpty(vos)) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (GovAssetVo vo : vos) {
            collect(ids, vo.getTechOwner());
            collect(ids, vo.getBizOwner());
            collect(ids, vo.getCreateUser());
        }
        Map<String, String> names = resolveNames(ids);
        for (GovAssetVo vo : vos) {
            apply(vo::setTechOwnerName, vo.getTechOwner(), names);
            apply(vo::setBizOwnerName, vo.getBizOwner(), names);
            apply(vo::setCreateUserName, vo.getCreateUser(), names);
        }
    }

    public void fillAsset(GovAssetVo vo) {
        if (vo == null) {
            return;
        }
        fillAssets(List.of(vo));
    }

    public void fillDagBriefs(List<Map<String, Object>> briefs) {
        if (CollUtil.isEmpty(briefs)) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> m : briefs) {
            collect(ids, str(m.get("owner")));
            collect(ids, str(m.get("createUser")));
        }
        Map<String, String> names = resolveNames(ids);
        for (Map<String, Object> m : briefs) {
            applyMap(m, "ownerName", str(m.get("owner")), names);
            applyMap(m, "createUserName", str(m.get("createUser")), names);
        }
    }

    public void fillDagBrief(Map<String, Object> brief) {
        if (brief == null) {
            return;
        }
        fillDagBriefs(List.of(brief));
    }

    private static void collect(Set<String> ids, String raw) {
        if (StrUtil.isNotBlank(raw)) {
            ids.add(raw.trim());
        }
    }

    private static void apply(Consumer<String> setter, String rawId, Map<String, String> names) {
        if (StrUtil.isBlank(rawId)) {
            return;
        }
        String name = names.get(rawId.trim());
        if (StrUtil.isNotBlank(name)) {
            setter.accept(name);
        }
    }

    private static void applyMap(Map<String, Object> m, String key, String rawId, Map<String, String> names) {
        if (StrUtil.isBlank(rawId)) {
            return;
        }
        String name = names.get(rawId.trim());
        if (StrUtil.isNotBlank(name)) {
            m.put(key, name);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
