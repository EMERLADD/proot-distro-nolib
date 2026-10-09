package id.or.oo.pr.engine;

import java.io.File;
import java.util.Objects;

public final class PdnBind {
    private final File hostFile;
    private final String guestPath;

    public PdnBind(File hostFile, String guestPath) {
        this.hostFile = Objects.requireNonNull(hostFile).getAbsoluteFile();
        this.guestPath = PdnConfiguration.absolutePath(guestPath);
        if (this.hostFile.getPath().indexOf('\0') >= 0 || this.hostFile.getPath().contains(":")) {
            throw new IllegalArgumentException("Bind host paths cannot contain NUL or colon");
        }
        if (guestPath.contains(":")) throw new IllegalArgumentException("Bind guest paths cannot contain colon");
    }

    public File getHostFile() { return hostFile; }
    public String getGuestPath() { return guestPath; }
}
