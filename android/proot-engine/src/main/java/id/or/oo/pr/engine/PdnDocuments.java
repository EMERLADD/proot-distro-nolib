package id.or.oo.pr.engine;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

public final class PdnDocuments {
    interface Runner { PdnResult run(ProcessBuilder builder, PdnListener listener) throws IOException, InterruptedException; }
    private final PdnRuntime runtime;
    private final Runner runner;
    public PdnDocuments(PdnRuntime runtime) { this(runtime, new PdnOperations(runtime)::run); }
    PdnDocuments(PdnRuntime runtime, Runner runner) {
        this.runtime = Objects.requireNonNull(runtime);
        this.runner = Objects.requireNonNull(runner);
    }

    public PdnResult install(String distro, InputStream input, PdnListener listener) throws IOException, InterruptedException {
        return importing(input, file -> runtime.install(distro, null, file), listener);
    }
    public PdnResult installAs(String distro, String name, InputStream input, PdnListener listener) throws IOException, InterruptedException {
        return importing(input, file -> runtime.installAs(distro, name, null, file), listener);
    }
    public PdnResult restore(String name, InputStream input, PdnListener listener) throws IOException, InterruptedException {
        return importing(input, file -> runtime.restore(name, file), listener);
    }
    public PdnResult backup(String name, OutputStream output, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(output);
        return exporting(name, () -> output, false, listener);
    }
    private interface Output { OutputStream open() throws IOException; }
    private PdnResult exporting(String name, Output destination, boolean owned, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(listener);
        PdnResult result;
        try (Staging staging = staging()) {
            result = runner.run(runtime.backup(name, staging.archive.toFile()), forwarding(listener));
            if (result.isSuccess()) {
                try (InputStream input = Files.newInputStream(staging.archive)) {
                    OutputStream output = destination.open();
                    if (owned) {
                        try (OutputStream closeable = output) { copy(input, closeable); closeable.flush(); }
                    } else { copy(input, output); output.flush(); }
                } catch (IOException | SecurityException failure) { throw documentFailure(failure); }
            }
        }
        listener.onComplete(result);
        return result;
    }

    public PdnResult install(String distro, ContentResolver resolver, Uri uri, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = input(resolver, uri)) { return install(distro, input, listener); }
    }
    public PdnResult installAs(String distro, String name, ContentResolver resolver, Uri uri, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = input(resolver, uri)) { return installAs(distro, name, input, listener); }
    }
    public PdnResult restore(String name, ContentResolver resolver, Uri uri, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = input(resolver, uri)) { return restore(name, input, listener); }
    }
    public PdnResult backup(String name, ContentResolver resolver, Uri uri, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(resolver);
        Objects.requireNonNull(uri);
        return exporting(name, () -> output(resolver, uri), true, listener);
    }
    public PdnResult install(String distro, ParcelFileDescriptor descriptor, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = descriptorInput(descriptor)) { return install(distro, input, listener); }
    }
    public PdnResult installAs(String distro, String name, ParcelFileDescriptor descriptor, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = descriptorInput(descriptor)) { return installAs(distro, name, input, listener); }
    }
    public PdnResult restore(String name, ParcelFileDescriptor descriptor, PdnListener listener) throws IOException, InterruptedException {
        try (InputStream input = descriptorInput(descriptor)) { return restore(name, input, listener); }
    }
    public PdnResult backup(String name, ParcelFileDescriptor descriptor, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(descriptor);
        return exporting(name, () -> descriptorOutput(descriptor), true, listener);
    }

    private interface Builder { ProcessBuilder create(File file); }
    private PdnResult importing(InputStream input, Builder builder, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(listener);
        PdnResult result;
        try (Staging staging = staging()) {
            ProcessBuilder command = builder.create(staging.archive.toFile());
            try (OutputStream output = Files.newOutputStream(staging.archive)) { copy(input, output); }
            catch (IOException | SecurityException failure) { throw documentFailure(failure); }
            result = runner.run(command, forwarding(listener));
        }
        listener.onComplete(result);
        return result;
    }
    private static PdnListener forwarding(PdnListener listener) {
        return new PdnListener() {
            public void onEvent(PdnEvent event) { listener.onEvent(event); }
            public void onStdout(byte[] data) { listener.onStdout(data); }
            public void onStderr(byte[] data) { listener.onStderr(data); }
        };
    }
    private static void copy(InputStream input, OutputStream output) throws IOException, InterruptedException {
        byte[] buffer = new byte[65536];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Document transfer interrupted");
            int size = input.read(buffer);
            if (size < 0) break;
            if (size == 0) {
                int value = input.read();
                if (value < 0) break;
                output.write(value);
            } else output.write(buffer, 0, size);
        }
    }
    private Staging staging() throws PdnHostException {
        try {
            Path cache = new File(runtime.environment().get("PROOT_TMP_DIR")).toPath();
            Files.createDirectories(cache);
            Path directory = Files.createTempDirectory(cache, "pdn-document-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return new Staging(directory);
        } catch (IOException | SecurityException failure) {
            throw new PdnHostException("host_cache_failed", "Cannot prepare document staging directory",
                    "Check private cache permissions and available storage", failure);
        }
    }
    private static PdnHostException documentFailure(Throwable failure) {
        return new PdnHostException("host_document_io_failed", "Cannot read or write selected document",
                "Check document access, provider availability and available storage; retry with a new destination if export is incomplete", failure);
    }
    private static InputStream input(ContentResolver resolver, Uri uri) throws PdnHostException {
        Objects.requireNonNull(resolver);
        Objects.requireNonNull(uri);
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("Document provider returned no input stream");
            return input;
        } catch (IOException | SecurityException failure) { throw documentFailure(failure); }
    }
    private static OutputStream output(ContentResolver resolver, Uri uri) throws PdnHostException {
        try {
            OutputStream output = resolver.openOutputStream(uri, "wt");
            if (output == null) throw new IOException("Document provider returned no output stream");
            return output;
        } catch (IOException | SecurityException failure) { throw documentFailure(failure); }
    }
    private static InputStream descriptorInput(ParcelFileDescriptor descriptor) throws PdnHostException {
        Objects.requireNonNull(descriptor);
        try { return new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor())); }
        catch (IOException | SecurityException failure) { throw documentFailure(failure); }
    }
    private static OutputStream descriptorOutput(ParcelFileDescriptor descriptor) throws PdnHostException {
        Objects.requireNonNull(descriptor);
        try { return new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.getFileDescriptor())); }
        catch (IOException | SecurityException failure) { throw documentFailure(failure); }
    }
    private static final class Staging implements AutoCloseable {
        final Path directory;
        final Path archive;
        Staging(Path directory) { this.directory = directory; this.archive = directory.resolve("archive.tar.gz"); }
        public void close() throws PdnHostException {
            try { Files.deleteIfExists(archive); Files.delete(directory); }
            catch (IOException | SecurityException failure) {
                throw new PdnHostException("host_cleanup_failed", "Cannot remove document staging files",
                        "Check private cache permissions and remove unused pdn-document directories after operations stop", failure);
            }
        }
    }
}
