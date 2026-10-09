package id.or.oo.pr.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PdnConfiguration {
    private final String user;
    private final String workDir;
    private final List<PdnBind> binds;
    private final Map<String, String> environment;

    public PdnConfiguration() {
        this("root", "/workspace", Collections.emptyList(), Collections.emptyMap());
    }

    public PdnConfiguration(String user, String workDir, List<PdnBind> binds, Map<String, String> environment) {
        this.user = value(user);
        if (user.isEmpty()) throw new IllegalArgumentException("A guest user is required");
        this.workDir = absolutePath(workDir);
        ArrayList<PdnBind> copiedBinds = new ArrayList<>(Objects.requireNonNull(binds));
        for (PdnBind bind : copiedBinds) Objects.requireNonNull(bind);
        this.binds = Collections.unmodifiableList(copiedBinds);
        LinkedHashMap<String, String> copiedEnvironment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : Objects.requireNonNull(environment).entrySet()) {
            if (!Objects.requireNonNull(entry.getKey()).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid guest environment key");
            }
            copiedEnvironment.put(entry.getKey(), value(entry.getValue()));
        }
        this.environment = Collections.unmodifiableMap(copiedEnvironment);
    }

    static String value(String value) {
        Objects.requireNonNull(value);
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Values cannot contain NUL");
        return value;
    }

    static String absolutePath(String value) {
        value(value);
        if (!value.startsWith("/")) throw new IllegalArgumentException("Guest paths must be absolute");
        return value;
    }

    public String getUser() { return user; }
    public String getWorkDir() { return workDir; }
    public List<PdnBind> getBinds() { return binds; }
    public Map<String, String> getEnvironment() { return environment; }
}
