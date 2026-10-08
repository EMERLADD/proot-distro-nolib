package id.or.oo.pr.engine;

public final class PdnResult {
    private final String operationId;
    private final String outcome;
    private final int exitCode;
    private final Integer guestExitCode;
    private final Integer guestSignal;
    private final Integer signal;
    private final String code;
    private final String message;
    private final String suggestion;
    PdnResult(String operationId, int exitCode, PdnEvent event) {
        this.operationId = operationId;
        this.exitCode = exitCode;
        this.outcome = event == null ? "host_protocol_error" : event.getOutcome();
        this.code = event == null ? "event_protocol" : event.getCode();
        this.message = event == null ? "Native operation events are missing or invalid" : event.getMessage();
        this.suggestion = event == null ? "Check that the native engine supports event protocol version 1" : event.getSuggestion();
        this.guestExitCode = event == null ? null : event.getGuestExitCode();
        this.signal = event == null ? null : event.getSignal();
        this.guestSignal = event == null ? null : event.getGuestSignal();
    }
    public String getOperationId() { return operationId; }
    public String getOutcome() { return outcome; }
    public int getExitCode() { return exitCode; }
    public Integer getGuestExitCode() { return guestExitCode; }
    public Integer getSignal() { return signal; }
    public Integer getGuestSignal() { return guestSignal; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getSuggestion() { return suggestion; }
    public boolean isSuccess() { return "success".equals(outcome) && exitCode == 0; }
}
