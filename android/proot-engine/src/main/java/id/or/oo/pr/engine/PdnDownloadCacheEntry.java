package id.or.oo.pr.engine;

public final class PdnDownloadCacheEntry {
    private final String id;
    private final String distro;
    private final String mirror;
    private final long bytes;
    private final Long total;
    private final long updatedAt;

    PdnDownloadCacheEntry(String id, String distro, String mirror, long bytes, Long total, long updatedAt) {
        this.id = id;
        this.distro = distro;
        this.mirror = mirror;
        this.bytes = bytes;
        this.total = total;
        this.updatedAt = updatedAt;
    }
    public String getId() { return id; }
    public String getDistro() { return distro; }
    public String getMirror() { return mirror; }
    public long getBytes() { return bytes; }
    public Long getTotal() { return total; }
    public long getUpdatedAt() { return updatedAt; }
}
