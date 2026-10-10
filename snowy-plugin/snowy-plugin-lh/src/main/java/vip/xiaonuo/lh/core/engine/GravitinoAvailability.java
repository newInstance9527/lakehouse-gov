package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.Map;

/**
 * Gravitino 控制面可用性：DOWN 时禁止新建登记 / 建表（§23 降级预案第一步）。
 * 读路径不经本门禁，由 {@link GravitinoSchemaCache} 做只读降级。
 */
@Component
public class GravitinoAvailability {

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;

    public boolean isUp() {
        try {
            Map<String, Object> h = gravitinoClient.health();
            return "UP".equalsIgnoreCase(String.valueOf(h.get("status")));
        } catch (Exception e) {
            return false;
        }
    }

    /** 配置开启且 Grav DOWN 时抛错。 */
    public void requireUpForRegister(String action) {
        if (!blockWhenDown()) {
            return;
        }
        if (isUp()) {
            return;
        }
        String act = StrUtil.blankToDefault(action, "登记");
        throw new CommonException("Gravitino 不可用，禁止" + act
                + "（lh.gravitino.block-register-when-down=true；恢复后重试）");
    }

    public boolean blockWhenDown() {
        return lhProperties.getGravitino() == null
                || lhProperties.getGravitino().isBlockRegisterWhenDown();
    }
}
