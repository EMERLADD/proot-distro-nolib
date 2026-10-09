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
    private boolean nativeCoverage;
    @Override public void onCreate(Bundle arguments) {
        nativeCoverage = arguments != null && "true".equals(arguments.getString("nativeCoverage"));
        super.onCreate(arguments);
        start();
    }
    private void dumpNativeCoverage(Bundle result) {
        if (!nativeCoverage) return;
        try {
            Class<?> type = Class.forName("id.or.oo.pr.engine.PtyNative");
            java.lang.reflect.Method method = type.getDeclaredMethod("nativeDumpCoverage", String.class);
            method.setAccessible(true);
            Object status = method.invoke(type.getField("INSTANCE").get(null), new File(getTargetContext().getFilesDir(), "native-coverage.profraw").getAbsolutePath());
            result.putString("native_coverage_status", String.valueOf(status));
            if (!(status instanceof Number) || ((Number)status).intValue() != 0) throw new IllegalStateException("Native coverage dump status: " + status);
        } catch (Exception failure) { result.putString("native_coverage_error", failure.toString()); }
    }
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
        dumpNativeCoverage(result);
        try {
            Files.deleteIfExists(new File(getTargetContext().getFilesDir(), "acceptance.json").toPath());
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
            try {
                File reportFile = new File(getTargetContext().getFilesDir(), "acceptance.json");
                JSONObject failed = reportFile.isFile() ? new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
                failed.put("passed", false).put("instrumentation_failure", failure.toString());
                Files.write(reportFile.toPath(), failed.toString(2).getBytes(StandardCharsets.UTF_8));
                result.putString("stream", failed.toString(2) + "\n");
            } catch (Exception reportFailure) { result.putString("report_error", reportFailure.toString()); }
        } finally {
            if (activity != null) { MainActivity target = activity; runOnMainSync(target::finish); waitForIdleSync(); }
        }
        try {
            Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
            Object agent = rt.getMethod("getAgent").invoke(null);
            byte[] data = (byte[]) agent.getClass().getMethod("getExecutionData", boolean.class).invoke(agent, false);
            Files.write(new File(getTargetContext().getFilesDir(), "coverage.ec").toPath(), data);
        } catch (Exception failure) { result.putString("coverage_error", failure.toString()); }
        dumpNativeCoverage(result);
        finish(status, result);
    }
}
