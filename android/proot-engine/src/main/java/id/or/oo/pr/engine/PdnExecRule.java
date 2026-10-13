package id.or.oo.pr.engine;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** An execution rule using a guest path or command name. Directory matching is exact. */
public final class PdnExecRule {
    private final String program;
    private final boolean commandRule;
    private final String workingDirectory;
    private PdnExecRule(String program, String workingDirectory, boolean commandRule) {
        this.commandRule = commandRule;
        this.program = commandRule ? commandName(program) : path(program);
        this.workingDirectory = workingDirectory == null ? null : path(workingDirectory);
    }
    public static PdnExecRule deny(String program) { return new PdnExecRule(program, null, false); }
    public static PdnExecRule onlyFrom(String program, String workingDirectory) {
        return new PdnExecRule(program, Objects.requireNonNull(workingDirectory), false);
    }
    /** Refuse this command name, including recognized BusyBox/toybox applet dispatch. */
    public static PdnExecRule denyCommand(String name) { return new PdnExecRule(name, null, true); }
    public static PdnExecRule commandOnlyFrom(String name, String workingDirectory) {
        return new PdnExecRule(name, Objects.requireNonNull(workingDirectory), true);
    }
    static String commandName(String name) {
        Objects.requireNonNull(name);
        if (name.length() > 128 || !name.matches("[A-Za-z0-9_.+@][A-Za-z0-9_.+@-]*"))
            throw new IllegalArgumentException("A simple command name of at most 128 ASCII characters is required");
        return name;
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
    public boolean isCommandRule() { return commandRule; }
    public String getCommandName() { return commandRule ? program : null; }
    /** The guest program path, or the command name when isCommandRule() is true. */
    public String getProgram() { return program; }
    /** Null for an unconditional deny rule. */
    public String getWorkingDirectory() { return workingDirectory; }
}
