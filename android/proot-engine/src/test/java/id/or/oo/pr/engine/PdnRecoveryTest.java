package id.or.oo.pr.engine;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdnRecoveryTest {
    private final String id = "abcdef1234567890abcdef1234567890";
    private JSONObject entry() throws Exception {
        return new JSONObject().put("directory", ".pdn-install-" + id).put("id", id)
            .put("target", "workspace").put("phase", "extracting").put("pid", 123)
            .put("start_time", 500).put("boot_id", "12345678-1234-1234-1234-123456789abc")
            .put("created_at", 100).put("status", "interrupted").put("reason", "process_missing");
    }
    private PdnRecoveryInfo parse(JSONObject... rows) throws Exception {
        JSONArray entries = new JSONArray();
        for (JSONObject row : rows) entries.put(row);
        return PdnCatalog.recoveryInfo(new JSONObject().put("version", 1).put("path", "/private/roots")
                .put("entries", entries).toString().getBytes(StandardCharsets.UTF_8));
    }
    private void invalid(JSONObject row) throws Exception {
        try { parse(row); fail(); }
        catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); }
    }
    @Test public void immutableSnapshotAndIdentity() throws Exception {
        PdnRecoveryInfo info = parse(entry());
        assertEquals("/private/roots", info.getPath());
        PdnRecoveryEntry e = info.getEntries().get(0);
        assertEquals(id, e.getId()); assertEquals(".pdn-install-" + id, e.getDirectory());
        assertEquals("workspace", e.getTarget()); assertEquals("extracting", e.getPhase());
        assertEquals(Long.valueOf(123), e.getPid()); assertEquals(Long.valueOf(500), e.getStartTime());
        assertEquals(Long.valueOf(100), e.getCreatedAt()); assertNotNull(e.getBootId());
        assertTrue(e.isInterrupted()); assertEquals("process_missing", e.getReason());
        try { info.getEntries().clear(); fail(); } catch (UnsupportedOperationException expected) {}
        assertTrue(parse().getEntries().isEmpty());
    }
    @Test public void legacyAndUnprovenRowsRemainUnknown() throws Exception {
        JSONObject row = entry().put("directory", ".pdn-alpine-ABC123").put("id", JSONObject.NULL)
            .put("status", "unknown").put("reason", "ownership_unproven");
        for (String key : new String[]{"target", "phase", "pid", "start_time", "boot_id", "created_at"}) row.put(key, JSONObject.NULL);
        PdnRecoveryEntry e = parse(row).getEntries().get(0);
        assertFalse(e.isInterrupted()); assertNull(e.getId()); assertNull(e.getPid());
        assertNull(e.getTarget()); assertNull(e.getStartTime()); assertNull(e.getCreatedAt());
    }
    @Test public void activeOwnerRequiresCompleteMatchingIdentity() throws Exception {
        JSONObject row = entry().put("status", "active").put("reason", "owner_alive");
        assertFalse(parse(row).getEntries().get(0).isInterrupted());
        invalid(row.put("start_time", JSONObject.NULL));
        invalid(entry().put("status", "active").put("reason", "owner_alive").put("boot_id", JSONObject.NULL));
    }
    @Test public void missingBootAndStartAreRepresentedWithoutInventingIdentity() throws Exception {
        JSONObject row = entry().put("boot_id", JSONObject.NULL);
        assertNull(parse(row).getEntries().get(0).getBootId());
        row.put("start_time", JSONObject.NULL).put("status", "unknown").put("reason", "identity_unavailable");
        assertNull(parse(row).getEntries().get(0).getStartTime());
    }
    @Test public void invalidValuesAreRejected() throws Exception {
        for (Object value : new Object[]{-1, true, "123", 1.5, 0, 2147483648L}) invalid(entry().put("pid", value));
        invalid(entry().put("directory", "../../outside")); invalid(entry().put("id", "f" + id.substring(1)));
        invalid(entry().put("target", "../project")); invalid(entry().put("phase", "other"));
        invalid(entry().put("boot_id", "wrong")); invalid(entry().put("status", "ready"));
        invalid(entry().put("reason", "owner_alive")); invalid(entry().put("start_time", 0));
        invalid(entry().put("created_at", -1)); invalid(entry().put("target", JSONObject.NULL));
        invalid(entry().put("status", "unknown").put("reason", "ownership_unproven"));
    }
    @Test public void duplicateRowsAndProtocolVersionsAreRejected() throws Exception {
        try { parse(entry(), entry()); fail(); } catch (PdnHostException expected) {}
        for (String text : new String[]{"{}", "{\"version\":2,\"path\":\"/roots\",\"entries\":[]}", "{\"version\":1,\"path\":\"relative\",\"entries\":[]}"}) {
            try { PdnCatalog.recoveryInfo(text.getBytes(StandardCharsets.UTF_8)); fail(); } catch (PdnHostException expected) {}
        }
    }
}
