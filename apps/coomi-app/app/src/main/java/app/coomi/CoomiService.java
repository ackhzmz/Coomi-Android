package app.coomi;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns deployment and lifecycle of the native coomi-rs process. */
public class CoomiService extends Service {

    private static final String LOG_TAG = "CoomiService";
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2000;
    private static final int CMD_TIMEOUT_SEC = 30;

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private volatile Process mEngineProcess;
    private volatile int mEnginePort = CoomiConstants.DEFAULT_ENGINE_PORT;
    /** 每次引擎启动生成的随机访问令牌（WebView 经 URL query 注入，防同设备 app 直连）。 */
    private volatile String mEngineToken = "";
    private volatile boolean mIsEngineRunning;
    /** 引擎启动流程进行中（含部署检查/进程拉起/健康探测），供控制台显示「引擎启动中」。 */
    private volatile boolean mIsEngineStarting;
    private volatile boolean mUpdateInProgress;

    private static String prefix() { return TermuxConstants.TERMUX_PREFIX_DIR_PATH; }
    private static String home() { return TermuxConstants.TERMUX_HOME_DIR_PATH; }
    private static String preload() { return prefix() + "/lib/libtermux-exec-ld-preload.so"; }

    /** Termux 环境变量前缀，不含 shell 命令。适用于 execTermux 和 getVersion 等静态场景。 */
    private static String termuxEnvironment() {
        // 注意：PATH 中 /system/bin 放在 $PREFIX/bin 前面，确保系统命令（mkdir、id 等）
        // 优先于 Termux coreutils（coreutils 可能因 DT_HASH 兼容性问题而无法链接）。
        return "export HOME=" + shellQuote(home())
            + " PREFIX=" + shellQuote(prefix())
            + " TMPDIR=" + shellQuote(prefix() + "/tmp")
            + " PATH=/system/bin:" + shellQuote(prefix() + "/bin:/system/bin")
            + " LD_LIBRARY_PATH=" + shellQuote(prefix() + "/lib")
            + " LD_PRELOAD=" + shellQuote(preload())
            + " COOMI_HOME=" + shellQuote(CoomiConstants.COOMI_CONFIG_DIR)
            + " COOMI_SHELL=" + shellQuote(prefix() + "/bin/bash")
            + " SSL_CERT_FILE=" + shellQuote(prefix() + "/etc/tls/cert.pem")
            + "; ";
    }

    private CommandResult execTermux(String command) {
        return execTermux(command, CMD_TIMEOUT_SEC);
    }

    /** 过滤掉 Termux linker 警告等干扰行，保留真正的输出内容。 */
    private static String stripLinkerWarnings(String output) {
        if (output == null || output.isEmpty()) return output;
        StringBuilder filtered = new StringBuilder(output.length());
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("WARNING: linker:")) continue;
            if (filtered.length() > 0) filtered.append('\n');
            filtered.append(line);
        }
        return filtered.toString();
    }

    /**
     * 在不依赖 Termux bash 的前提下执行一条简单命令，仅用于检测/修复 bash 本身。
     * 使用系统 /system/bin/sh，不设置 Termux 环境变量。
     */
    private CommandResult execRaw(String command, int timeoutSec) {
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            boolean exited = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            int code = exited ? process.exitValue() : -1;
            return new CommandResult(code == 0, output.toString().trim(), "", code);
        } catch (Exception e) {
            return new CommandResult(false, "", e.getMessage(), -1);
        }
    }

    /**
     * 检测 Termux bash 二进制是否可用（DT_HASH 兼容性）。
     * 注意：不创建包装脚本，因为 pkg 等脚本使用 bash 特有的 =~ 运算符，
     * 而 /system/bin/sh（mksh）不支持它。
     */
    private void ensureBashWorks() {
        String bashPath = prefix() + "/bin/bash";
        if (!new File(bashPath).isFile()) return;
        CommandResult check = execRaw(bashPath + " -c 'echo ok'", 10);
        if (check.success) return;
        if (check.stdout.contains("CANNOT LINK") || check.stderr.contains("CANNOT LINK")) {
            Logger.logInfo(LOG_TAG, "bash 存在 DT_HASH 兼容性问题，将使用系统命令替代");
        }
    }

    private CommandResult execTermux(String command, int timeoutSec) {
        try {
            String shell = termuxEnvironment()
                + "exec /system/bin/sh -c " + shellQuote(command);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", shell);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            boolean exited = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            int code = exited ? process.exitValue() : -1;
            String cleaned = stripLinkerWarnings(output.toString().trim());
            return new CommandResult(code == 0, cleaned, "", code);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Termux command failed: " + e.getMessage());
            return new CommandResult(false, "", e.getMessage(), -1);
        }
    }

    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
        }
    }

    public interface ProgressCallback {
        void onStep(String message);
        void onError(String error);
        void onComplete();
    }

    public class LocalBinder extends Binder {
        public CoomiService getService() { return CoomiService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return mBinder; }
    @Override public void onCreate() { Logger.logInfo(LOG_TAG, "Native service created"); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        stopEngineSync();
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        super.onDestroy();
    }

    public static boolean isBootstrapInstalled() {
        return new File(prefix() + "/bin/bash").isFile();
    }

    public static boolean isDeployComplete() {
        return new File(CoomiConstants.INSTALL_MARKER_PATH).isFile()
            && new File(prefix() + "/bin/coomi").isFile();
    }

    private File nativeBinary() {
        return new File(getApplicationInfo().nativeLibraryDir, CoomiConstants.NATIVE_BINARY_NAME);
    }

    /** 查询引擎版本。失败时返回空字符串，避免向 UI 暴露原始错误信息。 */
    public String getRuntimeVersion() {
        CommandResult result = execTermux("coomi --version");
        if (result.success) {
            return result.stdout;
        }
        Logger.logError(LOG_TAG, "Failed to get runtime version: " + result.stderr);
        return "";
    }

    /** Install Node.js, npm, and npx by extracting from APK assets. */
    public CommandResult installNodeJs() {
        ensureBashWorks();

        String destDir = prefix();
        File nodeBin = new File(destDir + "/bin/node");

        // 1. Check if node already exists
        if (nodeBin.isFile()) {
            CommandResult nodeVer = execTermux("node --version");
            if (nodeVer.success && !nodeVer.stdout.trim().isEmpty()) {
                Logger.logInfo(LOG_TAG, "Node.js already installed: " + nodeVer.stdout.trim());
                return nodeVer;
            }
        }

        // 2. Extract nodejs.zip from APK assets to $PREFIX/
        Logger.logInfo(LOG_TAG, "Extracting Node.js from APK assets to " + destDir);
        if (!CoomiBootstrap.assetExists(this, CoomiConstants.NODEJS_ASSET)) {
            return new CommandResult(false, "",
                "APK 中缺少 nodejs.zip，请重新安装。", -1);
        }
        int count = CoomiBootstrap.deployZipAsset(this, CoomiConstants.NODEJS_ASSET, new File(destDir));
        if (count <= 0 || !nodeBin.isFile()) {
            return new CommandResult(false, "",
                "APK 中 nodejs.zip 解压失败。", -1);
        }

        // 3. Make node/npm/npx executable
        execRaw("chmod +x " + shellQuote(destDir + "/bin/node")
            + " " + shellQuote(destDir + "/bin/npm")
            + " " + shellQuote(destDir + "/bin/npx") + " 2>/dev/null", 10);

        // 4. Create npm/npx wrapper scripts if the symlinks are broken
        // (Node.js ships npm/npx as symlinks to lib/node_modules/npm/bin/...)
        String npmScript = destDir + "/lib/node_modules/npm/bin/npm-cli.js";
        String npxScript = destDir + "/lib/node_modules/npm/bin/npx-cli.js";
        File npmBin = new File(destDir + "/bin/npm");
        if (!npmBin.isFile() || !npmBin.canExecute()) {
            createNodeWrapper("npm", npmScript, nodeBin);
        }
        File npxBin = new File(destDir + "/bin/npx");
        if (!npxBin.isFile() || !npxBin.canExecute()) {
            createNodeWrapper("npx", npxScript, nodeBin);
        }

        // 5. Verify installation
        CommandResult nodeVer = execTermux("node --version");
        if (!nodeVer.success || nodeVer.stdout.trim().isEmpty()) {
            return new CommandResult(false, nodeVer.stdout, "node --version 返回空", -1);
        }
        CommandResult npmVer = execTermux("npm --version");
        if (!npmVer.success) {
            return new CommandResult(false, npmVer.stdout, "npm --version 失败", -1);
        }
        CommandResult npxVer = execTermux("npx --version");
        if (!npxVer.success) {
            return new CommandResult(false, npxVer.stdout, "npx --version 失败", -1);
        }
        Logger.logInfo(LOG_TAG, "Node.js 安装完成: node=" + nodeVer.stdout.trim()
            + " npm=" + npmVer.stdout.trim() + " npx=" + npxVer.stdout.trim());
        return nodeVer;
    }

    /** Create a node-based wrapper script for npm/npx if the symlink is broken. */
    private void createNodeWrapper(String name, String scriptPath, File nodeBin) {
        String wrapper = prefix() + "/bin/" + name;
        String content = "#!/system/bin/sh\nexec " + nodeBin.getAbsolutePath()
            + " " + scriptPath + " \"$@\"\n";
        try {
            execRaw("cat > " + shellQuote(wrapper) + " << 'COOMI_EOF'\n"
                + content + "COOMI_EOF\n" + "chmod +x " + shellQuote(wrapper), 10);
        } catch (Exception ignored) {}
    }

    /** Check Node.js version. Returns "x.y.z" or empty string if not installed. */
    public static String getNodeJsVersion() {
        String version = getVersion("node --version");
        return version.isEmpty() ? "" : version.replaceAll("^v", "");
    }

    /** Check npm version. Returns "x.y.z" or empty string if not installed. */
    public static String getNpmVersion() {
        return getVersion("npm --version");
    }

    /** 在 Termux 环境中执行一条版本查询命令，返回 stdout 或空字符串。 */
    private static String getVersion(String command) {
        try {
            String shell = termuxEnvironment() + command;
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", shell);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
            }
            boolean exited = process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            if (exited && process.exitValue() == 0) {
                return stripLinkerWarnings(output.toString().trim());
            }
        } catch (Exception ignored) {}
        return "";
    }

    public void deployCoomi(ProgressCallback callback) {
        mExecutor.execute(() -> {
            if (mUpdateInProgress) {
                callback.onError("部署已在运行中，请等待完成");
                return;
            }
            mUpdateInProgress = true;
            try {
                // 先检测 bash 二进制是否可用（DT_HASH 兼容性），不可用时创建包装脚本
                ensureBashWorks();

                File binary = nativeBinary();
                File web = ensureCurrentWebAssets();
                if (!binary.isFile()) {
                    callback.onError("APK 中缺少 ARM64 coomi-rs 二进制：" + binary.getAbsolutePath());
                    return;
                }
                if (!new File(web, "index.html").isFile()) {
                    callback.onError("APK 中缺少已构建的前端 web.zip");
                    return;
                }

                callback.onStep("准备 Rust 运行目录");
                CommandResult directories = execTermux(
                    "mkdir -p " + shellQuote(home() + "/.coomi/config")
                        + " " + shellQuote(home() + "/.coomi/sessions")
                        + " " + shellQuote(home() + "/coomi"));
                if (!directories.success) {
                    callback.onError("无法创建运行目录：" + directories.stdout);
                    return;
                }

                callback.onStep("部署 coomi-rs ARM64 二进制");
                CommandResult link = execTermux(
                    "ln -sf " + shellQuote(binary.getAbsolutePath())
                        + " " + shellQuote(prefix() + "/bin/coomi"));
                if (!link.success) {
                    callback.onError("无法部署 coomi-rs：" + link.stdout);
                    return;
                }

                callback.onStep("校验原生引擎");
                CommandResult version = execTermux("coomi --version");
                if (!version.success || !version.stdout.contains("coomi")) {
                    callback.onError("coomi-rs 无法启动：\n" + version.stdout + "\n" + version.stderr);
                    return;
                }
                callback.onStep(version.stdout);

                callback.onStep("安装 Node.js 运行时");
                CommandResult nodeResult = installNodeJs();
                if (!nodeResult.success) {
                    callback.onError("Node.js 安装失败：" + nodeResult.stdout + "\n" + nodeResult.stderr);
                    return;
                }
                callback.onStep("Node.js " + nodeResult.stdout.trim());

                writeShellEnvironment();
                removeLegacyRuntimePayloads();
                try (FileWriter writer = new FileWriter(CoomiConstants.INSTALL_MARKER_PATH)) {
                    writer.write(version.stdout + "\n" + binary.getAbsolutePath() + "\n");
                }
                callback.onComplete();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Native deployment failed: " + e.getMessage());
                callback.onError(e.getMessage());
            } finally {
                mUpdateInProgress = false;
            }
        });
    }

    private void writeShellEnvironment() throws Exception {
        File profile = new File(home(), ".profile");
        try (FileWriter writer = new FileWriter(profile)) {
            writer.write("# Created by Coomi Android\n"
                + "export PREFIX=\"" + prefix() + "\"\n"
                + "export HOME=\"" + home() + "\"\n"
                + "export COOMI_HOME=\"$HOME/.coomi\"\n"
                + "export COOMI_SHELL=\"$PREFIX/bin/bash\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "export PATH=\"$PREFIX/bin:$PATH\"\n"
                + "[ -f ~/.bashrc ] && . ~/.bashrc\n");
        }
        File bashrc = new File(home(), ".bashrc");
        try (FileWriter writer = new FileWriter(bashrc)) {
            writer.write("# Created by Coomi Android\n"
                + "export COOMI_HOME=\"$HOME/.coomi\"\n"
                + "export COOMI_SHELL=\"$PREFIX/bin/bash\"\n"
                + "export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n"
                + "alias ll='ls -la'\n");
        }
    }

    private void removeLegacyRuntimePayloads() {
        CoomiBootstrap.deleteRecursive(new File(getFilesDir(), "pysrc"));
        CoomiBootstrap.deleteRecursive(new File(getFilesDir(), "wheels"));
        new File(home() + "/.coomi/config.json").delete();
        new File(home() + "/.coomi/credentials.json").delete();
        new File(prefix() + "/share/coomi/install.sh").delete();
    }

    public void startEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> callback.accept(startEngineSync()));
    }

    private CommandResult startEngineSync() {
        mIsEngineStarting = true;
        try {
            if (mEngineProcess != null && mEngineProcess.isAlive()) {
                if (checkHealth(mEnginePort)) {
                    mIsEngineStarting = false;
                    return new CommandResult(true, "already running", "", 0);
                }
                // 进程活着但健康检查失败（假死/端口错乱）：先清理旧进程再重启，
                // 避免双引擎并发写同一会话目录。
                Logger.logInfo(LOG_TAG, "Engine process alive but unhealthy, killing before restart");
                stopEngineSync();
            }
            if (!isDeployComplete()) {
                mIsEngineStarting = false;
                return new CommandResult(false, "", "coomi-rs is not deployed", -1);
            }
            File binary = nativeBinary();
            File web = ensureCurrentWebAssets();
            if (!binary.isFile() || !new File(web, "index.html").isFile()) {
                mIsEngineStarting = false;
                return new CommandResult(false, "", "native binary or frontend is missing", -1);
            }

            int port = findFreePort();
            String token = generateToken();
            mEngineToken = token;
            String command = termuxEnvironment()
                + "export RUST_BACKTRACE=1; cd " + shellQuote(home()) + "; "
                + "exec >>" + shellQuote(CoomiConstants.ENGINE_LOG_PATH) + " 2>&1; "
                + "exec " + shellQuote(binary.getAbsolutePath())
                + " --home " + shellQuote(CoomiConstants.COOMI_CONFIG_DIR)
                + " --cwd " + shellQuote(home())
                + " serve --port " + port
                + " --token " + shellQuote(token)
                + " --static-dir " + shellQuote(web.getAbsolutePath());
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
            builder.redirectErrorStream(true);
            mEngineProcess = builder.start();
            mEnginePort = port;
            mIsEngineRunning = true;

            Process process = mEngineProcess;
            new Thread(() -> {
                try {
                    int code = process.waitFor();
                    Logger.logInfo(LOG_TAG, "coomi-rs exited with code " + code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (mEngineProcess == process) {
                        mEngineProcess = null;
                        mIsEngineRunning = false;
                    }
                }
            }, "coomi-rs-waiter").start();

            return new CommandResult(true, "Engine started on port " + port, "", 0);
        } catch (Exception e) {
            mEngineProcess = null;
            mIsEngineRunning = false;
            return new CommandResult(false, "", e.getMessage(), -1);
        } finally {
            mIsEngineStarting = false;
        }
    }

    private synchronized File ensureCurrentWebAssets() throws Exception {
        File web = new File(getFilesDir(), CoomiConstants.WEB_DIR_BASENAME);
        File stampFile = new File(web, ".app-stamp");
        String expected = CoomiBootstrap.appStamp(this);
        String actual = "";
        if (stampFile.isFile()) {
            actual = new String(java.nio.file.Files.readAllBytes(stampFile.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        if (!expected.equals(actual) || !new File(web, "index.html").isFile()) {
            CoomiBootstrap.deleteRecursive(web);
            int count = CoomiBootstrap.deployZipAsset(this, CoomiConstants.WEB_ASSET, web);
            if (count < 1 || !new File(web, "index.html").isFile()) {
                throw new IllegalStateException("无法部署 APK 内置前端");
            }
            try (FileWriter writer = new FileWriter(stampFile)) {
                writer.write(expected);
            }
        }
        return web;
    }

    public void stopEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            stopEngineSync();
            if (callback != null) callback.accept(new CommandResult(true, "stopped", "", 0));
        });
    }

    private void stopEngineSync() {
        Process process = mEngineProcess;
        if (process != null) {
            process.destroy();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) process.destroyForcibly();
        }
        // 兜底：清掉可能残留的 coomi 进程（Rust 侧收到 SIGTERM 会先清理全部工具子进程）。
        // ^/[^ ]*libcoomi\.so 锚定引擎二进制路径开头，避免误匹配执行本命令的 shell 自身。
        try {
            execTermux("pkill -f '^/[^ ]*" + CoomiConstants.NATIVE_BINARY_NAME + "' 2>/dev/null; true");
        } catch (Exception ignored) { /* best-effort */ }
        mEngineProcess = null;
        mIsEngineRunning = false;
        mIsEngineStarting = false;
    }

    public void restartEngine(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            stopEngineSync();
            callback.accept(startEngineSync());
        });
    }

    public void getEngineStatus(Consumer<CommandResult> callback) {
        mExecutor.execute(() -> {
            // 启动流程进行中（无论进程是否已拉起）都报 starting，控制台显示「引擎启动中」。
            if (mIsEngineStarting) {
                callback.accept(new CommandResult(true, "starting", "", 0));
                return;
            }
            boolean alive = mIsEngineRunning && mEngineProcess != null && mEngineProcess.isAlive();
            String status = alive ? (checkHealth(mEnginePort) ? "running" : "starting") : "stopped";
            callback.accept(new CommandResult(true, status, "", 0));
        });
    }

    public boolean isUpdateInProgress() { return mUpdateInProgress; }
    public int getEnginePort() { return mEnginePort; }

    public static String readEngineLogTail(int count) {
        if (count <= 0) return "";
        // 使用环形缓冲区，避免全量读取后只取尾部 N 行
        String[] ring = new String[count];
        int idx = 0;
        int total = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(CoomiConstants.ENGINE_LOG_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ring[idx % count] = line;
                idx++;
                total++;
            }
        } catch (Exception ignored) {
            return "";
        }
        if (total == 0) return "";
        StringBuilder output = new StringBuilder();
        int start = total > count ? idx % count : 0;
        int limit = Math.min(total, count);
        for (int i = 0; i < limit; i++) {
            output.append(ring[(start + i) % count]).append('\n');
        }
        return output.toString().trim();
    }

    private static int findFreePort() {
        // 随机高位端口（缩小同设备其它 app 枚举命中的概率）。
        java.util.Random random = new java.util.Random();
        for (int attempt = 0; attempt < 50; attempt++) {
            int port = 20000 + random.nextInt(40000);
            try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (Exception ignored) {}
        }
        return CoomiConstants.DEFAULT_ENGINE_PORT;
    }

    /** 生成 512 位（64 字节）十六进制随机令牌（Android 端与 WebView 共享，不落盘不写 JS）。 */
    private static String generateToken() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public String getEngineToken() { return mEngineToken; }

    private boolean checkHealth(int port) {
        try {
            // health 端点已由引擎免认证放行（仅返回最小字段），无需携带令牌。
            HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + CoomiConstants.HEALTH_ENDPOINT).openConnection();
            connection.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            connection.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
