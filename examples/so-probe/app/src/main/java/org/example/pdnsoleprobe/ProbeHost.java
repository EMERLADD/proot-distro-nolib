package org.example.pdnsoleprobe;

import android.content.Context;
import java.io.File;

public final class ProbeHost {
    private final Context context;
    public ProbeHost(Context context) { this.context = context.getApplicationContext(); }
    public File getNativeLibDir() { return new File(context.getApplicationInfo().nativeLibraryDir); }
    public File getPrefixDir() { return new File(context.getFilesDir(), "program-data"); }
    public File getHomeDir() { return new File(context.getFilesDir(), "home"); }
    public File getCacheDir() { return new File(context.getCacheDir(), "engine"); }
    public String getPackageName() { return context.getPackageName(); }
}
