package vip.xiaonuo.lh.modular.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolRegistryDefinitionsTest {

    @Test
    void openaiDefinitionsIncludeCoreTools() {
        AiToolRegistry registry = new AiToolRegistry();
        List<Map<String, Object>> tools = registry.openaiToolDefinitions();
        assertFalse(tools.isEmpty());
        assertTrue(tools.size() >= 41);
        Set<String> names = new java.util.HashSet<>();
        for (Map<String, Object> t : tools) {
            Object fn = t.get("function");
            if (fn instanceof Map<?, ?> m) {
                names.add(String.valueOf(m.get("name")));
            }
        }
        assertTrue(names.contains("catalog_stats"));
        assertTrue(names.contains("list_my_assets"));
        assertTrue(names.contains("preview_asset"));
        assertTrue(names.contains("get_asset_detail"));
        assertTrue(names.contains("lineage_downstream"));
        assertTrue(names.contains("recent_dq_fails"));
        assertTrue(names.contains("list_apply_tickets"));
        assertTrue(names.contains("list_datasources"));
        assertTrue(names.contains("ds_connectivity"));
        assertTrue(names.contains("list_source_tables"));
        assertTrue(names.contains("query_history_mine"));
        assertTrue(names.contains("list_saved_queries"));
        assertTrue(names.contains("api_catalog_summary"));
        assertTrue(names.contains("standard_lookup"));
        assertTrue(names.contains("check_my_grant"));
        assertTrue(names.contains("list_mask_policies"));
        assertTrue(names.contains("security_overview"));
        assertTrue(names.contains("list_meta_drifts"));
        assertTrue(names.contains("list_releases"));
        assertTrue(names.contains("release_precheck"));
        assertTrue(names.contains("list_dev_scripts"));
        assertTrue(names.contains("list_udfs"));
        assertTrue(names.contains("quota_alerts"));
        assertTrue(names.contains("lifecycle_overview"));
        assertTrue(names.contains("compliance_summary"));
        assertTrue(names.contains("export_audit"));
        assertTrue(names.contains("contract_overview"));
        assertTrue(names.contains("aimodel_overview"));
        assertTrue(names.contains("kb_overview"));
        assertTrue(names.contains("workspace_summary"));
        assertTrue(names.contains("list_pending_approvals"));
        assertTrue(names.contains("search_docs"));
        assertTrue(names.contains("ops_job_status"));
    }

    @Test
    void unknownToolReturnsErrorJson() {
        AiToolRegistry registry = new AiToolRegistry();
        String out = registry.execute("not_a_real_tool", "{}", "default");
        assertTrue(out.contains("unknown_tool") || out.contains("error"));
    }
}
