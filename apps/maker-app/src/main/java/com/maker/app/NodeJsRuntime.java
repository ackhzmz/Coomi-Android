package com.maker.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages Node.js runtime extracted from APK assets.
 * No Termux, no bootstrap — just the official ARM64 Node.js binary.
 */
public class NodeJsRuntime {

    private static final String LOG_TAG = "NodeJsRuntime";
    private static final String NODEJS_ASSET = "nodejs.zip";
    private static final String NODEJS_DIR = "nodejs";

    private final Context context;
    private File nodeDir;
    private File nodeBin;

    public NodeJsRuntime(Context context) {
        this.context = context;
        this.nodeDir = new File(context.getFilesDir(), NODEJS_DIR);
        this.nodeBin = new File(nodeDir, "bin/node");
    }

    /** Returns true if Node.js is already installed. */
    public boolean isInstalled() {
        return nodeBin.isFile() && nodeBin.canExecute();
    }

    /** Extract Node.js from APK assets to app data directory. */
    public boolean extract() throws IOException {
        nodeDir.mkdirs();

        try (InputStream is = context.getAssets().open(NODEJS_ASSET);
             ZipInputStream zis = new ZipInputStream(is)) {
            byte[] buf = new byte[8192];
            ZipEntry ze;
            int count = 0;
            while ((ze = zis.getNextEntry()) != null) {
                File f = new File(nodeDir, ze.getName());
                if (ze.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    // Make files in bin/ executable
                    if (f.getParentFile().getName().equals("bin")) {
                        f.setExecutable(true, false);
                    }
                    count++;
                }
                zis.closeEntry();
            }
            Log.i(LOG_TAG, "Extracted " + count + " files to " + nodeDir);
        }

        // Verify node binary exists and is executable
        if (!nodeBin.isFile()) {
            throw new IOException("node binary not found in extracted assets");
        }
        nodeBin.setExecutable(true, false);
        return true;
    }

    /** Get the PATH entry for Node.js binaries. */
    public String getNodePath() {
        return new File(nodeDir, "bin").getAbsolutePath();
    }

    /** Get the NODE_PATH (node_modules root). */
    public String getNodeModulesPath() {
        return new File(nodeDir, "lib/node_modules").getAbsolutePath();
    }

    /** Get the full environment for running Node.js commands. */
    public String[] getNodeEnv() {
        String nodeBinPath = getNodePath();
        String home = context.getFilesDir().getAbsolutePath();
        String tmpDir = new File(context.getCacheDir(), "tmp").getAbsolutePath();
        new File(tmpDir).mkdirs();

        return new String[]{
            "PATH=" + nodeBinPath + ":/system/bin:/system/xbin",
            "HOME=" + home,
            "TMPDIR=" + tmpDir,
            "NODE_PATH=" + getNodeModulesPath(),
            "NPM_CONFIG_PREFIX=" + home,
            "NPM_CONFIG_CACHE=" + new File(context.getCacheDir(), "npm-cache").getAbsolutePath(),
            "USER=" + System.getProperty("user.name", "shell"),
            "TERM=xterm-256color",
        };
    }

    /** Get the absolute path to the node binary. */
    public String getNodeBinary() {
        return nodeBin.getAbsolutePath();
    }

    /** Get the Node.js version string (e.g. "v18.19.0"). */
    public String getVersion() {
        try {
            Process proc = Runtime.getRuntime().exec(
                new String[]{nodeBin.getAbsolutePath(), "--version"},
                getNodeEnv()
            );
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));
            String version = reader.readLine();
            proc.waitFor();
            return version != null ? version.trim() : "";
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to get Node.js version", e);
            return "";
        }
    }

    /** Run a command and return the output. */
    public String runCommand(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = nodeBin.getAbsolutePath();
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process proc = Runtime.getRuntime().exec(cmd, getNodeEnv());
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
            proc.waitFor();
            return output.toString();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Command failed", e);
            return "Error: " + e.getMessage();
        }
    }
}