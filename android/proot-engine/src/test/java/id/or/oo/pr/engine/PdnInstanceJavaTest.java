package id.or.oo.pr.engine;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

public class PdnInstanceJavaTest {
    private JSONObject metadata() throws Exception {
        return new JSONObject("{\"version\":1,\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"ai-python\",\"distro\":\"alpine\",\"distro_version\":\"3.24.2\",\"architecture\":\"aarch64\",\"source\":\"archive\",\"source_url\":\"https://example.com/rootfs.tar.gz\",\"sha256\":\"" + "a".repeat(64) + "\",\"created_at\":1791620000}");
    }
    private PdnDistributionInfo parse(Object instance) throws Exception { return parse(instance, "ai-python"); }
    private PdnDistributionInfo parse(Object instance, String name) throws Exception {
        JSONObject row = new JSONObject().put("name", name).put("rootfs", "/roots/ai-python");
        if (instance != null) row.put("instance", instance);
        return PdnCatalog.distributions(new JSONObject().put("version", 1).put("distributions", new org.json.JSONArray().put(row))
            .toString().getBytes(StandardCharsets.UTF_8), false).get(0);
    }
    private void invalid(Object value) throws Exception {
        try { parse(value); fail("Accepted invalid metadata: " + value); }
        catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); }
    }
    @Test public void immutableMetadataHasTypedGettersAndPreservesLegacyRows() throws Exception {
        assertNull(parse(null).getInstance());
        assertNull(parse(JSONObject.NULL).getInstance());
        PdnDistributionInfo row = parse(metadata());
        PdnInstanceInfo value = row.getInstance();
        assertEquals(1, value.getVersion());
        assertEquals("0123456789abcdef0123456789abcdef", value.getId());
        assertEquals(row.getName(), value.getName());
        assertEquals("alpine", value.getDistro());
        assertEquals("3.24.2", value.getDistroVersion());
        assertEquals("aarch64", value.getArchitecture());
        assertEquals("archive", value.getSource());
        assertEquals("https://example.com/rootfs.tar.gz", value.getSourceUrl());
        assertEquals("a".repeat(64), value.getSha256());
        assertEquals(1791620000L, value.getCreatedAt());
        assertNull(row.getVersion());
        assertNull(row.getArchitecture());
        assertTrue(Modifier.isFinal(PdnInstanceInfo.class.getModifiers()));
        for (java.lang.reflect.Field field : PdnInstanceInfo.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
        JSONObject restored = metadata().put("source", "restore").put("created_at", 0);
        for (String key : new String[]{"distro", "distro_version", "source_url", "sha256"}) restored.put(key, JSONObject.NULL);
        PdnInstanceInfo unknown = parse(restored).getInstance();
        assertEquals("restore", unknown.getSource());
        assertEquals(0L, unknown.getCreatedAt());
        assertNull(unknown.getDistro()); assertNull(unknown.getDistroVersion());
        assertNull(unknown.getSourceUrl()); assertNull(unknown.getSha256());
        assertEquals("mirror", parse(metadata().put("source", "mirror")).getInstance().getSource());
        assertEquals("clone", parse(restored.put("source", "clone")).getInstance().getSource());
        assertEquals("clone", parse(metadata().put("source", "clone")).getInstance().getSource());
    }
    @Test public void rejectsMalformedTypesMissingFieldsAndUnsupportedValues() throws Exception {
        for (Object value : new Object[]{1, "metadata", true, new org.json.JSONArray()}) invalid(value);
        for (String key : new String[]{"version", "id", "name", "distro", "distro_version", "architecture", "source", "source_url", "sha256", "created_at"}) {
            JSONObject missing = metadata(); missing.remove(key); invalid(missing);
            invalid(metadata().put(key, true));
        }
        for (Object value : new Object[]{0, 2, "1", 1.5, JSONObject.NULL}) invalid(metadata().put("version", value));
        for (String value : new String[]{"", "A".repeat(32), "a".repeat(31), "g".repeat(32)}) invalid(metadata().put("id", value));
        for (String value : new String[]{"", "A".repeat(64), "a".repeat(63), "g".repeat(64)}) invalid(metadata().put("sha256", value));
        for (String value : new String[]{"other", "AI-PYTHON", "../a", "中文", "a".repeat(129)}) invalid(metadata().put("name", value));
        invalid(metadata().put("architecture", "x86_64"));
        invalid(metadata().put("source", "unknown"));
        for (Object value : new Object[]{-1, 1.5, "1791620000", JSONObject.NULL}) invalid(metadata().put("created_at", value));
        assertEquals(Long.MAX_VALUE, parse(metadata().put("created_at", Long.MAX_VALUE)).getInstance().getCreatedAt());
    }
    @Test public void provenanceMatchesNativeBoundsAndSourceRequirements() throws Exception {
        for (String key : new String[]{"distro", "distro_version", "sha256"}) invalid(metadata().put(key, JSONObject.NULL));
        invalid(metadata().put("source", "mirror").put("source_url", JSONObject.NULL));
        for (String value : new String[]{"", "bad/name", "a".repeat(129)}) invalid(metadata().put("distro", value));
        for (String value : new String[]{"", "bad\nversion", "中文", "a".repeat(129)}) invalid(metadata().put("distro_version", value));
        for (String value : new String[]{"", "bad\turl", "中文", "a".repeat(2049)}) invalid(metadata().put("source_url", value));
        assertEquals(128, parse(metadata().put("distro_version", "a".repeat(128))).getInstance().getDistroVersion().length());
        assertEquals(2048, parse(metadata().put("source_url", "a".repeat(2048))).getInstance().getSourceUrl().length());
    }
    @Test public void metadataNamesUseAsciiAndNativeLengthLimit() throws Exception {
        String boundary = "a".repeat(128);
        assertEquals(boundary, parse(metadata().put("name", boundary), boundary).getInstance().getName());
        for (String name : new String[]{"a".repeat(129), "中文", ".hidden", "--option", "a b"}) {
            try { parse(metadata().put("name", name), name); fail("Accepted invalid name"); }
            catch (PdnHostException expected) { assertEquals("host_catalog_protocol", expected.getCode()); }
        }
    }
    @Test public void javaInstallOverloadsRemainAvailable() throws Exception {
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("install", String.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("install", String.class, String.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("install", String.class, String.class, java.io.File.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("installAs", String.class, String.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("installAs", String.class, String.class, String.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("installAs", String.class, String.class, String.class, java.io.File.class).getReturnType());
    }
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    @Test public void cloneAndRenameAreCallableFromJavaAndRejectInvalidTargets() throws Exception {
        java.io.File base = temporary.newFolder("java host");
        ProotHost host = new ProotHost() {
            public java.io.File getNativeLibDir() { return new java.io.File(base, "native"); }
            public java.io.File getPrefixDir() { return new java.io.File(base, "prefix"); }
            public java.io.File getHomeDir() { return new java.io.File(base, "home"); }
            public java.io.File getCacheDir() { return new java.io.File(base, "cache"); }
            public String getPackageName() { return "test.pdn.host"; }
        };
        assertTrue(host.getNativeLibDir().mkdirs());
        for (String name : new String[]{"libpdn.so", "libproot-loader.so"}) {
            java.io.File binary = new java.io.File(host.getNativeLibDir(), name);
            assertTrue(binary.createNewFile());
            assertTrue(binary.setExecutable(true));
        }
        PdnRuntime runtime = new PdnRuntime(host);
        assertEquals(java.util.Arrays.asList(runtime.getExecutable().getAbsolutePath(), "clone", "ai-python", "ai-test"),
            runtime.clone("ai-python", "ai-test").command());
        assertEquals(java.util.Arrays.asList(runtime.getExecutable().getAbsolutePath(), "rename", "ai-python", "workspace-python"),
            runtime.rename("ai-python", "workspace-python").command());
        assertThrows(IllegalArgumentException.class, () -> runtime.clone("source", "a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> runtime.rename("source", "../target"));
        assertThrows(IllegalArgumentException.class, () -> runtime.clone("../source", "target"));
        assertThrows(IllegalArgumentException.class, () -> runtime.rename("--source", "target"));
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("clone", String.class, String.class).getReturnType());
        assertEquals(ProcessBuilder.class, PdnRuntime.class.getMethod("rename", String.class, String.class).getReturnType());
    }
    @Test public void availableCatalogDoesNotInterpretInstanceMetadata() throws Exception {
        String text = "{\"version\":1,\"distributions\":[{\"name\":\"alpine\",\"version\":\"3\",\"architecture\":\"aarch64\",\"download_size\":1,\"instance\":false}]}";
        assertNull(PdnCatalog.distributions(text.getBytes(StandardCharsets.UTF_8), true).get(0).getInstance());
    }
}
