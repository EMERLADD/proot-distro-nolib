package id.or.oo.pr.engine;

public final class PdnTerminalException extends java.io.IOException {
    private final int errno;
    private final String code;
    private final String suggestion;
    PdnTerminalException(String code, int errno) {
        super("Terminal " + code + " failed (errno " + errno + ")");
        this.code = code;
        this.errno = errno;
        this.suggestion = errno == 2 ? "Check the executable, interpreter and loader paths"
                : errno == 13 ? "Check execute permissions and use the app native library directory"
                : errno == 8 ? "Check the executable format and architecture"
                : "Check the terminal resources and retry";
    }
    public int getErrno() { return errno; }
    public String getCode() { return code; }
    public String getSuggestion() { return suggestion; }
}
