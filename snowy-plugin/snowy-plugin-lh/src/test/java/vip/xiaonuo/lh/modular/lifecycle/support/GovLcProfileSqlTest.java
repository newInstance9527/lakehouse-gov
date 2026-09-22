package vip.xiaonuo.lh.modular.lifecycle.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.compliance.support.GovDelCkSql;
import vip.xiaonuo.lh.modular.compliance.support.GovDelIcebergSql;
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
    void orphanLogParserPrefersSummaryCount() {
        assertEquals(12, GovLcOrphanLogParser.countCandidates("Found 12 orphan files\ns3://bucket/a"));
        assertEquals(2, GovLcOrphanLogParser.countCandidates("orphan file s3://b/a\nhdfs://b/c"));
        assertEquals(-1, GovLcOrphanLogParser.countCandidates("task success"));
    }
}
