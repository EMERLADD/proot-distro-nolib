package id.or.oo.pr.engine;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** An execution rule using absolute guest paths. Directory matching is exact. */
public final class PdnExecRule {
    private final String program;
    private final String workingDirectory;
    private PdnExecRule(String program, String workingDirectory) {
        this.program = path(program);
        this.workingDirectory = workingDirectory == null ? null : path(workingDirectory);
    }
    public static PdnExecRule deny(String program) { return new PdnExecRule(program, null); }
    public static PdnExecRule onlyFrom(String program, String workingDirectory) {
        return new PdnExecRule(program, Objects.requireNonNull(workingDirectory));
    }
    static String path(String value) {
        PdnConfiguration.absolutePath(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new IllegalArgumentException("Execution policy paths cannot exceed 512 UTF-8 bytes");
        if (!value.equals("/")) {
            for (String part : value.substring(1).split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals(".."))
                    throw new IllegalArgumentException("Execution policy paths must be normalized");
        }
        return value;
    }
    public String getProgram() { return program; }
    /** Null for an unconditional deny rule. */
    public String getWorkingDirectory() { return workingDirectory; }
}
