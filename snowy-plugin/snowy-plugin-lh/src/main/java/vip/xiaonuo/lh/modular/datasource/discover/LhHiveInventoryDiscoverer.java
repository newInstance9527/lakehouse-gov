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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceGravitinoProjector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hive 清单发现：优先经 Gravitino Hive Catalog listSchemas/listTables
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(10)
public class LhHiveInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final Set<String> SKIP_SCHEMAS = Set.of(
            "information_schema", "sys", "performance_schema"
    );

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;

    @Override
    public boolean supports(String typeCode) {
        return "hive".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.TABLE;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        String metalake = lhProperties.getGravitino().getMetalake();
        String catalog = gravitinoProjector.catalogNameOf(ds);
        try {
            // 确保已投影（幂等）；失败不阻断后续 list（可能手工已建 catalog）
            try {
                gravitinoProjector.project(ds);
            } catch (Exception e) {
                log.warn("Hive Grav project soft-fail dsId={}: {}", ds.getId(), e.getMessage());
            }
            List<String> schemas = gravitinoClient.listSchemas(metalake, catalog);
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            String preferDb = StrUtil.blankToDefault(ds.getDatabaseName(), "").trim();
            for (String schema : schemas) {
                if (StrUtil.isBlank(schema) || SKIP_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (StrUtil.isNotBlank(preferDb) && !preferDb.equalsIgnoreCase(schema)) {
                    // 登记时指定了 database：只同步该库
                    continue;
                }
                List<String> tables;
                try {
                    tables = gravitinoClient.listTables(metalake, catalog, schema);
                } catch (Exception e) {
                    log.warn("Hive listTables {}.{}: {}", catalog, schema, e.getMessage());
                    continue;
                }
                for (String table : tables) {
                    if (StrUtil.isBlank(table)) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    // 多库时用 db.table，单库/已过滤时仍带 schema 前缀便于唯一
                    m.name = schema + "." + table;
                    m.engine = "hive";
                    m.encoding = "UTF-8";
                    m.comment = "Hive via Grav " + catalog;
                    items.add(m);
                }
            }
            if (items.isEmpty()) {
                throw new CommonException(
                        "Hive Catalog {} 未列出表。Grav 需要 Metastore thrift（默认 :9083），不是 HiveServer2 :10000；"
                                + "请确认 HMS 已启动，且 metastore.uris 用内网 IP（如 thrift://10.0.0.34:9083）",
                        catalog);
            }
            return LhInventoryDiscoverResult.remote("hive_grav", LhInventoryObjectKinds.TABLE, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            if (msg.toLowerCase(Locale.ROOT).contains("metastore")
                    || msg.toLowerCase(Locale.ROOT).contains("9083")) {
                throw new CommonException(
                        "Hive Metastore 不可达（{}）。请在主机启动 `hive --service metastore` 监听 9083，"
                                + "Vault/表单填写 metastoreUris=thrift://10.0.0.34:9083 后重新 projectToGravitino",
                        msg);
            }
            throw new CommonException("Hive 清单同步失败: {}", msg);
        }
    }
}
