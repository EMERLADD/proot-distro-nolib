package id.or.oo.pr.engine;

import java.io.IOException;

public final class PdnQueryException extends IOException {
    private final PdnResult result;
    public PdnQueryException(PdnResult result) {
        super(result.getMessage() == null ? "PDN metadata query failed: " + result.getOutcome() : result.getMessage());
        this.result = result;
    }
    public PdnResult getResult() { return result; }
}
