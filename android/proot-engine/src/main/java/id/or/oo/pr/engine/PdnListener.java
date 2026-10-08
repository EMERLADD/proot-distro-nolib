package id.or.oo.pr.engine;

public interface PdnListener {
    default void onEvent(PdnEvent event) {}
    default void onStdout(byte[] data) {}
    default void onStderr(byte[] data) {}
    default void onComplete(PdnResult result) {}
}
