package id.or.oo.pr.engine;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class PdnCatalogJavaTest {
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test public void queryRoutesValidateAndPreserveNativeFailures() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("pdn-catalog");
        try {
            for (String name : new String[]{"libpdn.so", "libproot-loader.so"}) {
                java.io.File file = directory.resolve(name).toFile();
                java.nio.file.Files.write(file.toPath(), new byte[]{1});
                assertTrue(file.setExecutable(true));
            }
            java.io.File base = directory.toFile();
            ProotHost host = new ProotHost() {
                public java.io.File getNativeLibDir() { return base; }
                public java.io.File getPrefixDir() { return base; }
                public java.io.File getHomeDir() { return base; }
                public java.io.File getCacheDir() { return base; }
                public String getPackageName() { return "test.catalog"; }
            };
            PdnRuntime runtime = new PdnRuntime(host);
            java.util.concurrent.atomic.AtomicReference<List<String>> command = new java.util.concurrent.atomic.AtomicReference<>();
            String[] response = {"{\"version\":1,\"distributions\":[]}"};
            String[] failure = {""};
            PdnOperations operations = new PdnOperations(runtime, builder -> {
                command.set(builder.command());
                String common = "\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"operation\":\"list\",\"sequence\":";
                String script = "printf '%s\\n' '{" + common + "1,\"type\":\"started\"}' > \"$PDN_EVENT_FILE\"; ";
                script += "printf '%s' '" + response[0] + "'; ";
                script += "printf '%s\\n' '{" + common + "2,\"type\":\"result\",\"outcome\":\"" + (failure[0].isEmpty() ? "success" : "manager_error") + "\",\"exit_code\":" + (failure[0].isEmpty() ? "0" : "2") + (failure[0].isEmpty() ? "" : ",\"code\":\"distro_unknown\",\"message\":\"unknown distro\",\"suggestion\":\"select another\"") + "}' >> \"$PDN_EVENT_FILE\"; ";
                if (!failure[0].isEmpty()) script += "exit 2";
                ProcessBuilder shell = new ProcessBuilder("/bin/sh", "-c", script);
                shell.environment().putAll(builder.environment());
                return shell.start();
            });
            PdnCatalog catalog = new PdnCatalog(runtime, operations);
            assertTrue(catalog.available().isEmpty());
            assertEquals(java.util.Arrays.asList(runtime.getExecutable().getAbsolutePath(), "list", "--available", "--json"), command.get());
            assertTrue(catalog.installed().isEmpty());
            assertEquals("--json", command.get().get(2));
            response[0] = "{\"version\":1,\"mirrors\":[]}";
            assertTrue(catalog.mirrors().isEmpty());
            assertEquals(3, command.get().size());
            assertTrue(catalog.mirrors("alpine").isEmpty());
            assertEquals("alpine", command.get().get(2));
            try { catalog.mirrors("--json"); fail(); } catch (IllegalArgumentException expected) {}
            failure[0] = "error";
            try { catalog.mirrors("missing"); fail(); }
            catch (PdnQueryException expected) {
                assertEquals("distro_unknown", expected.getResult().getCode());
                assertEquals("unknown distro", expected.getMessage());
                assertEquals("select another", expected.getResult().getSuggestion());
            }
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
    @Test public void availableMetadataIsTypedAndImmutable() throws Exception {
        List<PdnDistributionInfo> rows = PdnCatalog.distributions(bytes("{\"version\":1,\"distributions\":[{\"name\":\"alpine\",\"version\":\"3.24.2\",\"architecture\":\"aarch64\",\"download_size\":4028030}]}"), true);
        PdnDistributionInfo row = rows.get(0);
        assertEquals("alpine", row.getName());
        assertEquals("3.24.2", row.getVersion());
        assertEquals("aarch64", row.getArchitecture());
        assertEquals(Long.valueOf(4028030), row.getDownloadSize());
        assertNull(row.getRootfs());
        try { rows.clear(); fail(); } catch (UnsupportedOperationException expected) {}
    }
    @Test public void installedDoesNotInventVersionAndPreservesPaths() throws Exception {
        PdnDistributionInfo row = PdnCatalog.distributions(bytes("{\"version\":1,\"distributions\":[{\"name\":\"custom\",\"rootfs\":\"/tmp/中文\\nquote\\\"\"}]}"), false).get(0);
        assertEquals("/tmp/中文\nquote\"", row.getRootfs());
        assertNull(row.getVersion());
        assertNull(row.getArchitecture());
        assertNull(row.getDownloadSize());
        assertTrue(PdnCatalog.distributions(bytes("{\"version\":1,\"distributions\":[]}"), false).isEmpty());
    }
    @Test public void mirrorsHaveExplicitPriorityAndOfficialMarker() throws Exception {
        List<PdnMirrorInfo> rows = PdnCatalog.mirrorRows(bytes("{\"version\":1,\"mirrors\":[{\"distro\":\"debian\",\"name\":\"official\",\"base_url\":\"https://example.com/\",\"url\":\"https://example.com/rootfs.tar.gz\",\"priority\":0,\"official\":true}]}"));
        PdnMirrorInfo row = rows.get(0);
        assertEquals("debian", row.getDistro());
        assertEquals("official", row.getName());
        assertEquals("https://example.com/", row.getBaseUrl());
        assertEquals("https://example.com/rootfs.tar.gz", row.getUrl());
        assertEquals(0, row.getPriority());
        assertTrue(row.isOfficial());
        try { rows.add(row); fail(); } catch (UnsupportedOperationException expected) {}
    }
    @Test public void malformedEnvelopesAndTypesHaveTypedError() throws Exception {
        String[] invalid = {"", "[]", "{}", "{\"version\":2,\"distributions\":[]}",
            "{\"version\":\"1\",\"distributions\":[]}", "{\"version\":1,\"distributions\":{}}",
            "{\"version\":1,\"distributions\":[]} trailing", "{\"version\":1,\"distributions\":[null]}",
            "{\"version\":1,\"distributions\":[{\"name\":42,\"rootfs\":\"/r\"}]}",
            "{\"version\":1,\"distributions\":[{\"name\":\"\",\"rootfs\":\"/r\"}]}"};
        for (String text : invalid) assertInvalid(bytes(text), false);
        assertInvalid(new byte[]{(byte)0xff}, false);
        assertInvalid(new byte[4 * 1024 * 1024 + 1], false);
        for (String number : new String[]{"-1", "1.5", "\"42\"", "true", "9223372036854775808"}) {
            assertInvalid(bytes("{\"version\":1,\"distributions\":[{\"name\":\"a\",\"version\":\"1\",\"architecture\":\"aarch64\",\"download_size\":"+number+"}]}"), true);
        }
    }
    private void assertInvalid(byte[] bytes, boolean available) throws Exception {
        try { PdnCatalog.distributions(bytes, available); fail(); }
        catch (PdnHostException expected) {
            assertEquals("host_catalog_protocol", expected.getCode());
            assertNotNull(expected.getCause());
            assertTrue(expected.getSuggestion().contains("matching"));
        }
    }
    @Test public void malformedMirrorFieldsAreRejected() throws Exception {
        String original = "{\"version\":1,\"mirrors\":[{\"distro\":\"a\",\"name\":\"official\",\"base_url\":\"https://a/\",\"url\":\"https://a/root\",\"priority\":0,\"official\":true}]}";
        for (String text : new String[]{original.replace("\"priority\":0", "\"priority\":2147483648"), original.replace("\"official\":true", "\"official\":1"), original.replace("\"url\":\"https://a/root\"", "\"url\":null")}) {
            try { PdnCatalog.mirrorRows(bytes(text)); fail(); }
            catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); }
        }
    }
}
