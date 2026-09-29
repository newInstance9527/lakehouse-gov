package vip.xiaonuo.lh.modular.observability.service.impl;

import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.observability.service.LhObsInfraService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LhObsInfraServiceImpl implements LhObsInfraService {

    @Override
    public Map<String, Object> summary(String ws) {
        Map<String, Object> r = base(ws);
        r.put("kpis", emptyKpis());
        r.put("capacityTips", List.of());
        r.put("hint", "无 Categraf/VictoriaMetrics/夜莺采集时为空态；禁止演示节点与假告警");
        return r;
    }

    @Override
    public Map<String, Object> nodes(String ws) {
        Map<String, Object> r = base(ws);
        r.put("records", List.of());
        return r;
    }

    @Override
    public Map<String, Object> procs(String ws) {
        Map<String, Object> r = base(ws);
        r.put("records", List.of());
        return r;
    }

    @Override
    public Map<String, Object> alerts(String ws) {
        Map<String, Object> r = base(ws);
        r.put("records", List.of());
        return r;
    }

    @Override
    public Map<String, Object> containers(String ws) {
        Map<String, Object> r = base(ws);
        r.put("records", List.of());
        r.put("layer", "L1");
        r.put("hint", "§30.5 容器面二期；无采集时空列表合法");
        return r;
    }

    @Override
    public Map<String, Object> cluster(String ws) {
        Map<String, Object> r = base(ws);
        r.put("records", List.of());
        r.put("layer", "L2");
        r.put("hint", "§30.5 集群对象二期；无采集时空列表合法");
        return r;
    }

    @Override
    public Map<String, Object> capacityForecast(String ws) {
        Map<String, Object> r = base(ws);
        r.put("points", List.of());
        r.put("horizonDays", null);
        r.put("hint", "§30.5 容量预测二期；无时序时合法空态");
        return r;
    }

    private static Map<String, Object> base(String ws) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "empty");
        r.put("ws", ws == null || ws.isBlank() ? null : ws.trim());
        return r;
    }

    private static List<Map<String, Object>> emptyKpis() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(kpi("🖥️", "blue", "—", "台", "节点", "无采集", true, false, false));
        list.add(kpi("✅", "green", "—", "%", "组件进程存活", "无采集", true, false, false));
        list.add(kpi("🚨", "red", "—", "条", "活跃告警", "无采集", false, false, true));
        list.add(kpi("💽", "orange", "—", "%", "平均磁盘水位", "无采集", false, true, false));
        return list;
    }

    private static Map<String, Object> kpi(
            String icon, String color, String value, String unit, String label,
            String trend, boolean trendUp, boolean trendWarn, boolean trendDanger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icon", icon);
        m.put("color", color);
        m.put("value", value);
        m.put("unit", unit);
        m.put("label", label);
        m.put("trend", trend);
        m.put("trendUp", trendUp);
        m.put("trendWarn", trendWarn);
        m.put("trendDanger", trendDanger);
        return m;
    }
}
