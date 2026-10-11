package org.example.pdnprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

public final class ProbeDocumentProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/gzip"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"archive".equals(name) && !"backup".equals(name)) throw new FileNotFoundException("Unknown fixture");
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "document-" + name + ".tar.gz"),
                ParcelFileDescriptor.parseMode(mode));
    }
}
