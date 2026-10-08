package org.example.pdnsoleprobe;

public final class NativeResult {
    private final NativeEvent event;
    private final int status;
    NativeResult(NativeEvent event, int status) { this.event = event; this.status = status; }
    public String getOperationId() { return event.getOperationId(); }
    public String getOutcome() { return event.data.optString("outcome"); }
    public int getExitCode() { return status; }
    public String getCode() { return event.data.optString("code", null); }
    public String getMessage() { return event.data.optString("message", null); }
    public String getSuggestion() { return event.data.optString("suggestion", null); }
    public Integer getGuestExitCode() { return event.number("guest_exit_code"); }
    public Integer getGuestSignal() { return event.number("guest_signal"); }
    public boolean isSuccess() { return status == 0 && "success".equals(getOutcome()); }
}
