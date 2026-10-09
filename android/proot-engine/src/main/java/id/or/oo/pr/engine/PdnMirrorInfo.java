package id.or.oo.pr.engine;

public final class PdnMirrorInfo {
    private final String distro;
    private final String name;
    private final String baseUrl;
    private final String url;
    private final int priority;
    private final boolean official;

    PdnMirrorInfo(String distro, String name, String baseUrl, String url, int priority, boolean official) {
        this.distro = distro;
        this.name = name;
        this.baseUrl = baseUrl;
        this.url = url;
        this.priority = priority;
        this.official = official;
    }
    public String getDistro() { return distro; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getUrl() { return url; }
    public int getPriority() { return priority; }
    public boolean isOfficial() { return official; }
}
