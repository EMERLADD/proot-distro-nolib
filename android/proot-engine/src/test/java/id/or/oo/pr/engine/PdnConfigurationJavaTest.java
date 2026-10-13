package id.or.oo.pr.engine;

import org.junit.Test;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class PdnConfigurationJavaTest {
    @Test public void copiesCollectionsAndValidatesValues() {
        List<PdnBind> binds = new ArrayList<>();
        binds.add(new PdnBind(new File("/host with spaces"), "/guest with spaces"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LITERAL", "$(echo wrong) a=b");
        PdnConfiguration config = new PdnConfiguration("1000:1000", "/guest with spaces", binds, env);
        binds.clear(); env.clear();
        assertEquals("1000:1000", config.getUser());
        assertEquals("/guest with spaces", config.getWorkDir());
        assertEquals(1, config.getBinds().size());
        assertEquals("$(echo wrong) a=b", config.getEnvironment().get("LITERAL"));
        assertThrows(UnsupportedOperationException.class, () -> config.getBinds().clear());
        assertThrows(UnsupportedOperationException.class, () -> config.getEnvironment().clear());
        assertThrows(IllegalArgumentException.class, () -> new PdnBind(new File("/x:y"), "/guest"));
        assertThrows(IllegalArgumentException.class, () -> new PdnBind(new File("/x"), "relative"));
        assertThrows(IllegalArgumentException.class, () -> new PdnConfiguration("a\0b", "/", Collections.emptyList(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new PdnConfiguration("root", "relative", Collections.emptyList(), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new PdnConfiguration("root", "/", Collections.emptyList(), Collections.singletonMap("A=B", "x")));
        assertThrows(IllegalArgumentException.class, () -> new PdnConfiguration("root", "/", Collections.emptyList(), Collections.singletonMap("A", "x\0y")));
    }
    @org.junit.Test public void kotlinDefaultConstructorAbiIsPreserved() throws Exception {
        org.junit.Assert.assertNotNull(PdnRuntime.class.getDeclaredConstructor(ProotHost.class, java.io.File.class,
                java.io.File.class, int.class, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")));
        org.junit.Assert.assertNotNull(PdnRuntime.class.getConstructor(ProotHost.class));
        org.junit.Assert.assertNotNull(PdnRuntime.class.getConstructor(ProotHost.class, java.io.File.class));
        org.junit.Assert.assertNotNull(PdnRuntime.class.getConstructor(ProotHost.class, java.io.File.class, java.io.File.class));
        org.junit.Assert.assertNotNull(PdnRuntime.class.getConstructor(ProotHost.class, PdnConfiguration.class));
    }
    @Test public void executionRulesAreImmutableAndKeepExistingConstructors() {
        List<PdnExecRule> rules = new ArrayList<>();
        rules.add(PdnExecRule.onlyFrom("/usr/sbin/sshd", "/workspace"));
        rules.add(PdnExecRule.deny("/bin/example"));
        PdnExecPolicy policy = new PdnExecPolicy(rules);
        rules.clear();
        PdnConfiguration config = new PdnConfiguration("root", "/workspace", Collections.emptyList(), Collections.emptyMap(), policy);
        assertEquals(2, config.getExecutionPolicy().getRules().size());
        assertNull(policy.getRules().get(1).getWorkingDirectory());
        assertThrows(UnsupportedOperationException.class, () -> policy.getRules().clear());
        assertTrue(new PdnConfiguration().getExecutionPolicy().getRules().isEmpty());
        for (String bad : Arrays.asList("relative", "/a/../b", "/a//b", "/a/", "/a/./b", "/a\0b"))
            assertThrows(IllegalArgumentException.class, () -> PdnExecRule.deny(bad));
        assertThrows(IllegalArgumentException.class, () -> new PdnExecPolicy(Collections.nCopies(129, PdnExecRule.deny("/a"))));
        assertThrows(NullPointerException.class, () -> PdnExecRule.onlyFrom("/a", null));
    }
}
