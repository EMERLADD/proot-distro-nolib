package id.or.oo.pr.engine;

import org.json.JSONObject;

public final class PdnEvent {
    private final JSONObject data;
    PdnEvent(JSONObject data) { this.data = data; }
    public int getVersion() { return data.optInt("version"); }
    public String getOperationId() { return data.optString("operation_id"); }
    public long getSequence() { return data.optLong("sequence"); }
    public String getOperation() { return data.optString("operation"); }
    public String getType() { return data.optString("type"); }
    public String getStage() { return string("stage"); }
    public Long getCurrent() { return number("current"); }
    public Long getTotal() { return number("total"); }
    public Integer getPercent() { return integer("percent"); }
    public String getCode() { return string("code"); }
    public String getMessage() { return string("message"); }
    public String getSuggestion() { return string("suggestion"); }
    public String getOutcome() { return string("outcome"); }
    public Integer getExitCode() { return integer("exit_code"); }
    public Integer getGuestExitCode() { return integer("guest_exit_code"); }
    public Integer getSignal() { return integer("signal"); }
    public Integer getGuestSignal() { return integer("guest_signal"); }
    private String string(String key) { return data.has(key) ? data.optString(key) : null; }
    private Long number(String key) { return data.has(key) ? data.optLong(key) : null; }
    private Integer integer(String key) { return data.has(key) ? data.optInt(key) : null; }
}
