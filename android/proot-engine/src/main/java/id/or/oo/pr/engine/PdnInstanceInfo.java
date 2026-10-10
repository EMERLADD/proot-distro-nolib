package id.or.oo.pr.engine;

public final class PdnInstanceInfo {
    private final int version;
    private final String id;
    private final String name;
    private final String distro;
    private final String distroVersion;
    private final String architecture;
    private final String source;
    private final String sourceUrl;
    private final String sha256;
    private final long createdAt;

    PdnInstanceInfo(int version, String id, String name, String distro, String distroVersion,
                    String architecture, String source, String sourceUrl, String sha256, long createdAt) {
        this.version = version;
        this.id = id;
        this.name = name;
        this.distro = distro;
        this.distroVersion = distroVersion;
        this.architecture = architecture;
        this.source = source;
        this.sourceUrl = sourceUrl;
        this.sha256 = sha256;
        this.createdAt = createdAt;
    }
    public int getVersion() { return version; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDistro() { return distro; }
    public String getDistroVersion() { return distroVersion; }
    public String getArchitecture() { return architecture; }
    public String getSource() { return source; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSha256() { return sha256; }
    public long getCreatedAt() { return createdAt; }
}
