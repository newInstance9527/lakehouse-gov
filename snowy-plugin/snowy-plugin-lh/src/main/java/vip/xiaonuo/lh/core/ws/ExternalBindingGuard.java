package vip.xiaonuo.lh.core.ws;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;

/**
 * 绑定表 {@code ext_id} 全局唯一校验（投影前调用；冲突则拒绝，不静默覆盖）。
 */
@Component
public class ExternalBindingGuard {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private LhConsumerBindingMapper consumerBindingMapper;
    @Resource
    private DataapiApiBindingMapper dataapiApiBindingMapper;

    /**
     * {@code ig_consumer_binding.ext_id}：已被其他绑定占用则抛错。
     *
     * @param excludeId 当前行主键（更新时排除自身）；新建传 null
     */
    public void assertConsumerExtIdFree(String extId, Long excludeId) {
        if (StrUtil.isBlank(extId)) {
            return;
        }
        QueryWrapper<LhConsumerBinding> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(LhConsumerBinding::getExtId, extId)
                .eq(LhConsumerBinding::getDeleteFlag, NOT_DELETE)
                .ne(excludeId != null, LhConsumerBinding::getId, excludeId)
                .last("LIMIT 1");
        LhConsumerBinding hit = consumerBindingMapper.selectOne(qw);
        if (hit != null) {
            throw new CommonException("外部名已被占用（ext_id=" + extId
                    + " · ds=" + hit.getDsId() + " · type=" + hit.getConsumerType()
                    + "），请更换业务编码或工作空间后重试");
        }
    }

    /**
     * {@code dataapi_api_binding.ext_id}：已被其他 API 绑定占用则抛错。
     */
    public void assertDataapiExtIdFree(String extId, String excludeId) {
        if (StrUtil.isBlank(extId)) {
            return;
        }
        QueryWrapper<DataapiApiBinding> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(DataapiApiBinding::getExtId, extId)
                .eq(DataapiApiBinding::getDeleteFlag, NOT_DELETE)
                .ne(StrUtil.isNotBlank(excludeId), DataapiApiBinding::getId, excludeId)
                .last("LIMIT 1");
        DataapiApiBinding hit = dataapiApiBindingMapper.selectOne(qw);
        if (hit != null) {
            throw new CommonException("外部名已被占用（ext_id=" + extId
                    + " · api=" + hit.getId() + " · ws=" + hit.getWs()
                    + "），请更换 API 编码或工作空间后重试");
        }
    }

    /**
     * APISIX route id 全局唯一（与 {@code ext_id} 并存校验）。
     */
    public void assertApisixRouteIdFree(String routeId, String excludeId) {
        if (StrUtil.isBlank(routeId)) {
            return;
        }
        QueryWrapper<DataapiApiBinding> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(DataapiApiBinding::getApisixRouteId, routeId)
                .eq(DataapiApiBinding::getDeleteFlag, NOT_DELETE)
                .ne(StrUtil.isNotBlank(excludeId), DataapiApiBinding::getId, excludeId)
                .last("LIMIT 1");
        DataapiApiBinding hit = dataapiApiBindingMapper.selectOne(qw);
        if (hit != null) {
            throw new CommonException("APISIX 路由 id 已被占用（" + routeId
                    + " · api=" + hit.getId() + "），拒绝覆盖");
        }
    }
}
