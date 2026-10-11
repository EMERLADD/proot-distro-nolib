package id.or.oo.pr.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PdnDownloadCacheInfo {
    private final String path;
    private final long totalBytes;
    private final List<PdnDownloadCacheEntry> entries;

    PdnDownloadCacheInfo(String path, long totalBytes, List<PdnDownloadCacheEntry> entries) {
        this.path = path;
        this.totalBytes = totalBytes;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
    public String getPath() { return path; }
    public long getTotalBytes() { return totalBytes; }
    public List<PdnDownloadCacheEntry> getEntries() { return entries; }
}
