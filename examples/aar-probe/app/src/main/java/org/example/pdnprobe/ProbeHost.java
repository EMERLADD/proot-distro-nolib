package org.example.pdnprobe;

import android.content.Context;
import id.or.oo.pr.engine.ProotHost;
import java.io.File;

public final class ProbeHost implements ProotHost {
    private final Context context;
    public ProbeHost(Context context) { this.context = context.getApplicationContext(); }
    @Override public File getNativeLibDir() { return new File(context.getApplicationInfo().nativeLibraryDir); }
    @Override public File getPrefixDir() { return new File(context.getFilesDir(), "program-data"); }
    @Override public File getHomeDir() { return new File(context.getFilesDir(), "home"); }
    @Override public File getCacheDir() { return new File(context.getCacheDir(), "engine"); }
    @Override public String getPackageName() { return context.getPackageName(); }
}
