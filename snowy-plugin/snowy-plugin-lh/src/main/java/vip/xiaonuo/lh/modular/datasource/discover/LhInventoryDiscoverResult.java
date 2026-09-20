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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 源端清单发现结果（表 / Topic / Index / Bucket / Queue / Key 前缀等）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public class LhInventoryDiscoverResult {

    /** true=远端真实拉取；false=手工摘要等降级 */
    public boolean fromRemote;
    /**
     * 发现路径：jdbc / hive_grav / kafka_admin / es_cat / redis_prefix / redis_scan
     * / rabbitmq_mgmt / minio_s3 / manual / fallback
     */
    public String path = "unknown";
    /** 对象语义：table / topic / index / … 见 {@link LhInventoryObjectKinds} */
    public String objectKind = LhInventoryObjectKinds.TABLE;
    /** 产品提示（手工/不支持自动时必填，供前端 toast） */
    public String hint;
    public List<LhRemoteInventoryItem> items = new ArrayList<>();

    public static LhInventoryDiscoverResult remote(String path, List<LhRemoteInventoryItem> items) {
        return remote(path, LhInventoryObjectKinds.TABLE, items);
    }

    public static LhInventoryDiscoverResult remote(String path, String objectKind,
                                                   List<LhRemoteInventoryItem> items) {
        LhInventoryDiscoverResult r = new LhInventoryDiscoverResult();
        r.fromRemote = true;
        r.path = path;
        r.objectKind = objectKind == null ? LhInventoryObjectKinds.TABLE : objectKind;
        r.items = items == null ? new ArrayList<>() : items;
        return r;
    }

    public static LhInventoryDiscoverResult manual(String objectKind, String hint,
                                                   List<LhRemoteInventoryItem> items) {
        LhInventoryDiscoverResult r = new LhInventoryDiscoverResult();
        r.fromRemote = false;
        r.path = "manual";
        r.objectKind = objectKind == null ? LhInventoryObjectKinds.UNKNOWN : objectKind;
        r.hint = hint;
        r.items = items == null ? Collections.emptyList() : items;
        return r;
    }

    public static LhInventoryDiscoverResult fallback(List<LhRemoteInventoryItem> items) {
        LhInventoryDiscoverResult r = new LhInventoryDiscoverResult();
        r.fromRemote = false;
        r.path = "fallback";
        r.objectKind = LhInventoryObjectKinds.UNKNOWN;
        r.hint = "当前类型无专用发现器，已按 schemaSummary 手工摘要回填";
        r.items = items == null ? Collections.emptyList() : items;
        return r;
    }
}
