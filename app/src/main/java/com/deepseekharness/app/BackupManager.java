package com.deepseekharness.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 备份到外部存储（Download/DSHA/）：
 * 支持按需选择备份内容：
 *   - 配置与对话（.dsh）
 *   - OpenCode 凭据（.local/share/opencode）
 *   - 环境与工具（rootfs 的 usr/etc/opt/bin 等，避免重新下载）
 *   - 工作区源码（rootfs /root/&lt;workdir&gt;，含 node_modules）
 *   - 日志（dsh-web.log）
 * Android 10+ 走 MediaStore（无需权限）；Android 9- 直接写公共目录。
 */
public final class BackupManager {

    /** 备份项：配置 / OpenCode凭据 / 环境工具 / 工作区 / 日志 */
    public static final int OPT_CONFIG = 1;
    public static final int OPT_OPENCODE = 2;
    public static final int OPT_ENV = 4;
    public static final int OPT_WORKDIR = 8;
    public static final int OPT_LOGS = 16;

    private BackupManager() {
    }

    /**
     * 执行备份并导出，返回外部存储中的完整路径；失败返回 null。
     * @param opts OPT_* 位组合；0 表示使用默认（配置+凭据+工作区+日志）
     */
    public static String backupToExternal(Context ctx, HarnessController c, int opts) {
        try {
            int o = opts == 0
                    ? OPT_CONFIG | OPT_OPENCODE | OPT_WORKDIR | OPT_LOGS
                    : opts;
            // 1. rootfs 内打包（按选择拼 tar 参数）
            // 注意：必须从根目录(/)打包，usr/etc 等在 / 下、配置等在 /root 下。
            String wd = c.getWorkdir();
            StringBuilder items = new StringBuilder();
            if ((o & OPT_CONFIG) != 0) items.append(" root/.dsh");
            if ((o & OPT_OPENCODE) != 0) items.append(" root/.local/share/opencode");
            if ((o & OPT_ENV) != 0) items.append(" usr etc opt sbin bin lib lib64 var");
            if ((o & OPT_WORKDIR) != 0) items.append(" root/").append(wd).append(" root/dsh-bin");
            if ((o & OPT_LOGS) != 0) items.append(" root/dsh-web.log");
            if (items.length() == 0) return null;

            // 排除运行时虚拟目录/临时文件与备份文件自身
            String excludes = " --exclude=proc --exclude=sys --exclude=dev "
                    + "--exclude=tmp --exclude=run --exclude=root/.dsha-backup.tar.gz ";
            String cmd = "rm -f /root/.dsha-backup.tar.gz && cd / && "
                    + "tar -czf /root/.dsha-backup.tar.gz" + excludes + items + " 2>/dev/null; "
                    + "test -s /root/.dsha-backup.tar.gz && echo OK || echo EMPTY";
            String r = c.getProot().execAndRead(cmd);
            if (r == null || !r.trim().endsWith("OK")) return null;
            File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-backup.tar.gz");
            if (!tmp.isFile() || tmp.length() == 0) return null;

            String name = "DSHA-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date()) + ".tar.gz";
            String path = Build.VERSION.SDK_INT >= 29
                    ? writeViaMediaStore(ctx, tmp, name)
                    : writeDirect(tmp, name);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    /** Android 10+：MediaStore Downloads 集合，无需存储权限 */
    private static String writeViaMediaStore(Context ctx, File src, String name) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSHA");
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return Environment.getExternalStorageDirectory() + "/Download/DSHA/" + name;
    }

    /** Android 9-：直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE 权限） */
    @SuppressWarnings("deprecation")
    private static String writeDirect(File src, String name) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "DSHA");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File dst = new File(dir, name);
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return dst.getAbsolutePath();
    }
}
