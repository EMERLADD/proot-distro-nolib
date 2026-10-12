package id.or.oo.pr.engine;

public final class PdnRecoveryEntry {
    private final String directory, id, target, phase, bootId, status, reason;
    private final Long pid, startTime, createdAt;
    PdnRecoveryEntry(String directory, String id, String target, String phase, Long pid,
            Long startTime, String bootId, Long createdAt, String status, String reason) {
        this.directory = directory; this.id = id; this.target = target; this.phase = phase;
        this.pid = pid; this.startTime = startTime; this.bootId = bootId; this.createdAt = createdAt;
        this.status = status; this.reason = reason;
    }
    public String getDirectory() { return directory; }
    public String getId() { return id; }
    public String getTarget() { return target; }
    public String getPhase() { return phase; }
    public Long getPid() { return pid; }
    public Long getStartTime() { return startTime; }
    public String getBootId() { return bootId; }
    public Long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public boolean isInterrupted() { return "interrupted".equals(status); }
}
