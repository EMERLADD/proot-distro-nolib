package org.example.pdnprobe;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import org.json.JSONObject;

public final class ProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void click(MainActivity activity, String label, boolean expectSuccess) throws Exception {
        runOnMainSync(() -> {
            ArrayList<View> matches = new ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT);
            if (matches.size() != 1 || !matches.get(0).performClick()) throw new AssertionError("Missing control: " + label);
        });
        long deadline = SystemClock.elapsedRealtime() + 300000;
        while (true) {
            boolean[] busy = new boolean[1];
            runOnMainSync(() -> busy[0] = activity.isBusy());
            if (!busy[0]) break;
            if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Timed out: " + label);
            SystemClock.sleep(100);
        }
        String[] status = new String[1];
        runOnMainSync(() -> status[0] = activity.statusText());
        if (status[0].startsWith("失败") == expectSuccess) throw new AssertionError(label + ": " + status[0]);
        Bundle progress = new Bundle();
        progress.putString("stream", "UI " + label + ": " + status[0] + "\n");
        sendStatus(0, progress);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = 0;
        MainActivity activity = null;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click(activity, "初始化", true);
            boolean[] installed = new boolean[1];
            MainActivity started = activity;
            runOnMainSync(() -> installed[0] = started.isInstalled());
            click(activity, "安装 Alpine", !installed[0]);
            click(activity, "执行命令", true);
            click(activity, "打开终端", true);
            click(activity, "发送到终端", true);
            click(activity, "32×96", true);
            click(activity, "Ctrl-C", true);
            click(activity, "关闭终端", true);
            click(activity, "发送到终端", false);
            click(activity, "全部验收", true);
            JSONObject report = new JSONObject(new String(Files.readAllBytes(
                    new File(getTargetContext().getFilesDir(), "acceptance.json").toPath()), StandardCharsets.UTF_8));
            report.put("gui_controls_tested", true);
            Files.write(new File(getTargetContext().getFilesDir(), "acceptance.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
            result.putString("stream", report.toString(2) + "\n");
            status = report.getBoolean("passed") ? -1 : 0;
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL " + failure + "\n");
        } finally {
            if (activity != null) { MainActivity target = activity; runOnMainSync(target::finish); waitForIdleSync(); }
        }
        try {
            Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
            Object agent = rt.getMethod("getAgent").invoke(null);
            byte[] data = (byte[]) agent.getClass().getMethod("getExecutionData", boolean.class).invoke(agent, false);
            Files.write(new File(getTargetContext().getFilesDir(), "coverage.ec").toPath(), data);
        } catch (Exception failure) { result.putString("coverage_error", failure.toString()); }
        finish(status, result);
    }
}
