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

    private static JSONArray envelope(byte[] bytes, String key) throws CharacterCodingException, JSONException {
        if (bytes.length > LIMIT) throw new IllegalArgumentException("Metadata exceeds size limit");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        PdnJson.validate(text);
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Invalid metadata envelope");
        JSONObject object = (JSONObject)value;
        if (number(object, "version") != 1) throw new IllegalArgumentException("Unsupported metadata version");
        return object.getJSONArray(key);
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
