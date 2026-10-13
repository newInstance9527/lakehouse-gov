package vip.xiaonuo.lh.modular.analytics.service;

import vip.xiaonuo.lh.modular.analytics.param.LhAnalyticsCollectParam;

import java.util.Map;

public interface LhAnalyticsCollectService {

    Map<String, Object> collect(LhAnalyticsCollectParam param);
}
