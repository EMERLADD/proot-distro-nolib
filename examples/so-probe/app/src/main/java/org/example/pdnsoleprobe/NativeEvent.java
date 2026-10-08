package org.example.pdnsoleprobe;

import org.json.JSONObject;

public final class NativeEvent {
    final JSONObject data;
    NativeEvent(JSONObject data) { this.data = data; }
    public String getType() { return data.optString("type"); }
    public String getStage() { return data.optString("stage", null); }
    public String getOperationId() { return data.optString("operation_id"); }
    public long getSequence() { return data.optLong("sequence"); }
    public Integer getPercent() { return number("percent"); }
    Integer number(String key) { return data.has(key) ? data.optInt(key) : null; }
}
