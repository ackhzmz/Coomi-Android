package com.maker.app;

import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalEmulator;

/**
 * Terminal activity with Node.js available in PATH.
 * Uses the terminal-view library for terminal emulation.
 */
public class TerminalActivity extends AppCompatActivity {

    private TerminalView terminalView;
    private TerminalSession session;
    private NodeJsRuntime nodeRuntime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        nodeRuntime = new NodeJsRuntime(this);
        terminalView = findViewById(R.id.terminal_view);

        // Set up terminal client
        terminalView.setTerminalViewClient(new TerminalViewClient() {
            @Override public float onScale(float scale) { return scale; }
            @Override public void onSingleTapUp(MotionEvent e) {}
            @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
            @Override public boolean shouldEnforceCharBasedInput() { return false; }
            @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
            @Override public boolean isTerminalViewSelected() { return true; }
            @Override public void copyModeChanged(boolean copyMode) {}
            @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e, TerminalSession s) { return false; }
            @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { return false; }
            @Override public boolean onLongPress(MotionEvent event) { return false; }
            @Override public boolean readControlKey() { return false; }
            @Override public boolean readAltKey() { return false; }
            @Override public boolean readShiftKey() { return false; }
            @Override public boolean readFnKey() { return false; }
            @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) { return false; }
            @Override public void onEmulatorSet() {}
            @Override public void logError(String tag, String message) {}
            @Override public void logWarn(String tag, String message) {}
            @Override public void logInfo(String tag, String message) {}
            @Override public void logDebug(String tag, String message) {}
            @Override public void logVerbose(String tag, String message) {}
            @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
            @Override public void logStackTrace(String tag, Exception e) {}
        });

        // Start a shell session with Node.js in PATH
        startShellSession();
    }

    private void startShellSession() {
        try {
            String[] env;
            String shell;
            String workingDir;

            if (nodeRuntime.isInstalled()) {
                // Node.js is available — use its environment
                env = nodeRuntime.getNodeEnv();
                shell = "/system/bin/sh";
                workingDir = getFilesDir().getAbsolutePath();
            } else {
                // Fallback: basic environment
                env = new String[]{
                    "PATH=/system/bin:/system/xbin",
                    "HOME=" + getFilesDir().getAbsolutePath(),
                    "TERM=xterm-256color",
                };
                shell = "/system/bin/sh";
                workingDir = getFilesDir().getAbsolutePath();
            }

            // Create the terminal session
            session = new TerminalSession(shell, workingDir, null, env, TerminalEmulator.HANDLER_KEY_EVENTS_MODE_ON);

            // Set up the session
            terminalView.attachSession(session);

            // Show welcome message
            session.write("Welcome to Maker App!\r\n");
            if (nodeRuntime.isInstalled()) {
                session.write("Node.js " + nodeRuntime.getVersion() + " ready.\r\n");
                session.write("Type 'node --version' to verify.\r\n");
                session.write("Type 'npx @taptap/maker init' to start a new project.\r\n");
            } else {
                session.write("Node.js not installed. Go back and install first.\r\n");
            }
            session.write("\r\n");

        } catch (Exception e) {
            Toast.makeText(this, "Failed to start terminal: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (session != null) {
            session.finishIfRunning();
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.finishIfRunning();
        }
        super.onDestroy();
    }
}