package id.or.oo.pr.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

public final class PdnCatalog {
    private static final int LIMIT = 4 * 1024 * 1024;
    private final PdnRuntime runtime;
    private final PdnOperations operations;

    public PdnCatalog(PdnRuntime runtime) { this(runtime, new PdnOperations(runtime)); }
    PdnCatalog(PdnRuntime runtime, PdnOperations operations) {
        this.runtime = Objects.requireNonNull(runtime);
        this.operations = Objects.requireNonNull(operations);
    }

    public List<PdnDistributionInfo> available() throws IOException, InterruptedException {
        return distributions(query(runtime.processBuilder(java.util.Arrays.asList("list", "--available", "--json"))), true);
    }
    public List<PdnDistributionInfo> installed() throws IOException, InterruptedException {
        return distributions(query(runtime.processBuilder(java.util.Arrays.asList("list", "--json"))), false);
    }
    public List<PdnMirrorInfo> mirrors() throws IOException, InterruptedException { return mirrors(null); }
    public List<PdnMirrorInfo> mirrors(String distro) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("mirrors");
        if (distro != null) {
            if (!distro.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid distro name");
            args.add(distro);
        }
        args.add("--json");
        return mirrorRows(query(runtime.processBuilder(args)));
    }

    public PdnDownloadCacheInfo cache() throws IOException, InterruptedException { return cache(null); }
    public PdnDownloadCacheInfo cache(String distro) throws IOException, InterruptedException {
        return cacheInfo(query(runtime.cache(distro)));
    }

    public PdnRecoveryInfo recovery() throws IOException, InterruptedException {
        return recoveryInfo(query(runtime.recovery()));
    }

    private byte[] query(ProcessBuilder builder) throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdnResult result;
        try {
            result = operations.run(builder, new PdnListener() {
                public void onStdout(byte[] data) {
                    if (data.length > LIMIT - output.size()) throw new CatalogLimitException();
                    output.write(data, 0, data.length);
                }
            });
        } catch (CatalogLimitException failure) { throw invalid(failure); }
        if (!result.isSuccess()) throw new PdnQueryException(result);
        return output.toByteArray();
    }

    static List<PdnDistributionInfo> distributions(byte[] bytes, boolean available) throws PdnHostException {
        try {
            JSONArray rows = envelope(bytes, "distributions");
            List<PdnDistributionInfo> values = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String name = string(row, "name");
                if (available) values.add(new PdnDistributionInfo(name, string(row, "version"), string(row, "architecture"), number(row, "download_size"), null));
                else values.add(new PdnDistributionInfo(name, null, null, null, string(row, "rootfs"), instance(row, name)));
            }
            return Collections.unmodifiableList(values);
        } catch (Exception failure) { throw invalid(failure); }
    }

    private static PdnInstanceInfo instance(JSONObject row, String rowName) throws JSONException {
        if (!row.has("instance") || row.isNull("instance")) return null;
        JSONObject object = row.getJSONObject("instance");
        long version = number(object, "version");
        String id = string(object, "id");
        String name = string(object, "name");
        String architecture = string(object, "architecture");
        String source = string(object, "source");
        String distro = nullableString(object, "distro");
        String distroVersion = nullableString(object, "distro_version");
        String sourceUrl = nullableString(object, "source_url");
        String sha256 = nullableString(object, "sha256");
        long createdAt = number(object, "created_at");
        if (version != 1 || !id.matches("[0-9a-f]{32}") || !name.equals(rowName)
                || name.length() > 128 || !name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")
                || !architecture.equals("aarch64")
                || !(source.equals("archive") || source.equals("mirror") || source.equals("restore") || source.equals("clone"))
                || (sha256 != null && !sha256.matches("[0-9a-f]{64}"))
                || (distro != null && (distro.length() > 128 || !distro.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")))
                || !optionalAscii(distroVersion, 128) || !optionalAscii(sourceUrl, 2048)
                || ((source.equals("archive") || source.equals("mirror")) && (distro == null || distroVersion == null || sha256 == null))
                || (source.equals("mirror") && sourceUrl == null)) {
            throw new IllegalArgumentException("Invalid instance metadata");
        }
        return new PdnInstanceInfo((int)version, id, name, distro, distroVersion, architecture,
            source, sourceUrl, sha256, createdAt);
    }

    private static boolean optionalAscii(String value, int limit) {
        if (value == null) return true;
        if (value.isEmpty() || value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }

    private static String nullableString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (value == JSONObject.NULL) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
        return (String)value;
    }

    static List<PdnMirrorInfo> mirrorRows(byte[] bytes) throws PdnHostException {
        try {
            JSONArray rows = envelope(bytes, "mirrors");
            List<PdnMirrorInfo> values = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long priority = number(row, "priority");
                Object official = row.get("official");
                if (!(official instanceof Boolean) || priority > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid mirror metadata");
                values.add(new PdnMirrorInfo(string(row, "distro"), string(row, "name"), string(row, "base_url"), string(row, "url"), (int)priority, (Boolean)official));
            }
            return Collections.unmodifiableList(values);
        } catch (Exception failure) { throw invalid(failure); }
    }

    static PdnDownloadCacheInfo cacheInfo(byte[] bytes) throws PdnHostException {
        try {
            JSONObject object = envelopeObject(bytes);
            String path = string(object, "path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("Invalid cache path");
            long totalBytes = number(object, "total_bytes");
            JSONArray rows = object.getJSONArray("entries");
            List<PdnDownloadCacheEntry> values = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            long sum = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = string(row, "id");
                if (!id.matches("[0-9a-f]{64}-[0-9a-f]{64}\\.part") || !ids.add(id)) throw new IllegalArgumentException("Invalid cache id");
                String distro = nullableString(row, "distro");
                String mirror = nullableString(row, "mirror");
                if ((distro != null && distro.isEmpty()) || (mirror != null && mirror.isEmpty())) throw new IllegalArgumentException("Invalid cache metadata");
                long size = number(row, "bytes");
                Long total = row.get("total") == JSONObject.NULL ? null : Long.valueOf(number(row, "total"));
                long updatedAt = number(row, "updated_at");
                sum = Math.addExact(sum, size);
                values.add(new PdnDownloadCacheEntry(id, distro, mirror, size, total, updatedAt));
            }
            if (sum != totalBytes) throw new IllegalArgumentException("Invalid cache byte total");
            return new PdnDownloadCacheInfo(path, totalBytes, values);
        } catch (Exception failure) { throw invalid(failure); }
    }

    static PdnRecoveryInfo recoveryInfo(byte[] bytes) throws PdnHostException {
        try {
            JSONObject object = envelopeObject(bytes);
            String path = string(object, "path");
            if (!path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid recovery path");
            List<PdnRecoveryEntry> entries = new ArrayList<>();
            Set<String> directories = new HashSet<>();
            JSONArray rows = object.getJSONArray("entries");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String directory = string(row, "directory"), id = nullableString(row, "id");
                boolean current = directory.matches("\\.pdn-install-[0-9a-f]{32}");
                if ((!current && !directory.matches("\\.pdn-(alpine|ubuntu|debian|arch)-[A-Za-z0-9]{6}")) || !directories.add(directory))
                    throw new IllegalArgumentException("Invalid recovery directory");
                if (current ? !directory.substring(13).equals(id) : id != null) throw new IllegalArgumentException("Invalid recovery identity");
                String target = nullableString(row, "target"), phase = nullableString(row, "phase"), boot = nullableString(row, "boot_id");
                Long pid = row.get("pid") == JSONObject.NULL ? null : number(row, "pid");
                Long start = row.get("start_time") == JSONObject.NULL ? null : number(row, "start_time");
                Long created = row.get("created_at") == JSONObject.NULL ? null : number(row, "created_at");
                String status = string(row, "status"), reason = string(row, "reason");
                boolean interrupted = "interrupted".equals(status);
                boolean active = "active".equals(status);
                boolean unknown = "unknown".equals(status);
                if (!(interrupted || active || unknown)) throw new IllegalArgumentException("Invalid recovery status");
                if (interrupted ? !java.util.Arrays.asList("boot_changed", "process_missing", "pid_reused").contains(reason)
                        : active ? !"owner_alive".equals(reason)
                        : !java.util.Arrays.asList("ownership_unproven", "identity_unavailable", "process_unavailable", "boot_unavailable").contains(reason))
                    throw new IllegalArgumentException("Invalid recovery reason");
                if (target == null) {
                    if (!unknown || !"ownership_unproven".equals(reason) || phase != null || pid != null || start != null || boot != null || created != null)
                        throw new IllegalArgumentException("Unproven recovery identity");
                } else {
                    if ((unknown && "ownership_unproven".equals(reason)) || (interrupted && "boot_changed".equals(reason) && boot == null)
                            || !current || target.length() > 128 || !target.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") || phase == null
                            || !java.util.Arrays.asList("preparing", "copying", "downloading", "verifying", "extracting", "configuring", "publishing").contains(phase)
                            || pid == null || pid <= 0 || pid > Integer.MAX_VALUE || created == null || (start != null && start <= 0)
                            || (boot != null && !boot.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                            || (active && (boot == null || start == null)) || (interrupted && !"boot_changed".equals(reason) && start == null))
                        throw new IllegalArgumentException("Invalid recovery metadata");
                }
                entries.add(new PdnRecoveryEntry(directory, id, target, phase, pid, start, boot, created, status, reason));
            }
            return new PdnRecoveryInfo(path, entries);
        } catch (Exception failure) { throw invalid(failure); }
    }

    private static JSONArray envelope(byte[] bytes, String key) throws CharacterCodingException, JSONException {
        return envelopeObject(bytes).getJSONArray(key);
    }

    private static JSONObject envelopeObject(byte[] bytes) throws CharacterCodingException, JSONException {
        if (bytes.length > LIMIT) throw new IllegalArgumentException("Metadata exceeds size limit");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        PdnJson.validate(text);
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Invalid metadata envelope");
        JSONObject object = (JSONObject)value;
        if (number(object, "version") != 1) throw new IllegalArgumentException("Unsupported metadata version");
        return object;
    }

    private static String string(JSONObject row, String key) throws JSONException {
        Object value = row.get(key);
        if (!(value instanceof String) || ((String)value).isEmpty()) throw new IllegalArgumentException("Invalid " + key);
        return (String)value;
    }
    private static long number(JSONObject row, String key) throws JSONException {
        Object value = row.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("Invalid " + key);
        long number = ((Number)value).longValue();
        if (number < 0) throw new IllegalArgumentException("Invalid " + key);
        return number;
    }
    private static PdnHostException invalid(Throwable cause) {
        return new PdnHostException("host_catalog_protocol", "Native metadata is invalid or unsupported", "Use matching PDN native binaries and AAR versions; inspect the query output", cause);
    }
    private static final class CatalogLimitException extends RuntimeException {}
}
