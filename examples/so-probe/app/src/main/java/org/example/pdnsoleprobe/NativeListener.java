package org.example.pdnsoleprobe;

interface NativeListener {
    void onStdout(byte[] data);
    void onStderr(byte[] data);
    void onEvent(NativeEvent event);
    void onComplete(NativeResult result);
}
