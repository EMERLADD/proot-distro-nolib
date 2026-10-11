package id.or.oo.pr.engine;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class PdnDownloadCacheTest {
    private final String id = new String(new char[64]).replace('\0', 'a') + "-" + new String(new char[64]).replace('\0', 'b') + ".part";
    private String row() {
        return "{\"id\":\"" + id + "\",\"distro\":\"alpine\",\"mirror\":\"official\",\"bytes\":37,\"total\":4028030,\"updated_at\":123}";
    }
    private String envelope(String rows, String total) {
        return "{\"version\":1,\"path\":\"/roots/.pdn-downloads\",\"total_bytes\":" + total + ",\"entries\":[" + rows + "]}";
    }
    private PdnDownloadCacheInfo parse(String value) throws Exception {
        return PdnCatalog.cacheInfo(value.getBytes(StandardCharsets.UTF_8));
    }
    private void invalid(String value) throws Exception {
        try { parse(value); fail(value); }
        catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); assertNotNull(expected.getCause()); }
    }
    @Test public void cacheMetadataIsTypedAndImmutable() throws Exception {
        PdnDownloadCacheInfo info = parse(envelope(row(), "37"));
        assertEquals("/roots/.pdn-downloads", info.getPath());
        assertEquals(37, info.getTotalBytes());
        PdnDownloadCacheEntry entry = info.getEntries().get(0);
        assertEquals(id, entry.getId());
        assertEquals("alpine", entry.getDistro());
        assertEquals("official", entry.getMirror());
        assertEquals(37, entry.getBytes());
        assertEquals(Long.valueOf(4028030), entry.getTotal());
        assertEquals(123, entry.getUpdatedAt());
        try { info.getEntries().clear(); fail(); } catch (UnsupportedOperationException expected) {}
        ArrayList<PdnDownloadCacheEntry> source = new ArrayList<>(info.getEntries());
        PdnDownloadCacheInfo copied = new PdnDownloadCacheInfo(info.getPath(), 37, source);
        source.clear();
        assertEquals(1, copied.getEntries().size());
    }
    @Test public void staleAndOversizedEntriesRemainQueryable() throws Exception {
        PdnDownloadCacheEntry stale = parse(envelope(row().replace("\"alpine\"", "null").replace("\"official\"", "null").replace("4028030", "null"), "37")).getEntries().get(0);
        assertNull(stale.getDistro()); assertNull(stale.getMirror()); assertNull(stale.getTotal());
        assertEquals(Long.valueOf(1), parse(envelope(row().replace("4028030", "1"), "37")).getEntries().get(0).getTotal());
        assertEquals("custom-distro", parse(envelope(row().replace("alpine", "custom-distro"), "37")).getEntries().get(0).getDistro());
        assertTrue(parse(envelope("", "0")).getEntries().isEmpty());
    }
    @Test public void strictEnvelopeAndEntrySchema() throws Exception {
        String valid = envelope(row(), "37");
        for (String text : new String[]{"", "[]", "{}", valid + " x", valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("/roots/", "roots/"),
                valid.replace("\"entries\":[" + row() + "]", "\"entries\":{}"), envelope("null", "37"),
                valid.replace(id, "a.part"), valid.replace(id, id.toUpperCase()),
                valid.replace("\"alpine\"", "\"\""), valid.replace("\"official\"", "\"\""),
                valid.replace("\"alpine\"", "true"), valid.replace("\"official\"", "42"),
                valid.replace("\"total\":4028030,", ""), valid.replace("\"distro\":\"alpine\",", ""),
                valid.replace("\"mirror\":\"official\",", ""), valid.replace("\"path\":\"/roots/.pdn-downloads\"", "\"path\":null"),
                envelope(row() + "," + row(), "74"), envelope(row(), "38")}) invalid(text);
        for (String key : new String[]{"version", "total_bytes", "bytes", "total", "updated_at"}) {
            String old = key.equals("version") ? "1" : key.equals("updated_at") ? "123" : key.equals("total") ? "4028030" : "37";
            for (String value : new String[]{"-1", "1.5", "\"37\"", "true", "9223372036854775808"}) {
                invalid(valid.replace("\"" + key + "\":" + old, "\"" + key + "\":" + value));
            }
            if (!key.equals("total")) invalid(valid.replace("\"" + key + "\":" + old, "\"" + key + "\":null"));
        }
        String maximum = row().replace("\"bytes\":37", "\"bytes\":9223372036854775807");
        assertEquals(Long.MAX_VALUE, parse(envelope(maximum, "9223372036854775807")).getTotalBytes());
        invalid(envelope(maximum + "," + row().replace(id, id.replace('a', 'c')), "9223372036854775807"));
        for (byte[] bytes : new byte[][]{new byte[]{(byte)0xff}, new byte[4 * 1024 * 1024 + 1]}) {
            try { PdnCatalog.cacheInfo(bytes); fail(); } catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); }
        }
    }
}
