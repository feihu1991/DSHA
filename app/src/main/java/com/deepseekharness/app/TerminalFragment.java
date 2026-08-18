package com.deepseekharness.app;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置终端：直接挂到 proot 的持久 bash 会话上（script 伪终端，真实 TTY）。
 * 输出带 ANSI 颜色渲染（前景/背景色 + 加粗/斜体），历史与会话跨页面保留。
 */
public class TerminalFragment extends Fragment {

    private HarnessController c;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;

    // ===== 静态：跨 Fragment 重建保留 =====
    private static volatile Process shell;
    private static volatile boolean running = false;
    private static volatile Thread readerThread;
    private static final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private static final AnsiRenderer renderer = new AnsiRenderer();
    private static volatile TextView boundOutput;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        inputEdit = view.findViewById(R.id.term_input);
        outputText = view.findViewById(R.id.term_output);
        scrollView = view.findViewById(R.id.term_scroll);
        boundOutput = outputText;

        TextView ctrlcBtn = view.findViewById(R.id.term_ctrlc);
        ctrlcBtn.setOnClickListener(v -> {
            Process p = shell;
            if (p != null && p.isAlive()) {
                try {
                    p.getOutputStream().write(3); // Ctrl+C
                    p.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            }
        });
        TextView clearBtn = view.findViewById(R.id.term_clear);
        clearBtn.setOnClickListener(v -> {
            buffer.clear();
            renderer.reset();
            buffer.append("Ubuntu 24.04 · 回车执行 · 中止 · exit 退出\n");
            if (outputText != null) outputText.setText(buffer);
            if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCommand();
                return true;
            }
            return false;
        });

        // 重绘历史（静态 buffer 切页后仍在）
        outputText.setText(buffer.length() == 0
                ? "Ubuntu 24.04 · 回车执行 · 中止 · exit 退出"
                : buffer);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        if (!c.isHarnessInstalled()) {
            appendLine("环境未安装，请先到「安装」页完成安装");
            return;
        }
        startShell();
    }

    private void startShell() {
        c.ensureDangerGuard();
        Process p = shell;
        if (p != null && p.isAlive() && readerThread != null && readerThread.isAlive()) {
            return;
        }
        new Thread(() -> {
            try {
                shell = c.getProot().execRootfsInteractive();
                running = true;
                byte[] buf = new byte[8192];
                InputStream in = shell.getInputStream();
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    final String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    mainHandler.post(() -> appendRaw(chunk));
                }
                mainHandler.post(() -> appendLine("\n[会话已退出]"));
            } catch (Exception e) {
                mainHandler.post(() -> appendLine("终端启动失败：" + e.getMessage()));
            }
        }, "term-read").start();
    }

    private void sendCommand() {
        String cmd = inputEdit.getText().toString().trim();
        if (cmd.isEmpty()) return;
        inputEdit.setText("");
        // pty 模式：bash 自行回显，无需本地 echo
        Process p = shell;
        if (p == null || !p.isAlive()) {
            appendLine("会话未运行，正在重启…");
            startShell();
            return;
        }
        try {
            p.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        } catch (IOException e) {
            appendLine("发送失败：" + e.getMessage());
        }
    }

    private void appendLine(String s) {
        appendRaw(s + "\n");
    }

    /** 原始输出经 ANSI 渲染后追加到静态 buffer；有绑定视图才刷 UI */
    private void appendRaw(String s) {
        if (s == null || s.isEmpty()) return;
        SpannableString colored = renderer.render(s);
        if (colored.length() == 0) return;
        buffer.append(colored);
        // 过长截断：保留尾部 10 万字符
        if (buffer.length() > 300000) {
            buffer.delete(0, buffer.length() - 100000);
        }
        TextView out = boundOutput;
        if (out == null) return;
        out.setText(buffer);
        ScrollView sv = scrollView;
        if (sv != null) {
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        boundOutput = null;
    }

    // ================= ANSI 颜色渲染 =================

    /** 增量 ANSI 渲染器：维护颜色/样式状态，支持跨 chunk 的未完成转义序列 */
    private static final class AnsiRenderer {
        int fg = -1;   // -1 = 默认前景
        int bg = -1;   // -1 = 默认背景
        int style = 0; // Typeface.BOLD | Typeface.ITALIC
        final StringBuilder pending = new StringBuilder();

        // 16 色（xterm 标准）
        static final int[] C16 = {
                0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
                0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5
        };
        // 亮色（90-97）
        static final int[] C16_BRIGHT = {
                0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
                0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
        };

        void reset() {
            fg = -1; bg = -1; style = 0; pending.setLength(0);
        }

        SpannableString render(String chunk) {
            String data = pending.length() > 0 ? pending.toString() + chunk : chunk;
            pending.setLength(0);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            StringBuilder plain = new StringBuilder();
            int i = 0, n = data.length();
            while (i < n) {
                char ch = data.charAt(i);
                if (ch == '\u001b') {
                    if (i + 1 >= n) { pending.append(ch); break; }
                    char c2 = data.charAt(i + 1);
                    if (c2 == '[') {
                        // CSI：找终止字母（0x40-0x7E）
                        int k = i + 2;
                        while (k < n && (data.charAt(k) < 0x40 || data.charAt(k) > 0x7E)) k++;
                        if (k < n) {
                            flush(sb, plain);
                            applyCsi(data.substring(i + 2, k));
                            i = k + 1;
                            continue;
                        } else {
                            pending.append(data, i, n);
                            i = n;
                            break;
                        }
                    } else if (c2 == ']') {
                        // OSC：到 BEL 或 ESC\
                        int k = i + 2;
                        while (k < n && data.charAt(k) != '\u0007' && data.charAt(k) != '\u001b') k++;
                        if (k < n) {
                            i = (data.charAt(k) == '\u001b' && k + 1 < n && data.charAt(k + 1) == '\\')
                                    ? k + 2 : k + 1;
                            continue;
                        } else {
                            pending.append(data, i, n);
                            i = n;
                            break;
                        }
                    } else {
                        i += 2; // 其他 ESC 序列：跳过
                    }
                } else {
                    plain.append(ch);
                    i++;
                }
            }
            flush(sb, plain);
            return SpannableString.valueOf(sb);
        }

        /** 把累积的纯文本段按当前颜色/样式 flush 进结果 */
        private void flush(SpannableStringBuilder sb, StringBuilder plain) {
            if (plain.length() == 0) return;
            int start = sb.length();
            sb.append(plain);
            int end = sb.length();
            if (fg >= 0) sb.setSpan(new ForegroundColorSpan(fg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bg >= 0) sb.setSpan(new BackgroundColorSpan(bg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style != 0) sb.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            plain.setLength(0);
        }

        private void applyCsi(String params) {
            if (params.isEmpty()) { reset(); return; }
            String[] parts = params.split(";");
            int[] codes = new int[parts.length];
            for (int k = 0; k < parts.length; k++) {
                try { codes[k] = parts[k].isEmpty() ? 0 : Integer.parseInt(parts[k]); }
                catch (NumberFormatException e) { codes[k] = -1; }
            }
            for (int idx = 0; idx < codes.length; idx++) {
                int c = codes[idx];
                if (c == -1) continue;
                if (c == 0) { fg = -1; bg = -1; style = 0; }
                else if (c == 1) style |= Typeface.BOLD;
                else if (c == 2) style |= Typeface.ITALIC; // 弱化：按斜体处理
                else if (c == 3) style |= Typeface.ITALIC;
                else if (c == 22) style &= ~Typeface.BOLD;
                else if (c == 23) style &= ~Typeface.ITALIC;
                else if (c >= 30 && c <= 37) fg = C16[c - 30];
                else if (c == 39) fg = -1;
                else if (c >= 40 && c <= 47) bg = C16[c - 40];
                else if (c == 49) bg = -1;
                else if (c >= 90 && c <= 97) fg = C16_BRIGHT[c - 90];
                else if (c >= 100 && c <= 107) bg = C16_BRIGHT[c - 100];
                else if (c == 38 || c == 48) {
                    // 扩展色：38;5;n / 48;5;n（256 色）或 38;2;r;g;b（真彩）
                    if (idx + 1 < codes.length && codes[idx + 1] == 5 && idx + 2 < codes.length) {
                        int color = color256(codes[idx + 2]);
                        if (c == 38) fg = color; else bg = color;
                        idx += 2;
                    } else if (idx + 1 < codes.length && codes[idx + 1] == 2 && idx + 4 < codes.length) {
                        int color = 0xFF000000 | (codes[idx + 2] << 16) | (codes[idx + 3] << 8) | codes[idx + 4];
                        if (c == 38) fg = color; else bg = color;
                        idx += 4;
                    }
                }
            }
        }

        private static int color256(int n) {
            if (n < 16) return C16[n];
            if (n < 232) {
                n -= 16;
                int r = n / 36, g = (n / 6) % 6, b = n % 6;
                return 0xFF000000 | (cube(r) << 16) | (cube(g) << 8) | cube(b);
            }
            int g = 8 + (n - 232) * 10;
            return 0xFF000000 | (g << 16) | (g << 8) | g;
        }

        private static int cube(int x) {
            return x == 0 ? 0 : 55 + x * 40;
        }
    }
}
