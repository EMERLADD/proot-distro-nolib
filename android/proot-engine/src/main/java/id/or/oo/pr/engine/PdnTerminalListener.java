package id.or.oo.pr.engine;

public interface PdnTerminalListener {
    default void onEvent(PdnEvent event) {}
    default void onOutput(byte[] data) {}
    default void onComplete(PdnResult result) {}
    default void onFailure(Exception failure) {}
}
