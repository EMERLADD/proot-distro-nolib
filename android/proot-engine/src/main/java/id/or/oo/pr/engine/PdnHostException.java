package id.or.oo.pr.engine;

import java.io.IOException;
import java.util.Objects;

public final class PdnHostException extends IOException {
    private final String code;
    private final String suggestion;

    public PdnHostException(String code, String message, String suggestion, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
        this.suggestion = Objects.requireNonNull(suggestion);
    }

    public String getCode() { return code; }
    public String getSuggestion() { return suggestion; }
}
