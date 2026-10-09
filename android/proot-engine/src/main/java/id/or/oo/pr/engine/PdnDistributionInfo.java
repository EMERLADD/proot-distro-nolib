package id.or.oo.pr.engine;

public final class PdnDistributionInfo {
    private final String name;
    private final String version;
    private final String architecture;
    private final Long downloadSize;
    private final String rootfs;

    PdnDistributionInfo(String name, String version, String architecture, Long downloadSize, String rootfs) {
        this.name = name;
        this.version = version;
        this.architecture = architecture;
        this.downloadSize = downloadSize;
        this.rootfs = rootfs;
    }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getArchitecture() { return architecture; }
    public Long getDownloadSize() { return downloadSize; }
    public String getRootfs() { return rootfs; }
}
