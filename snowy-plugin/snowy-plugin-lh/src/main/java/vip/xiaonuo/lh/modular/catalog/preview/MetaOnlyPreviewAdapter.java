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
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 尚无专用探查适配器的类型：返回明确提示（结束路由）。
 * <p>已有样例适配器的类型（ES/Mongo/S3/HTTP/Kafka/Redis/JDBC/湖表）不得命中本适配器。</p>
 */
@Component
@Order(90)
public class MetaOnlyPreviewAdapter implements GovAssetPreviewAdapter {

    @Override
    public int order() {
        return 90;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        if (ds == null) {
            return false;
        }
        if (PreviewAdapterSupport.isJdbcSource(ds) || PreviewAdapterSupport.isLakeSource(ctx)) {
            return false;
        }
        String t = PreviewAdapterSupport.dsType(ds);
        // 已有专用 PreviewAdapter
        if ("kafka".equals(t) || "redis".equals(t)
                || "elasticsearch".equals(t) || "es".equals(t)
                || "mongodb".equals(t)
                || "s3".equals(t) || "minio".equals(t)
                || "http_api".equals(t) || "api".equals(t)) {
            return false;
        }
        Optional<LhDatasourceTypeEnum> e = LhDatasourceTypeEnum.of(ds.getType());
        if (e.isEmpty()) {
            return StrUtil.isNotBlank(t);
        }
        return switch (e.get()) {
            case FILE, FTP, HDFS, HBASE, RABBITMQ, PULSAR, TABLEAU, SUPERSET, AIRFLOW -> true;
            default -> false;
        };
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        Map<String, Object> r = ctx.newResult();
        String type = PreviewAdapterSupport.dsType(ctx.getPrimaryDs());
        String kind = ctx.getPrimaryLink() == null ? null : ctx.getPrimaryLink().getObjectKind();
        if (StrUtil.isBlank(kind) && ctx.getAsset() != null) {
            kind = ctx.getAsset().getAssetKind();
        }
        r.put("ok", false);
        r.put("source", "meta_only");
        r.put("tryNext", false);
        r.put("qualifiedName", ctx.getObjectName());
        r.put("columns", List.of());
        r.put("rows", List.of());
        r.put("rowCount", 0);
        r.put("message", "类型「" + type + "」暂无专用样例探查；请查看清单元数据。"
                + "（ES/Mongo/S3/HTTP/Kafka/Redis/JDBC/湖表已走各自适配器）");
        r.put("hint", "目录预览 = 类型适配探查；分析仍只走 Trino→Gravitino。FTP/HDFS 等可后续补 List 适配器。");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dsType", type);
        meta.put("objectName", ctx.getObjectName());
        meta.put("objectKind", kind);
        r.put("meta", meta);
        return r;
    }
}
