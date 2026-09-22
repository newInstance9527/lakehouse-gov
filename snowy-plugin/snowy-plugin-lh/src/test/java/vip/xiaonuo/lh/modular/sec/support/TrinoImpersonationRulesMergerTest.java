package vip.xiaonuo.lh.modular.sec.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoImpersonationRulesMergerTest {

    @TempDir
    Path tmp;

    @Test
    void newUserPatternQuotesAndJoins() {
        assertEquals("(?!)", TrinoImpersonationRulesMerger.buildNewUserPattern(List.of()));
        assertEquals("(?!)", TrinoImpersonationRulesMerger.buildNewUserPattern(null));
        String p = TrinoImpersonationRulesMerger.buildNewUserPattern(List.of("superAdmin", "alice"));
        assertEquals("^(" + Pattern.quote("superAdmin") + "|" + Pattern.quote("alice") + ")$", p);
        Pattern compiled = Pattern.compile(p);
        assertTrue(compiled.matcher("superAdmin").matches());
        assertTrue(compiled.matcher("alice").matches());
        assertFalse(compiled.matcher("bob").matches());
        assertFalse(compiled.matcher("super").matches());
    }

    @Test
    void emptyPatternNeverMatches() {
        Pattern compiled = Pattern.compile(TrinoImpersonationRulesMerger.EMPTY_NEW_USER);
        assertFalse(compiled.matcher("admin").matches());
        assertFalse(compiled.matcher("").matches());
    }

    @Test
    void mergeReplacesOnlyImpersonation() {
        Map<String, Object> existing = TrinoImpersonationRulesMerger.parseRulesJson("""
                {
                  "catalogs": [{"user":".*","catalog":"iceberg","allow":"all"}],
                  "impersonation": [{"original_user":"admin","new_user":".*","allow":true}]
                }
                """);
        Map<String, Object> rule = TrinoImpersonationRulesMerger.buildImpersonationRule(
                "admin", List.of("superAdmin"));
        Map<String, Object> merged = TrinoImpersonationRulesMerger.merge(existing, List.of(rule));
        assertEquals(1, ((List<?>) merged.get("catalogs")).size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> imp = (List<Map<String, Object>>) merged.get("impersonation");
        assertEquals(1, imp.size());
        assertEquals("admin", imp.get(0).get("original_user"));
        assertEquals("^(" + Pattern.quote("superAdmin") + ")$", imp.get(0).get("new_user"));
        assertEquals(true, imp.get(0).get("allow"));
    }

    @Test
    void mergeNullExistingBootstraps() {
        Map<String, Object> rule = TrinoImpersonationRulesMerger.buildImpersonationRule("svc", List.of("u1"));
        Map<String, Object> merged = TrinoImpersonationRulesMerger.merge(null, List.of(rule));
        assertTrue(merged.containsKey("catalogs"));
        assertTrue(merged.containsKey("schemas"));
        assertTrue(merged.containsKey("tables"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> imp = (List<Map<String, Object>>) merged.get("impersonation");
        assertEquals("svc", imp.get(0).get("original_user"));
        assertEquals("^(" + Pattern.quote("u1") + ")$", imp.get(0).get("new_user"));
    }

    @Test
    void roundTripWriteAndMark() throws Exception {
        Map<String, Object> rule = TrinoImpersonationRulesMerger.buildImpersonationRule(
                "admin", List.of("superAdmin", "devUser"));
        Map<String, Object> merged = TrinoImpersonationRulesMerger.merge(
                TrinoImpersonationRulesMerger.bootstrapBase(), List.of(rule));
        String body = TrinoImpersonationRulesMerger.toPrettyJson(merged);
        Path file = tmp.resolve("rules.json");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        Map<String, Object> reloaded = TrinoImpersonationRulesMerger.parseRulesJson(
                Files.readString(file, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> imp = (List<Map<String, Object>>) reloaded.get("impersonation");
        assertEquals(
                "^(" + Pattern.quote("superAdmin") + "|" + Pattern.quote("devUser") + ")$",
                imp.get(0).get("new_user"));
        String mark = TrinoImpersonationRulesMerger.contentMark(body);
        assertEquals(64, mark.length());
        assertEquals(mark, TrinoImpersonationRulesMerger.contentMark(body));
    }

    @Test
    void specialCharsInPrincipalAreQuoted() {
        String p = TrinoImpersonationRulesMerger.buildNewUserPattern(List.of("a.b"));
        Pattern compiled = Pattern.compile(p);
        assertTrue(compiled.matcher("a.b").matches());
        assertFalse(compiled.matcher("axb").matches());
    }
}
