package id.or.oo.pr.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PdnRecoveryInfo {
    private final String path;
    private final List<PdnRecoveryEntry> entries;
    PdnRecoveryInfo(String path, List<PdnRecoveryEntry> entries) {
        this.path = path;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
    public String getPath() { return path; }
    public List<PdnRecoveryEntry> getEntries() { return entries; }
}
