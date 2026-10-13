package vip.xiaonuo.lh.modular.meta.service;

import java.util.Map;

/** M1.4 金路径软探针（仓内可观测，非替代现网联调勾选） */
public interface GoldenPathService {
    Map<String, Object> status(String ws);
}
