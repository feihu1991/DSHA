package com.deepseekharness.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 工作区管理模块：工作目录配置、环境信息、无 ROOT 文件共享（MT 注入文件提供器） */
public class WorkspaceFragment extends Fragment {

    private HarnessController c;
    private final ActivityResultLauncher<String[]> pickBackup =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) restoreBackup(uri);
                    });
    private EditText workdirEdit;
    private TextView infoText, shareStatusText, shizukuStatusText;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_workspace, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        prefs = requireContext().getSharedPreferences("deepseekharness", 0);
        workdirEdit = view.findViewById(R.id.workspace_path);
        infoText = view.findViewById(R.id.workspace_info);
        shareStatusText = view.findViewById(R.id.workspace_share_status);
        shizukuStatusText = view.findViewById(R.id.workspace_shizuku_status);
        Button applyBtn = view.findViewById(R.id.workspace_apply);
        Button shizukuAuthBtn = view.findViewById(R.id.workspace_shizuku_auth);
        Button clearBtn = view.findViewById(R.id.workspace_clear);
        Button backupBtn = view.findViewById(R.id.workspace_backup);
        Button restoreBtn = view.findViewById(R.id.workspace_restore);
        Button resetBtn = view.findViewById(R.id.workspace_reset);

        workdirEdit.setText(c.getWorkdir());
        refreshInfo();

        applyBtn.setOnClickListener(v -> {
            String wd = workdirEdit.getText().toString().trim();
            if (!wd.isEmpty()) {
                c.setWorkdir(wd);
                refreshInfo();
                Toast.makeText(requireContext(), "工作区已更新", Toast.LENGTH_SHORT).show();
            }
        });

        shizukuAuthBtn.setOnClickListener(v -> {
            if (!ShizukuShell.isAvailable()) {
                Toast.makeText(requireContext(), "请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
                return;
            }
            ShizukuShell.requestPermission((code, grantResult) -> refreshShizukuStatus());
            refreshShizukuStatus();
        });

        clearBtn.setOnClickListener(v -> {
            c.getProot().uninstall();
            refreshInfo();
            Toast.makeText(requireContext(), "已清除环境", Toast.LENGTH_SHORT).show();
        });

        backupBtn.setOnClickListener(v -> showBackupOptions());

        resetBtn.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("重置配置？")
                .setMessage("将删除 settings.yaml 和 .env（对话记录保留），并重新写入 .env。")
                .setPositiveButton("重置", (d, w) -> {
                    String r = c.resetConfig();
                    Toast.makeText(requireContext(), r, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show());

        restoreBtn.setOnClickListener(v ->
                pickBackup.launch(new String[]{"*/*"}));
    }

    private void restoreBackup(Uri uri) {
        new AlertDialog.Builder(requireContext())
                .setTitle("恢复备份？")
                .setMessage("将用备份文件覆盖当前的配置和对话记录。\n建议先停止 Web UI 再恢复。")
                .setPositiveButton("恢复", (d, w) -> doRestore(uri))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 备份前弹出选择对话框：勾选要备份的内容 */
    private void showBackupOptions() {
        String[] labels = {
                "配置与对话记录 (.dsh)",
                "OpenCode 凭据 (auth.json)",
                "环境与工具 (usr/etc… 避免重新下载)",
                "工作区源码 (workdir + node_modules)",
                "日志 (dsh-web.log)"
        };
        final boolean[] checked = {true, true, false, true, true};
        new AlertDialog.Builder(requireContext())
                .setTitle("选择备份内容")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("开始备份", (d, w) -> {
                    int opts = 0;
                    if (checked[0]) opts |= BackupManager.OPT_CONFIG;
                    if (checked[1]) opts |= BackupManager.OPT_OPENCODE;
                    if (checked[2]) opts |= BackupManager.OPT_ENV;
                    if (checked[3]) opts |= BackupManager.OPT_WORKDIR;
                    if (checked[4]) opts |= BackupManager.OPT_LOGS;
                    if (opts == 0) {
                        Toast.makeText(requireContext(), "至少选择一项", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runBackup(opts);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runBackup(int opts) {
        Toast.makeText(requireContext(), "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String path = BackupManager.backupToExternal(requireContext(), c, opts);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (path == null) {
                    Toast.makeText(requireContext(), "备份失败：环境可能未安装", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle("备份完成")
                        .setMessage("已导出到：\n" + path)
                        .setPositiveButton("复制路径", (d2, w2) -> {
                            ClipboardManager cm = (ClipboardManager) requireContext()
                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("backup", path));
                                Toast.makeText(requireContext(), "路径已复制", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("好", null)
                        .show();
            });
        }).start();
    }

    private void doRestore(Uri uri) {
        Toast.makeText(requireContext(), "正在停止服务并恢复，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // 0. 先停 Web UI：否则运行中的 DSH 会用旧内存状态覆盖恢复出来的配置
                c.stopWeb();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

                File tmp = new File(c.getProot().getRootfsDir(), "root/.dsha-restore.tar.gz");
                if (tmp.getParentFile() != null) tmp.getParentFile().mkdirs();
                try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                // 解压（自动识别备份包格式）：
                //   v1.4+ 新格式：成员相对根路径（root/.dsh、usr、etc…），在 / 下解压；
                //   v1.1 及更早旧格式：成员相对 /root（.dsh、<wd>/.env、dsh-web.log），在 /root 下解压。
                // 若不识别旧格式，会把 .dsh 解到 /.dsh（错误位置），表现为“恢复成功但什么都没恢复”。
                // 解压后按备份包实际成员逐一校验落位，避免再出现静默失败。
                String out = c.getProot().execChecked(
                        "LIST=$(tar -tzf /root/.dsha-restore.tar.gz 2>/dev/null | grep -v '/$'); "
                        + "if [ -z \"$LIST\" ]; then echo \"EMPTY:unreadable\"; "
                        + "else "
                        + "if echo \"$LIST\" | grep -qE '^(root/|usr/|etc/|opt/|sbin/|bin/|lib/|lib64/|var/)'; then "
                        + "tar -xzf /root/.dsha-restore.tar.gz -C / 2>/dev/null; P=''; "
                        + "else "
                        + "mkdir -p /root && tar -xzf /root/.dsha-restore.tar.gz -C /root 2>/dev/null; P=/root; "
                        + "fi; "
                        + "FAIL=''; "
                        + "for d in $(echo \"$LIST\" | awk -F/ 'NF>0{print $1}' | sort -u); do "
                        + "[ -e \"$P/$d\" ] || FAIL=\"$FAIL $d\"; "
                        + "done; "
                        + "[ -z \"$FAIL\" ] && echo OK || echo \"EMPTY:$FAIL\"; "
                        + "fi");
                if (out == null || !out.trim().endsWith("OK")) {
                    throw new IOException("备份文件解压后未找到有效内容（文件可能损坏，"
                            + "或不是 DSHA 备份，或备份版本过旧无法识别）");
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();

                // ── 恢复后修复 ──

                // 1. 修复 workspace.json（路径尾部空格 + 确保所有 session 在列表里）
                c.getProot().execChecked(
                    "cd /root/.dsh/storages && test -f workspace.json && "
                    + "sed -i 's|\"/root/[a-z]* \"|\"/root/workspace\"|g; s|\"title\": \"[a-z]* \"|\"title\": \"workspace\"|g' workspace.json 2>/dev/null; "
                    + "python3 -c '\n"
                    + "import json,os,glob\n"
                    + "p=\"/root/.dsh/storages/workspace.json\"\n"
                    + "try:\n"
                    + "  d=json.load(open(p))\n"
                    + "  ws=list(d[\"tables\"][\"workspaces\"].values())[0]\n"
                    + "  ws[\"path\"]=ws[\"path\"].rstrip()\n"
                    + "  ws[\"title\"]=ws[\"title\"].rstrip()\n"
                    + "  existing=set(ws[\"sessionIds\"])\n"
                    + "  sess_dir=\"/root/.dsh/sessions\"\n"
                    + "  ws_dir=ws[\"path\"].replace(\"/root/\",\"\")\n"
                    + "  ws_dir_enc=\"--\"+ws_dir.replace(\" \",\"~0020\")+\"--\"\n"
                    + "  full=os.path.join(sess_dir, ws_dir_enc)\n"
                    + "  if os.path.isdir(full):\n"
                    + "    for s in os.listdir(full):\n"
                    + "      if s.startswith(\"session-\") and s not in existing:\n"
                    + "        ws[\"sessionIds\"].append(s)\n"
                    + "  d[\"global\"][\"archivedSessionIds\"]=[]\n"
                    + "  json.dump(d, open(p,\"w\"), indent=2)\n"
                    + "except: pass\n"
                    + "' 2>/dev/null; echo FIX_DONE");

                // 2. 重建 auth.json（从 .dsh/.credentials.yaml 读 OpenCode key 写入标准路径）
                c.getProot().execChecked(
                    "KEY=$(grep -oE '^OPENCODE_GO_API_KEY:[[:space:]]*.+$' /root/.dsh/.credentials.yaml "
                    + " | sed -E 's/^OPENCODE_GO_API_KEY:[[:space:]]*//; s/[[:space:]]+$//'); "
                    + "if [ -n \"$KEY\" ]; then "
                    + "  mkdir -p /root/.local/share/opencode && "
                    + "  echo \"{\\\"opencode-go\\\":{\\\"type\\\":\\\"apikey\\\",\\\"key\\\":\\\"$KEY\\\"}}\" "
                    + "    > /root/.local/share/opencode/auth.json && echo AUTH_OK; "
                    + "fi");

                // 3. 同步所有 API key 到 App 配置（不仅 DEEPSEEK_API_KEY）
                String env = c.getProot().execAndRead(
                        "cat /root/" + c.getWorkdir() + "/.env 2>/dev/null");
                if (env != null) {
                    for (String line : env.split("\n")) {
                        if (line.startsWith("DEEPSEEK_API_KEY=")) {
                            String key = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                            if (!key.isEmpty()) c.setApiKey(key);
                        }
                    }
                }
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "恢复完成，请到「启动」页重新启动 Web UI",
                                Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                            "恢复失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (c != null) refreshInfo();
    }

    private void refreshInfo() {
        String envState = c.isHarnessInstalled() ? "✅ 已安装"
                : c.getProot().isInstalled() ? "🔄 环境已就绪" : "📦 未安装";
        infoText.setText("环境状态：" + envState
                + "\n\n工作区（rootfs 内）：/root/" + c.getWorkdir()
                + "\n\n安装完成后该目录即为 deepseek-harness 源码。");
        refreshShareStatus();
    }

    private void refreshShareStatus() {
        shareStatusText.setText("文件提供器已就绪（MT 官方注入，无需 ROOT）\n\n"
                + "用法：MT 管理器 → 侧拉栏 → 添加本地存储 → 选择「DSHA」\n\n"
                + "工作区在：data → files → linux → ubuntu → root → " + c.getWorkdir() + "\n"
                + "配置在：data → files → linux → ubuntu → root → .dsh\n\n"
                + "（若 MT 里看不到内容，先打开本 App 保持进程运行）");
        refreshShizukuStatus();
    }

    private void refreshShizukuStatus() {
        if (shizukuStatusText == null) return;
        if (!ShizukuShell.isAvailable()) {
            shizukuStatusText.setText("Shizuku 未安装或未启动\n（装好 Shizuku 后，在这里授权）");
        } else if (ShizukuShell.hasPermission()) {
            shizukuStatusText.setText("✅ Shizuku 已授权，助手可执行设备 shell 命令");
        } else {
            shizukuStatusText.setText("Shizuku 已就绪，点击「授权 Shizuku」");
        }
    }
}
