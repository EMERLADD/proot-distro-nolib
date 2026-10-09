package id.or.oo.pr.engine;

public final class PdnTerminalStatus {
    private final Integer exitCode;
    private final Integer signal;
    private PdnTerminalStatus(Integer exitCode, Integer signal) {
        this.exitCode = exitCode;
        this.signal = signal;
    }
    public static PdnTerminalStatus exited(int code) { return new PdnTerminalStatus(code, null); }
    public static PdnTerminalStatus signalled(int signal) { return new PdnTerminalStatus(null, signal); }
    public Integer getExitCode() { return exitCode; }
    public Integer getSignal() { return signal; }
    public boolean isSuccess() { return exitCode != null && exitCode == 0; }
}
