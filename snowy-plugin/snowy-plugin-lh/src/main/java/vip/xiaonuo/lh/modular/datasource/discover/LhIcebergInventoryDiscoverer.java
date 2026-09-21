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
package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceGravitinoProjector;
import vip.xiaonuo.lh.modular.datasource.support.LhIcebergNamespaceNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Iceberg 清单：先按命名空间清单 ensureSchema 到 Grav 湖 catalog，再 listSchemas/listTables。
 */
@Slf4j
@Component
@Order(10)
public class LhIcebergInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final Set<String> SKIP_SCHEMAS = Set.of(
            "information_schema", "sys", "performance_schema"
    );

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private LhDsTableMapper dsTableMapper;

    @Override
    public boolean supports(String typeCode) {
        return "iceberg".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.TABLE;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        String metalake = lhProperties.getGravitino().getMetalake();
        String catalog = gravitinoProjector.catalogNameOf(ds);
        List<String> extra = existingObjectNames(ds);
        try {
            gravitinoProjector.project(ds);
        } catch (Exception e) {
            log.warn("Iceberg Grav project soft-fail dsId={}: {}", ds.getId(), e.getMessage());
        }
        Map<String, Object> ensured = gravitinoProjector.ensureIcebergNamespaces(ds, extra);
        List<String> declared = LhIcebergNamespaceNames.resolve(ds, extra);
        try {
            List<String> listed = gravitinoClient.listSchemas(metalake, catalog);
            LinkedHashSet<String> schemas = new LinkedHashSet<>(declared);
            if (listed != null) {
                for (String s : listed) {
                    if (StrUtil.isNotBlank(s) && !SKIP_SCHEMAS.contains(s.toLowerCase(Locale.ROOT))) {
                        schemas.add(s);
                    }
                }
            }
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String schema : schemas) {
                if (StrUtil.isBlank(schema) || SKIP_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> tables = List.of();
                try {
                    tables = gravitinoClient.listTables(metalake, catalog, schema);
                } catch (Exception e) {
                    log.warn("Iceberg listTables {}.{}: {}", catalog, schema, e.getMessage());
                }
                boolean anyTable = false;
                if (tables != null) {
                    for (String table : tables) {
                        if (StrUtil.isBlank(table)) {
                            continue;
                        }
                        anyTable = true;
                        addItem(items, seen, schema + "." + table, "Iceberg via Grav " + catalog);
                    }
                }
                if (!anyTable) {
                    addItem(items, seen, schema, "Iceberg namespace " + catalog + "." + schema);
                }
            }
            LhInventoryDiscoverResult r = LhInventoryDiscoverResult.remote(
                    "iceberg_grav", LhInventoryObjectKinds.TABLE, items);
            r.hint = hint(catalog, ensured, declared, listed);
            r.fromRemote = true;
            return r;
        } catch (Exception e) {
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String ns : declared) {
                LhRemoteInventoryItem m = LhRemoteInventoryItem.of(ns);
                m.engine = "iceberg";
                m.encoding = "UTF-8";
                m.comment = "declared namespace";
                items.add(m);
            }
            LhInventoryDiscoverResult r = LhInventoryDiscoverResult.manual(
                    LhInventoryObjectKinds.TABLE,
                    "已尝试在 Grav catalog " + catalog + " 创建命名空间；listSchemas 失败: "
                            + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
                            + "。请核对 warehouse / catalog-backend 后重新同步。",
                    items);
            r.path = "iceberg_grav";
            return r;
        }
    }

    private List<String> existingObjectNames(LhDatasource ds) {
        if (ds == null || StrUtil.isBlank(ds.getId())) {
            return List.of();
        }
        List<LhDsTable> rows = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId()));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (LhDsTable row : rows) {
            if (row != null && StrUtil.isNotBlank(row.getTableName())) {
                names.add(row.getTableName());
            }
        }
        return names;
    }

    private static void addItem(List<LhRemoteInventoryItem> items, Set<String> seen,
                                String name, String comment) {
        if (!seen.add(name)) {
            return;
        }
        LhRemoteInventoryItem m = LhRemoteInventoryItem.of(name);
        m.engine = "iceberg";
        m.encoding = "UTF-8";
        m.comment = comment;
        items.add(m);
    }

    @SuppressWarnings("unchecked")
    private static String hint(String catalog, Map<String, Object> ensured,
                               List<String> declared, List<String> listed) {
        Object ensuredNs = ensured == null ? List.of() : ensured.get("ensured");
        Object errors = ensured == null ? List.of() : ensured.get("schemaErrors");
        StringBuilder sb = new StringBuilder();
        sb.append("Gravitino catalog ").append(catalog);
        sb.append(" namespaces declared=").append(declared);
        sb.append(" ensured=").append(ensuredNs);
        sb.append(" listed=").append(listed == null ? List.of() : listed);
        if (errors instanceof List<?> err && !err.isEmpty()) {
            sb.append(" errors=").append(err);
        }
        return sb.toString();
    }
}
