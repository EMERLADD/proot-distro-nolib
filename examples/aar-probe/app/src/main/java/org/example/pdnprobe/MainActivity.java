package org.example.pdnprobe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private ProbeSuite suite;
    private ProbeTerminal terminal;
    private TextView logs;
    private TextView status;
    private EditText input;
    private boolean busy;
    private boolean disposed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        suite = new ProbeSuite(this, this::append);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("独立 AAR 验证 · Java / targetSdk 35");
        title.setTextSize(20);
        root.addView(title);
        status = new TextView(this);
        status.setText("只导入 PDN AAR 与 Kotlin 标准库，数据使用新 App 的私有目录。");
        root.addView(status);
        LinearLayout row = row(root);
        add(row, "初始化", () -> task(() -> {
            suite.getRuntime().prepare();
            append("初始化成功");
            print(suite.run(suite.getRuntime().version()));
        }));
        add(row, "安装 Alpine", () -> task(() -> print(suite.run(suite.getRuntime().install("alpine", "official")))));
        add(row, "全部验收", () -> task(() -> {
            if (!suite.verify().getBoolean("passed")) throw new IllegalStateException("验收失败，请查看报告");
        }));
        row = row(root);
        add(row, "执行命令", () -> {
            String command = input.getText().toString();
            task(() -> print(suite.run(suite.getRuntime().exec("alpine", java.util.Arrays.asList("/bin/sh", "-c", command)))));
        });
        add(row, "打开终端", () -> task(() -> {
            if (terminal != null) throw new IllegalStateException("终端已打开");
            terminal = new ProbeTerminal(suite.getHost(), suite.getRuntime(), suite.getRootfs(), this::append);
            append("交互终端已打开，可连续发送命令。");
        }));
        add(row, "关闭终端", () -> task(() -> { if (terminal != null) { terminal.close(); terminal = null; } }));
        ScrollView scroll = new ScrollView(this);
        logs = new TextView(this);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextSize(14);
        logs.setTextIsSelectable(true);
        scroll.addView(logs);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("命令，例如 id; pwd; uname -r");
        input.setText("id; pwd; uname -r");
        root.addView(input);
        row = row(root);
        add(row, "发送到终端", () -> {
            String command = input.getText().toString();
            task(() -> { requireTerminal().send(command + "\n"); });
        });
        add(row, "Ctrl-C", () -> task(() -> requireTerminal().send("\u0003")));
        add(row, "32×96", () -> task(() -> requireTerminal().resize(32, 96)));
        setContentView(root);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row);
        return row;
    }
    private void add(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        actions.add(button);
    }
    private ProbeTerminal requireTerminal() {
        if (terminal == null) throw new IllegalStateException("先打开终端");
        return terminal;
    }
    private interface Task { void run() throws Exception; }
    private void task(Task action) {
        if (busy) return;
        busy = true;
        actions.forEach(button -> button.setEnabled(false));
        status.setText("执行中，日志会显示事件阶段和结果。");
        worker.submit(() -> {
            String result = "已完成";
            try { action.run(); }
            catch (Exception | AssertionError failure) { result = "失败：" + failure; append(result); }
            String finalResult = result;
            runOnUiThread(() -> {
                if (disposed) return;
                busy = false;
                status.setText(finalResult);
                actions.forEach(button -> button.setEnabled(true));
            });
        });
    }
    private void print(ProbeSuite.Capture capture) {
        append(capture.stdout());
        append(capture.stderr());
        append("结果：" + capture.result.getOutcome() + ", exit=" + capture.result.getExitCode());
        if (capture.result.getSuggestion() != null) append("建议：" + capture.result.getSuggestion());
        if (!capture.result.isSuccess()) throw new IllegalStateException("PDN 操作失败：" + capture.result.getOutcome());
    }
    private void append(String text) {
        runOnUiThread(() -> {
            if (disposed) return;
            output.append(text).append('\n');
            if (output.length() > 65536) output.delete(0, output.length() - 65536);
            logs.setText(output.toString());
        });
    }
    boolean isInstalled() { return suite.getRootfs().isDirectory(); }
    boolean isBusy() { return busy; }
    String statusText() { return status.getText().toString(); }

    @Override public void onDestroy() {
        disposed = true;
        worker.shutdownNow();
        if (terminal != null) {
            try { terminal.close(); } catch (java.io.IOException failure) { android.util.Log.e("PdnProbe", "Terminal cleanup failed", failure); }
        }
        super.onDestroy();
    }
}
