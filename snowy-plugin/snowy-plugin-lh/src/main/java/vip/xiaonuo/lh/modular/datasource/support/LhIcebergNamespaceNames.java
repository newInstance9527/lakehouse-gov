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
package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iceberg Grav schema（namespace）名：来自数据源 database / 命名空间清单 / 表清单对象名。
 */
public final class LhIcebergNamespaceNames {

    private static final Set<String> SKIP = Set.of(
            "information_schema", "sys", "performance_schema");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private LhIcebergNamespaceNames() {
    }

    public static List<String> resolve(LhDatasource ds, Collection<String> extraObjectNames) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ds != null) {
            addIdent(out, ds.getDatabaseName());
            for (String raw : split(ds.getSchemaSummary())) {
                addObject(out, raw);
            }
        }
        if (extraObjectNames != null) {
            for (String raw : extraObjectNames) {
                addObject(out, raw);
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> split(String summary) {
        if (StrUtil.isBlank(summary)) {
            return Collections.emptyList();
        }
        return Arrays.stream(summary.split("[,;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    static void addObject(Set<String> out, String raw) {
        String s = StrUtil.trim(raw);
        if (StrUtil.isBlank(s) || s.contains("://") || s.contains("/")
                || s.toLowerCase(Locale.ROOT).startsWith("s3")) {
            return;
        }
        String[] parts = s.split("\\.");
        String ns;
        if (parts.length >= 3 && "iceberg".equalsIgnoreCase(parts[0])) {
            ns = parts[1];
        } else if (parts.length >= 2) {
            ns = parts[0];
        } else {
            ns = parts[0];
        }
        addIdent(out, ns);
    }

    static void addIdent(Set<String> out, String ns) {
        if (StrUtil.isBlank(ns)) {
            return;
        }
        String t = ns.trim();
        if (SKIP.contains(t.toLowerCase(Locale.ROOT)) || !IDENT.matcher(t).matches()) {
            return;
        }
        out.add(t);
    }
}
