package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.compliance.support.GovDelCkSql;
import vip.xiaonuo.lh.modular.compliance.support.GovDelIcebergSql;
import vip.xiaonuo.lh.modular.compliance.support.GovDelSinkSql;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovLcProfileSqlTest {

    @Test
    void metadataSqlQuotesIcebergTables() {
        GovLcMetadataSql.TableRef ref = GovLcMetadataSql.parse("ods_trade.s_order", "iceberg");
        assertEquals("iceberg", ref.catalog());
        assertTrue(GovLcMetadataSql.files(ref).contains("\"ods_trade\".\"s_order$files\""));
        assertTrue(GovLcMetadataSql.files(ref).contains("33554432"));
        assertTrue(GovLcMetadataSql.allFiles(ref).contains("\"s_order$all_files\""));
        assertTrue(GovLcMetadataSql.snapshots(ref).contains("\"s_order$snapshots\""));
        assertTrue(GovLcMetadataSql.partitions(ref).contains("\"s_order$partitions\""));
    }

    @Test
    void rejectsFreeformNames() {
        assertThrows(IllegalArgumentException.class,
                () -> GovLcMetadataSql.parse("ods.s_order;drop", "iceberg"));
    }

    @Test
    void complianceExpireKeepsOneSnapshot() {
        String sql = GovLcProcedureSql.expire("iceberg", "dwd_user.dwd_user_info", new GovLcPolicy(), 1);
        assertTrue(sql.contains("retain_last => 1"));
        assertTrue(sql.contains("dwd_user.dwd_user_info"));
    }

    @Test
    void deletePredicateIsHashOnly() {
        String sql = GovDelIcebergSql.delete("dwd_user", "dwd_user_info", "user_key",
                "a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01");
        assertEquals("DELETE FROM \"dwd_user\".\"dwd_user_info\" WHERE \"user_key\" = "
                + "'a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01'", sql);
        assertThrows(IllegalArgumentException.class,
                () -> GovDelIcebergSql.delete("dwd_user", "dwd_user_info", "user_key", "user_88241"));
    }

    @Test
    void complianceHardDeleteSparkSqlAndOrder() {
        String hash = "a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01";
        String delete = GovDelIcebergSql.deleteSpark("iceberg", "dwd_user", "dwd_user_info", "user_key", hash);
        assertEquals("DELETE FROM iceberg.dwd_user.dwd_user_info WHERE user_key = '" + hash + "'", delete);
        String expire = GovLcProcedureSql.expire("iceberg", "dwd_user.dwd_user_info", new GovLcPolicy(), 1);
        String rewrite = GovLcProcedureSql.rewrite("iceberg", "dwd_user.dwd_user_info", new GovLcPolicy());
        // 独立 DAG 顺序：delete → compact → 定向 expire（≠ 日作业 expire → rewrite → orphan）
        assertTrue(delete.startsWith("DELETE FROM"));
        assertTrue(rewrite.contains("rewrite_data_files"));
        assertTrue(expire.contains("retain_last => 1"));
        assertThrows(IllegalArgumentException.class,
                () -> GovDelIcebergSql.deleteSpark("iceberg;drop", "dwd_user", "dwd_user_info", "user_key", hash));
    }

    @Test
    void ckCountUsesBackticksAndHashOnly() {
        String hash = "a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01";
        GovLcMetadataSql.TableRef ref = GovDelCkSql.parse("ads.user_tags_local");
        assertEquals("ads", ref.schema());
        assertEquals("user_tags_local", ref.table());
        String sql = GovDelCkSql.count(ref.schema(), ref.table(), "user_key", hash);
        assertEquals("SELECT count(*) AS cnt FROM `ads`.`user_tags_local` WHERE `user_key` = '" + hash + "'", sql);
        assertThrows(IllegalArgumentException.class,
                () -> GovDelCkSql.count("ads", "user_tags_local", "user_key", "user_88241"));
    }

    @Test
    void ckAlterDeleteUsesMutationSyncAndOptionalCluster() {
        String hash = "a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01";
        String sql = GovDelCkSql.alterDelete("ads", "user_tags_local", "user_key", hash, null);
        assertEquals("ALTER TABLE `ads`.`user_tags_local` DELETE WHERE `user_key` = '" + hash
                + "' SETTINGS mutations_sync = 2", sql);
        String clustered = GovDelCkSql.alterDelete("ads", "user_tags_local", "user_key", hash, "lh");
        assertTrue(clustered.contains("ON CLUSTER lh"));
        assertTrue(clustered.contains("mutations_sync = 2"));
        String status = GovDelCkSql.mutationStatus("lh", "user_tags_local", "0000000001");
        assertTrue(status.contains("clusterAllReplicas('lh', system.mutations)"));
        assertTrue(status.contains("mutation_id = '0000000001'"));
        assertThrows(IllegalArgumentException.class,
                () -> GovDelCkSql.alterDelete("ads", "user_tags_local", "user_key", "user_88241", null));
    }

    @Test
    void sinkFqnParseAndRdbPreview() {
        GovDelSinkSql.SinkRef ref = GovDelSinkSql.parse("mysql.crm.user_profile");
        assertEquals("mysql", ref.engine());
        assertEquals("crm", ref.dsHint());
        assertEquals("user_profile", ref.table());
        assertTrue(GovDelSinkSql.isRdb("mysql"));
        assertTrue(GovDelSinkSql.isRedis("redis"));
        String hash = "a1f3c8de42b7105f9c3b6a77d0e21c48f5b9042a6c1d8e37f4a05b6c7d8e9f01";
        assertTrue(GovDelSinkSql.deletePreview("crm", "user_profile", "user_id", hash).startsWith("DELETE FROM"));
        assertThrows(IllegalArgumentException.class,
                () -> GovDelSinkSql.parse("mysql.crm"));
    }

    @Test
    void orphanLogParserPrefersSummaryCount() {
        assertEquals(12, GovLcOrphanLogParser.countCandidates("Found 12 orphan files\ns3://bucket/a"));
        assertEquals(2, GovLcOrphanLogParser.countCandidates("orphan file s3://b/a\nhdfs://b/c"));
        assertEquals(-1, GovLcOrphanLogParser.countCandidates("task success"));
    }
}
