package com.maker.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher activity: shows Node.js status and provides entry points.
 */
public class MainActivity extends AppCompatActivity {

    private NodeJsRuntime nodeRuntime;
    private TextView statusText;
    private Button btnTerminal;
    private Button btnSetup;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nodeRuntime = new NodeJsRuntime(this);

        statusText = findViewById(R.id.status_text);
        btnTerminal = findViewById(R.id.btn_terminal);
        btnSetup = findViewById(R.id.btn_setup);

        btnTerminal.setOnClickListener(v -> {
            if (!nodeRuntime.isInstalled()) {
                Toast.makeText(this, "请先点击「安装 Node.js」", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, TerminalActivity.class));
        });

        btnSetup.setOnClickListener(v -> setupNodeJs());

        // Check status on start
        checkStatus();
    }

    private void checkStatus() {
        statusText.setText("检查 Node.js 状态...");
        executor.execute(() -> {
            if (nodeRuntime.isInstalled()) {
                String version = nodeRuntime.getVersion();
                String npmVersion = nodeRuntime.runCommand("--version").trim();
                mainHandler.post(() -> {
                    statusText.setText("Node.js " + version + "\nnpm " + npmVersion);
                    btnTerminal.setEnabled(true);
                    btnSetup.setText("重新安装");
                });
            } else {
                mainHandler.post(() -> {
                    statusText.setText("Node.js 未安装");
                    btnTerminal.setEnabled(false);
                    btnSetup.setText("安装 Node.js");
                });
            }
        });
    }

    private void setupNodeJs() {
        btnSetup.setEnabled(false);
        statusText.setText("正在安装 Node.js...");
        executor.execute(() -> {
            try {
                // Check if nodejs.zip exists in assets
                try {
                    getAssets().open("nodejs.zip").close();
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        statusText.setText("APK 中缺少 nodejs.zip，请重新安装。");
                        btnSetup.setEnabled(true);
                    });
                    return;
                }

                nodeRuntime.extract();
                String version = nodeRuntime.getVersion();
                mainHandler.post(() -> {
                    statusText.setText("Node.js " + version + " 安装完成！");
                    btnTerminal.setEnabled(true);
                    btnSetup.setText("重新安装");
                    btnSetup.setEnabled(true);
                    Toast.makeText(this, "Node.js 安装完成", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    statusText.setText("安装失败：" + e.getMessage());
                    btnSetup.setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}