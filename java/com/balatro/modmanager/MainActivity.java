package com.balatro.modmanager;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Balatro 手机版 mod 管理器。
 * 背景是原版 background.fs 的 Java 移植；数据走 SAF，不需要任何权限。
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;
    private static final int REQ_ZIP = 1002;
    private static final String PREFS = "bm";
    private static final String K_TREE = "tree";
    private static final String K_MODS = "mods";

    private static final int ST_OK = 0;
    private static final int ST_NESTED = 1;
    private static final int ST_UNKNOWN = 2;

    // ===== 配色 =====
    static final int C_INK = 0xFFF3EFE2;
    static final int C_MUTED = 0xFF9AAFB4;
    static final int C_DIM = 0xFF6C8388;
    static final int C_GOLD = 0xFFF6C445;
    static final int C_RED = 0xFFFF5245;
    static final int C_GREEN = 0xFF4ED07A;
    static final int C_CYAN = 0xFF35DCDC;
    static final int C_ORANGE = 0xFFF0983A;
    static final int C_PURPLE = 0xFF9A7BEA;
    static final int C_PANEL = 0xF2162830;
    static final int C_PANEL_SOLID = 0xFF14252C;
    static final int C_PANEL_HI = 0xFF1C333C;
    static final int C_BORDER = 0xFF050B0E;
    static final int C_LINE = 0x33FFFFFF;

    private static final int[] TILE_COLORS = {
            0xFFF6C445, 0xFF35DCDC, 0xFF9A7BEA, 0xFF4ED07A,
            0xFFFF8A6B, 0xFF5AA9FF, 0xFFFFC978, 0xFF6FE3B4
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Uri treeUri;
    private String modsDocId;

    private Swirl swirl;
    private TextView pathTv;
    private TextView statMods, statItems, statPatches;
    private TextView headState;
    private LinearLayout listBox;
    private ScrollView scroll;
    private LinearLayout head;
    private Bar bar;
    private volatile boolean busy = false;

    // 字体：display = 游戏原版像素字体；bold/reg = 系统无衬线（某些 ROM 的默认族是宋体）
    static Typeface TF_DISPLAY, TF_BOLD, TF_REG, TF_MONO;

    private final Map<String, String> dirCache = new HashMap<String, String>();

    // ================= 生命周期 =================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        initFonts();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        String t = prefs.getString(K_TREE, null);
        String m = prefs.getString(K_MODS, null);
        if (t != null && m != null) {
            treeUri = Uri.parse(t);
            modsDocId = m;
            refresh();
        } else {
            setHead("等待授权", 0);
            pathTv.setText("尚未选择目录");
            renderEmpty("还没有连接存档目录",
                    "依次进入 Documents → Balatro → game → save",
                    "选择目录", new Runnable() {
                        public void run() {
                            pickTree();
                        }
                    });
        }
    }

    private void initFonts() {
        try {
            TF_DISPLAY = Typeface.createFromAsset(getAssets(), "m6x11plus.ttf");
        } catch (Throwable t) {
            TF_DISPLAY = null;
        }
        TF_REG = fileFont("/system/fonts/Roboto-Regular.ttf");
        if (TF_REG == null) TF_REG = fileFont("/system/fonts/DroidSans.ttf");
        TF_BOLD = fileFont("/system/fonts/DroidSans-Bold.ttf");
        if (TF_BOLD == null) TF_BOLD = fileFont("/system/fonts/Roboto-Regular.ttf");
        TF_MONO = fileFont("/system/fonts/DroidSansMono.ttf");
        if (TF_MONO == null) TF_MONO = TF_REG;
        if (TF_REG == null) TF_REG = Typeface.DEFAULT;
        if (TF_BOLD == null) TF_BOLD = TF_REG;
        if (TF_DISPLAY == null) TF_DISPLAY = TF_BOLD;
        if (TF_MONO == null) TF_MONO = TF_REG;
    }

    private static Typeface fileFont(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return null;
            return Typeface.createFromFile(f);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (swirl != null) swirl.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (swirl != null) swirl.pause();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            busy = false;
            bar.setIdle();
            return;
        }
        Uri u = data.getData();
        if (req == REQ_TREE) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignore) {
                // 个别系统不支持持久化，本次会话仍可用
            }
            bindTree(u);
        } else if (req == REQ_ZIP) {
            installZip(u);
        }
    }

    // ================= 界面骨架 =================

    private void buildUi() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        try {
            w.setNavigationBarDividerColor(Color.TRANSPARENT);
        } catch (Throwable ignore) {
        }
        // 全面屏：内容延伸到状态栏与导航栏下面，再由 insets 决定安全边距
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        swirl = new Swirl(this);
        root.addView(swirl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏 ----
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(14), dp(20), dp(10));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView over = display("BALATRO · MOBILE", 11f, C_GOLD);
        over.setLetterSpacing(0.09f);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = display("MOD MANAGER", 27f, C_INK);
        title.setShadowLayer(dp(8), 0, dp(2), 0xC0000000);
        TextView titleCn = label("模组管理器", 11f, 0xFFA9C0C4, Typeface.NORMAL);
        titleCn.setPadding(dp(10), 0, 0, dp(4));
        titleRow.addView(title);
        titleRow.addView(titleCn);

        tl.addView(over);
        tl.addView(titleRow);
        topRow.addView(tl);

        // 右上角状态胶囊
        headState = label("", 9f, C_MUTED, Typeface.BOLD);
        headState.setLetterSpacing(0.06f);
        headState.setPadding(dp(10), dp(5), dp(10), dp(5));
        topRow.addView(headState);

        TextView more = label("⋮", 20f, 0xFFD8E6E8, Typeface.BOLD);
        more.setGravity(Gravity.CENTER);
        more.setBackgroundDrawable(new PixelPanel(0x66101F26, 0x88B9D6D9, dpf(8), dpf(1.4f), 0));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        mlp.leftMargin = dp(8);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showOverflow();
            }
        });
        press(more);
        topRow.addView(more, mlp);

        head.addView(topRow);

        // 路径条
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setBackgroundDrawable(new PixelPanel(0x77081216, 0x55B9D6D9, dpf(9), dpf(1.2f), 0));
        chipRow.setPadding(dp(10), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(11);
        chipRow.setLayoutParams(clp);

        TextView dot = label("●", 7f, C_CYAN, Typeface.NORMAL);
        dot.setPadding(0, 0, dp(7), 0);
        pathTv = mono("尚未选择目录", 9f, 0xFFAEC4C8);
        chipRow.addView(dot);
        chipRow.addView(pathTv);
        head.addView(chipRow);

        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new Bar(this);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // ---- 列表 ----
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 统计卡
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        statMods = addStat(stats, "MOD", C_GOLD, 0);
        statItems = addStat(stats, "条目", C_CYAN, 1);
        statPatches = addStat(stats, "补丁", C_PURPLE, 2);
        body.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        body.addView(listBox, llp);

        // ---- 悬浮操作坞：作为第二层浮在列表上方 ----
        View bottomScrim = new Fade(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        slp.gravity = Gravity.BOTTOM;
        root.addView(bottomScrim, slp);

        final FrameLayout dockWrap = new FrameLayout(this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.BOTTOM;
        dlp.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(dockWrap, dlp);

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(9), dp(9), dp(9), dp(17));
        dock.setBackgroundDrawable(new PixelPanel(0xFF1A3039, C_BORDER, dpf(12), dpf(4), dp(4)));
        dockWrap.addView(dock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView install = pxButton("INSTALL ZIP", 15f, C_RED, 0xFF0B0E10, false);
        install.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickZip();
            }
        });
        dock.addView(dockCell(install, "安装模组", 0, 1f, 0));

        TextView refreshBtn = pxSmall("REFRESH", new View.OnClickListener() {
            public void onClick(View v) {
                refresh();
            }
        });
        dock.addView(dockCell(refreshBtn, "刷新", dp(70), 0f, dp(8)));

        TextView logBtn = pxSmall("LOGS", new View.OnClickListener() {
            public void onClick(View v) {
                showLog();
            }
        });
        dock.addView(dockCell(logBtn, "日志", dp(70), 0f, dp(8)));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                int top = ins.getSystemWindowInsetTop();
                int bottom = ins.getSystemWindowInsetBottom();
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    android.view.DisplayCutout dc = ins.getDisplayCutout();
                    if (dc != null) {
                        if (dc.getSafeInsetTop() > top) top = dc.getSafeInsetTop();
                        if (dc.getSafeInsetBottom() > bottom) bottom = dc.getSafeInsetBottom();
                    }
                }
                head.setPadding(dp(20), top + dp(12), dp(20), dp(10));

                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) dockWrap.getLayoutParams();
                lp.bottomMargin = bottom + dp(10);
                dockWrap.setLayoutParams(lp);

                final int navBottom = bottom;
                dockWrap.post(new Runnable() {
                    public void run() {
                        scroll.setPadding(0, 0, 0,
                                dockWrap.getHeight() + navBottom + dp(18));
                    }
                });
                return ins;
            }
        });

        setContentView(root);

        if (swirl != null) swirl.resume();
    }

    // ---------- 组件工厂 ----------

    private TextView label(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        // 这台机器的系统默认族是宋体，必须用 /system/fonts 里的无衬线字体
        t.setTypeface(style == Typeface.BOLD ? TF_BOLD : TF_REG);
        return t;
    }

    /** 游戏原版像素字体（m6x11plus），只含基本拉丁字形。 */
    private TextView display(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);

        // 这个字体只有 ASCII 字形。含中文/西文扩展字符时不换字体会落到
        // 系统默认族上，而这台机器默认族是宋体，就会和旁边文字打架。
        if (!isAsciiOnly(s)) {
            // 方块字比像素字视觉重，按 0.84 收一档，两种名字在列表里等高
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.84f);
            t.setTypeface(TF_REG);
            return t;
        }

        // m6x11 是 11px 网格的像素字体：字号取 11 的整数倍，字形才不会糊
        float d = getResources().getDisplayMetrics().density;
        int px = 11 * Math.max(1, Math.round(sp * d / 11f));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTypeface(TF_DISPLAY);
        // 这个字体的行高只报 0.82em，不补内边距会被裁掉上下缘
        int unit = Math.max(1, px / 11);
        t.setPadding(0, unit * 2, 0, unit * 2);
        return t;
    }

    private static boolean isAsciiOnly(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private TextView mono(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(TF_MONO);
        t.setSingleLine(true);
        return t;
    }

    /** 悬浮坞里的一个格子：上面是像素按钮，下面是中文注释。 */
    private LinearLayout dockCell(View btn, String caption, int width, float weight, int marginLeft) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView cap = label(caption, 9.5f, C_MUTED, Typeface.NORMAL);
        cap.setGravity(Gravity.CENTER);
        cap.setLetterSpacing(0.12f);
        cap.setPadding(0, dp(6), 0, 0);
        cell.addView(cap);

        LinearLayout.LayoutParams lp = weight > 0f
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                : new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = marginLeft;
        cell.setLayoutParams(lp);
        return cell;
    }

    /** 主按钮 / 次按钮：贴纸式硬阴影。 */
    private TextView button(String s, float sp, int fill, int textColor, boolean shadow) {
        TextView b = label(s, sp, textColor, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.05f);
        float sh = shadow ? dpf(4) : 0f;
        b.setBackgroundDrawable(new PixelPanel(fill, C_BORDER, dpf(14), dpf(2), sh));
        b.setPadding(dp(6), 0, dp(6), (int) sh);
        press(b);
        return b;
    }

    private TextView ghost(String s, View.OnClickListener l) {
        TextView b = label(s, 11.5f, 0xFFBFD3D6, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.03f);
        b.setBackgroundDrawable(new PixelPanel(0x2EFFFFFF, C_BORDER, dpf(11), dpf(1.2f), 0));
        b.setOnClickListener(l);
        press(b);
        return b;
    }

    private TextView pxButton(String s, float sp, int fill, int textColor, boolean shadow) {        TextView b = display(s, sp, textColor);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.02f);
        float sh = shadow ? dpf(4) : 0f;
        b.setBackgroundDrawable(new PixelPanel(fill, C_BORDER, dpf(6), dpf(6), sh));
        b.setPadding(dp(10), 0, dp(10), (int) sh);
        press(b);
        return b;
    }

    private TextView pxSmall(String s, View.OnClickListener l) {
        TextView b = display(s, 12f, C_INK);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundDrawable(new PixelPanel(0xFF223A44, C_BORDER, dpf(6), dpf(3), 0));
        b.setOnClickListener(l);
        press(b);
        return b;
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView b = label(s, 12f, C_INK, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundDrawable(new PixelPanel(0x2EFFFFFF, C_BORDER, dpf(14), dpf(1.2f), 0));
        b.setOnClickListener(l);
        press(b);
        return b;
    }

    private TextView addStat(LinearLayout parent, String caption, int accent, int idx) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundDrawable(new PixelPanel(0xF0162830, C_BORDER, dpf(13), dpf(1.6f), dp(3)));
        card.setPadding(dp(13), dp(8), dp(10), dp(11));

        TextView num = display("—", 22f, accent);
        TextView lab = label(caption, 8.5f, C_MUTED, Typeface.NORMAL);
        lab.setLetterSpacing(0.16f);
        lab.setPadding(dp(1), 0, 0, 0);
        card.addView(num);
        card.addView(lab);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        if (idx > 0) lp.leftMargin = dp(9);
        parent.addView(card, lp);
        return num;
    }

    private void press(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(70).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                }
                return false;
            }
        });
    }

    private float dpf(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================= 状态 =================

    private void setHead(String text, int color) {
        headState.setText(text);
        headState.setTextColor(color == 0 ? C_MUTED : color);
        int fill = color == C_GREEN ? 0x334ED07A
                : color == C_ORANGE ? 0x33F0983A
                : color == C_RED ? 0x33FF5245
                : 0x2EFFFFFF;
        headState.setBackgroundDrawable(new PixelPanel(fill, C_BORDER, dpf(9), dpf(1.2f), 0));
    }

    private void post(final Runnable r) {
        ui.post(r);
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 选择目录 =================

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("系统里找不到文件选择器");
        }
    }

    private void bindTree(final Uri u) {
        setHead("识别中", C_GOLD);
        bar.setIndeterminate();
        new Thread(new Runnable() {
            public void run() {
                try {
                    treeUri = u;
                    String treeId = DocumentsContract.getTreeDocumentId(u);
                    String rootName = nameOf(treeId);
                    String found;
                    if ("Mods".equals(rootName)) {
                        found = treeId;
                    } else {
                        found = findChildId(treeId, "Mods");
                    }
                    final String fid = found;
                    final String fname = rootName;
                    if (fid == null) {
                        busy = false;
                        post(new Runnable() {
                            public void run() {
                                bar.setIdle();
                                setHead("未找到", C_RED);
                                renderEmpty("这里没有 Mods 文件夹",
                                        "你选的是「" + fname + "」，请退到 save 这一层再选",
                                        "重新选择", new Runnable() {
                                            public void run() {
                                                pickTree();
                                            }
                                        });
                            }
                        });
                        return;
                    }
                    final String ts = u.toString();
                    post(new Runnable() {
                        public void run() {
                            modsDocId = fid;
                            prefs.edit().putString(K_TREE, ts).putString(K_MODS, fid).apply();
                            refresh();
                        }
                    });
                } catch (final Exception e) {
                    busy = false;
                    post(new Runnable() {
                        public void run() {
                            bar.setIdle();
                            setHead("读取失败", C_RED);
                            renderEmpty("目录读取失败", shortMsg(e));
                        }
                    });
                }
            }
        }).start();
    }

    // ================= SAF 基础操作 =================

    private List<Doc> children(String parentId) throws Exception {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        List<Doc> out = new ArrayList<Doc>();
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, proj, null, null, null);
            if (c == null) return out;
            while (c.moveToNext()) {
                Doc d = new Doc();
                d.docId = c.getString(0);
                d.name = c.getString(1) == null ? "" : c.getString(1);
                d.mime = c.getString(2);
                d.size = c.isNull(3) ? -1L : c.getLong(3);
                d.mtime = c.isNull(4) ? 0L : c.getLong(4);
                d.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(d.mime);
                out.add(d);
            }
        } finally {
            if (c != null) c.close();
        }
        Collections.sort(out, new Comparator<Doc>() {
            public int compare(Doc a, Doc b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    private String nameOf(String docId) {
        return nameOfUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
    }

    private String nameOfUri(Uri du) {
        Cursor c = null;
        try {
            c = getContentResolver().query(du,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String findChildId(String parentId, String name) {
        try {
            for (Doc d : children(parentId)) {
                if (name.equals(d.name)) return d.docId;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String mkdir(String parentId, String name) throws Exception {
        Uri pu = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
        Uri created = DocumentsContract.createDocument(getContentResolver(), pu,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("无法创建目录 " + name);
        return DocumentsContract.getDocumentId(created);
    }

    private void deleteDoc(String docId) throws Exception {
        Uri du = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        DocumentsContract.deleteDocument(getContentResolver(), du);
    }

    /** 日志保留时长：超过这个时间的 lovely 日志会被自动删除。 */
    static final long LOG_KEEP_MS = 60L * 60L * 1000L;
    /** 即使全部超时，也留下最新的一份，免得日志区变空。 */
    static final boolean LOG_KEEP_NEWEST = true;

    /** 清理 lovely/log 下过期的日志，返回删除个数。 */
    private int pruneLogs(String logDirId) {
        if (logDirId == null) return 0;
        int n = 0;
        try {
            List<Doc> ds = children(logDirId);
            Doc newest = null;
            for (Doc d : ds) {
                if (d.isDir) continue;
                if (newest == null || d.mtime > newest.mtime) newest = d;
            }
            long cut = System.currentTimeMillis() - LOG_KEEP_MS;
            for (Doc d : ds) {
                if (d.isDir) continue;
                if (!d.name.toLowerCase().endsWith(".log")) continue;
                if (d.mtime <= 0 || d.mtime >= cut) continue;
                if (LOG_KEEP_NEWEST && newest != null && d.docId.equals(newest.docId)) continue;
                try {
                    deleteDoc(d.docId);
                    n++;
                } catch (Exception ignore) {
                    // 单个删不掉就跳过，不影响其它
                }
            }
        } catch (Exception ignore) {
        }
        return n;
    }

    /** 从当前目录里定位 lovely/log 再清理。 */
    private int pruneLogsNow() {
        try {
            String lovelyId = findChildId(modsDocId, "lovely");
            if (lovelyId == null) return 0;
            return pruneLogs(findChildId(lovelyId, "log"));
        } catch (Exception e) {
            return 0;
        }
    }

    private String readText(String docId, int max) throws Exception {
        Uri du = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        InputStream in = getContentResolver().openInputStream(du);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) break;
        }
        in.close();
        return bo.toString("UTF-8");
    }

    // ================= 列表 =================

    private void refresh() {
        if (treeUri == null || modsDocId == null) {
            renderEmpty("还没有连接存档目录", "从右上角「⋮」里选择目录",
                    "选择目录", new Runnable() {
                        public void run() {
                            pickTree();
                        }
                    });
            return;
        }
        if (busy) return;
        busy = true;
        setHead("扫描中", C_GOLD);
        bar.setIndeterminate();
        final String mid = modsDocId;
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Row> rows = new ArrayList<Row>();
                    int modCount = 0;
                    // 顺手清掉超过 1 小时的旧日志
                    final int pruned = pruneLogsNow();
                    for (Doc d : children(mid)) {
                        Row r = new Row();
                        r.doc = d;
                        if (d.isDir) {
                            try {
                                List<Doc> sub = children(d.docId);
                                r.childCount = sub.size();
                                r.state = classify(sub);
                                if (r.state == ST_OK
                                        && !"lovely".equalsIgnoreCase(d.name)
                                        && !"smods".equalsIgnoreCase(d.name)) {
                                    modCount++;
                                }
                            } catch (Exception e) {
                                r.state = ST_UNKNOWN;
                            }
                        }
                        rows.add(r);
                    }
                    final int patches = countPatches();
                    final List<Row> fr = rows;
                    final int fm = modCount;
                    post(new Runnable() {
                        public void run() {
                            busy = false;
                            bar.setIdle();
                            statMods.setText(String.valueOf(fm));
                            statItems.setText(String.valueOf(fr.size()));
                            statPatches.setText(patches < 0 ? "—" : String.valueOf(patches));
                            setHead(fm > 0 ? "已连接" : "空目录",
                                    fm > 0 ? C_GREEN : C_ORANGE);
                            pathTv.setText("Documents/Balatro/game/save/Mods");
                            if (pruned > 0) toast("已清理 " + pruned + " 个过期日志");
                            renderRows(fr);
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        public void run() {
                            busy = false;
                            bar.setIdle();
                            setHead("读取失败", C_RED);
                            renderEmpty("读取失败", shortMsg(e));
                        }
                    });
                }
            }
        }).start();
    }

    private int countPatches() {
        try {
            String lovelyId = findChildId(modsDocId, "lovely");
            if (lovelyId == null) return -1;
            String logDir = findChildId(lovelyId, "log");
            if (logDir == null) return -1;
            Doc latest = null;
            for (Doc d : children(logDir)) {
                if (!d.isDir && (latest == null || d.mtime > latest.mtime)) latest = d;
            }
            if (latest == null) return -1;
            String text = readText(latest.docId, 600000);
            int n = 0;
            for (String ln : text.split("\n")) {
                if (ln.contains("Applied ") && ln.contains("patch")) n++;
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    private int classify(List<Doc> sub) {
        List<Doc> dirs = new ArrayList<Doc>();
        boolean hasEntry = false;
        boolean hasLovely = false;
        for (Doc d : sub) {
            if (d.isDir) {
                dirs.add(d);
                if ("lovely".equalsIgnoreCase(d.name)) hasLovely = true;
            } else {
                String n = d.name.toLowerCase();
                if (n.equals("manifest.json") || n.equals("main.lua")
                        || n.endsWith(".lua") || n.endsWith(".json")) hasEntry = true;
            }
        }
        if (hasEntry || hasLovely) return ST_OK;
        if (dirs.size() == 1 && sub.size() == 1) {
            try {
                for (Doc x : children(dirs.get(0).docId)) {
                    String n = x.name.toLowerCase();
                    if (n.equals("manifest.json") || n.equals("main.lua")
                            || n.endsWith(".lua")) return ST_NESTED;
                }
            } catch (Exception ignore) {
            }
        }
        return ST_UNKNOWN;
    }

    private void renderEmpty(String title, String sub) {
        renderEmpty(title, sub, null, null);
    }

    private void renderEmpty(String title, String sub, String cta, final Runnable action) {
        listBox.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(20), dp(36), dp(20), dp(37));
        card.setBackgroundDrawable(new PixelPanel(0xE014252C, C_BORDER, dpf(16), dpf(1.6f), dp(3)));

        TextView t = label(title, 14f, C_INK, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        TextView s = label(sub, 11.5f, C_MUTED, Typeface.NORMAL);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(8), 0, 0);
        s.setLineSpacing(dp(3), 1f);
        card.addView(t);
        card.addView(s);

        if (cta != null && action != null) {
            TextView b = button(cta, 13.5f, C_RED, 0xFF0B0E10, true);
            b.setPadding(dp(26), 0, dp(26), dp(4));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    action.run();
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            blp.topMargin = dp(18);
            card.addView(b, blp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        listBox.addView(card, lp);
    }

    private void renderRows(List<Row> rows) {
        listBox.removeAllViews();
        if (rows.isEmpty()) {
            renderEmpty("目录是空的", "用「安装 ZIP」把 mod 放进来",
                    "安装 ZIP", new Runnable() {
                        public void run() {
                            pickZip();
                        }
                    });
            return;
        }

        List<Row> mods = new ArrayList<Row>();
        List<Row> bad = new ArrayList<Row>();
        List<Row> sys = new ArrayList<Row>();
        for (Row r : rows) {
            boolean isLovely = "lovely".equalsIgnoreCase(r.doc.name);
            boolean isSmods = "smods".equalsIgnoreCase(r.doc.name);
            if (!r.doc.isDir) sys.add(r);
            else if (isLovely || isSmods) sys.add(r);
            else if (r.state == ST_OK) mods.add(r);
            else bad.add(r);
        }

        int idx = 0;
        if (!mods.isEmpty()) {
            section("已装载", mods.size(), listBox);
            for (Row r : mods) addRow(r, idx++, listBox);
        }
        if (!bad.isEmpty()) {
            section("需要处理", bad.size(), listBox);
            for (Row r : bad) addRow(r, idx++, listBox);
        }
        if (!sys.isEmpty()) {
            section("系统", sys.size(), listBox);
            for (Row r : sys) addRow(r, idx++, listBox);
        }

        TextView foot = label("点任意一项可以重命名或删除 · 改动后重启游戏生效",
                10.5f, 0x77FFFFFF, Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, 0);
        listBox.addView(foot);
    }

    private void section(String text, int count, LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), dp(16), dp(3), dp(9));

        TextView t = label(text, 9.5f, C_MUTED, Typeface.BOLD);
        t.setLetterSpacing(0.18f);
        TextView n = display(String.valueOf(count), 12f, C_GOLD);
        n.setPadding(dp(7), dp(5), dp(10), dp(6));

        View line = new View(this);
        line.setBackgroundColor(0x2EFFFFFF);

        row.addView(t);
        row.addView(n);
        row.addView(line, new LinearLayout.LayoutParams(0, dp(1), 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRow(final Row r, int idx, LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11), dp(10), dp(12), dp(13));
        card.setBackgroundDrawable(new PixelPanel(0xF0162830, C_BORDER, dpf(14), dpf(1.6f), dp(3)));

        int accent = TILE_COLORS[idx % TILE_COLORS.length];
        if (r.doc.isDir && "lovely".equalsIgnoreCase(r.doc.name)) accent = 0xFF6E8790;
        if (r.doc.isDir && "smods".equalsIgnoreCase(r.doc.name)) accent = 0xFF5AA9FF;
        if (r.doc.isDir && r.state == ST_NESTED) accent = C_ORANGE;
        if (r.doc.isDir && r.state == ST_UNKNOWN) accent = 0xFF8496A0;

        String initial = r.doc.name.length() > 0
                ? r.doc.name.substring(0, 1).toUpperCase() : "?";
        TextView tile = label(initial, 15f, 0xFF07100F, Typeface.BOLD);
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundDrawable(new PixelPanel(accent, C_BORDER, dpf(11), dpf(1.8f), dp(2)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        tlp.rightMargin = dp(12);
        card.addView(tile, tlp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = display(r.doc.name, 16f, C_INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView sub = label(r.subtitle(), 10.5f, C_DIM, Typeface.NORMAL);
        sub.setSingleLine(true);
        sub.setPadding(0, dp(3), 0, 0);

        mid.addView(name);
        mid.addView(sub);
        card.addView(mid);

        if (r.doc.isDir) {
            String label;
            int color;
            if ("lovely".equalsIgnoreCase(r.doc.name)) {
                label = "日志";
                color = 0xFF6E8790;
            } else if ("smods".equalsIgnoreCase(r.doc.name)) {
                label = "框架";
                color = 0xFF5AA9FF;
            } else if (r.state == ST_OK) {
                label = "已识别";
                color = C_GREEN;
            } else if (r.state == ST_NESTED) {
                label = "套了一层";
                color = C_ORANGE;
            } else {
                label = "未识别";
                color = 0xFF8C9BA2;
            }
            card.addView(badge(label, color));
        } else {
            card.addView(badge("文件", 0xFF8C9BA2));
        }

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showActions(r);
            }
        });
        press(card);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        parent.addView(card, lp);
    }

    private TextView badge(String text, int color) {
        TextView b = label(text, 9f, 0xFF07100F, Typeface.BOLD);
        b.setLetterSpacing(0.04f);
        b.setPadding(dp(9), dp(4), dp(9), dp(4));
        b.setBackgroundDrawable(new PixelPanel(color, 0xFF050B0E, dpf(8), dpf(1.2f), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    // ================= 单条操作 =================

    private void showActions(final Row r) {
        final Sheet s = new Sheet(r.doc.name,
                r.doc.isDir ? (r.childCount + " 项 · " + stateText(r)) : human(r.doc.size));
        if (r.doc.isDir) {
            s.row("重命名", "改名或加后缀停用", C_CYAN, new Runnable() {
                public void run() {
                    doRename(r.doc);
                }
            });
        }
        s.row("查看详情", "", C_MUTED, new Runnable() {
            public void run() {
                showDetail(r);
            }
        });
        s.row("删除", "连同内部文件一起删掉", C_RED, new Runnable() {
            public void run() {
                confirmDelete(r.doc);
            }
        });
        s.show();
    }

    private String stateText(Row r) {
        if (!r.doc.isDir) return "文件";
        if ("lovely".equalsIgnoreCase(r.doc.name)) return "Lovely 日志目录";
        if ("smods".equalsIgnoreCase(r.doc.name)) return "Steamodded 本体";
        if (r.state == ST_OK) return "已识别为 mod";
        if (r.state == ST_NESTED) return "里面多套了一层";
        return "找不到入口文件";
    }

    private void doRename(final Doc doc) {
        final EditText et = new EditText(this);
        et.setText(doc.name);
        et.setSelection(doc.name.length());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(C_INK);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setBackgroundDrawable(new PixelPanel(0x88101E24, C_BORDER, dpf(10), dpf(1.4f), 0));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));

        final Sheet s = new Sheet("重命名", "加后缀可以临时停用，例如 mymod_off");
        s.view(et);
        s.primary("保存", new Runnable() {
            public void run() {
                final String nn = et.getText().toString().trim();
                if (nn.length() == 0 || nn.contains("/")) {
                    toast("名字不合法");
                    return;
                }
                s.dismiss();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            Uri du = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.docId);
                            DocumentsContract.renameDocument(getContentResolver(), du, nn);
                            post(new Runnable() {
                                public void run() {
                                    refresh();
                                }
                            });
                        } catch (final Exception e) {
                            post(new Runnable() {
                                public void run() {
                                    toast("重命名失败：" + shortMsg(e));
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        s.show();
        et.requestFocus();
        ui.postDelayed(new Runnable() {
            public void run() {
                InputMethodManager im = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 180);
    }

    private void confirmDelete(final Doc doc) {
        final Sheet s = new Sheet("删除 " + doc.name + " ?", "会连同里面所有文件一起删掉，无法撤销。");
        s.primary("删除", new Runnable() {
            public void run() {
                s.dismiss();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            deleteDoc(doc.docId);
                            post(new Runnable() {
                                public void run() {
                                    refresh();
                                }
                            });
                        } catch (final Exception e) {
                            post(new Runnable() {
                                public void run() {
                                    toast("删除失败：" + shortMsg(e));
                                }
                            });
                        }
                    }
                }).start();
            }
        }, C_RED);
        s.secondary("取消");
        s.show();
    }

    private void showDetail(Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append("类型　").append(r.doc.isDir ? "文件夹" : "文件").append('\n');
        if (!r.doc.isDir) sb.append("大小　").append(human(r.doc.size)).append('\n');
        sb.append("子项　").append(r.childCount).append('\n');
        sb.append("状态　").append(stateText(r)).append('\n');
        if (r.state == ST_NESTED) {
            sb.append("\n建议：删掉它，再用「安装 ZIP」重装一次。\n安装器会自动剥掉多余的那层。");
        }
        sb.append("\n内部 ID\n").append(r.doc.docId);
        Sheet s = new Sheet(r.doc.name, null);
        s.text(sb.toString());
        s.secondary("好");
        s.show();
    }

    // ================= ZIP 安装 =================

    private void pickZip() {
        if (modsDocId == null) {
            toast("请先选择目录");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_ZIP);
        } catch (Exception e) {
            toast("系统里找不到文件选择器");
        }
    }

    private void installZip(final Uri zipUri) {
        busy = true;
        setHead("解析压缩包", C_GOLD);
        bar.setIndeterminate();
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<String> names = listZip(zipUri);
                    if (names.isEmpty()) {
                        busy = false;
                        post(new Runnable() {
                            public void run() {
                                bar.setIdle();
                                setHead("压缩包为空", C_RED);
                            }
                        });
                        return;
                    }
                    final List<Install> plan = plan(names, zipBase(zipUri));
                    final int total = names.size();
                    post(new Runnable() {
                        public void run() {
                            confirmInstall(zipUri, plan, total);
                        }
                    });
                } catch (final Exception e) {
                    busy = false;
                    post(new Runnable() {
                        public void run() {
                            bar.setIdle();
                            setHead("读取失败", C_RED);
                            toast(shortMsg(e));
                        }
                    });
                }
            }
        }).start();
    }

    private List<String> listZip(Uri zipUri) throws Exception {
        List<String> out = new ArrayList<String>();
        InputStream in = getContentResolver().openInputStream(zipUri);
        ZipInputStream zis = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) out.add(e.getName());
            }
        } finally {
            zis.close();
        }
        return out;
    }

    private String zipBase(Uri u) {
        String s = u.getLastPathSegment();
        if (s == null) return "mod";
        int i = s.indexOf(".zip");
        if (i > 0) s = s.substring(0, i);
        int j = s.indexOf(':');
        if (j >= 0) s = s.substring(j + 1);
        return s.length() == 0 ? "mod" : s;
    }

    /** 推断落点，自动剥掉多余嵌套层。 */
    private List<Install> plan(List<String> names, String zipBase) {
        String lcp = commonDir(names);
        List<Install> out = new ArrayList<Install>();

        if (!lcp.isEmpty()) {
            Install inst = new Install();
            inst.target = lastSeg(lcp);
            inst.entries = new ArrayList<String>();
            inst.rels = new ArrayList<String>();
            for (String n : names) {
                inst.entries.add(n);
                inst.rels.add(n.substring(lcp.length()));
            }
            out.add(inst);
            return out;
        }

        boolean rootFile = false;
        for (String n : names) {
            if (n.indexOf('/') < 0) {
                rootFile = true;
                break;
            }
        }
        if (rootFile) {
            Install inst = new Install();
            inst.target = zipBase;
            inst.entries = new ArrayList<String>(names);
            inst.rels = new ArrayList<String>(names);
            out.add(inst);
            return out;
        }

        Map<String, Install> byFirst = new LinkedHashMap<String, Install>();
        for (String n : names) {
            String first = n.substring(0, n.indexOf('/'));
            Install inst = byFirst.get(first);
            if (inst == null) {
                inst = new Install();
                inst.target = first;
                inst.entries = new ArrayList<String>();
                inst.rels = new ArrayList<String>();
                byFirst.put(first, inst);
            }
            inst.entries.add(n);
            inst.rels.add(n.substring(first.length() + 1));
        }
        out.addAll(byFirst.values());
        return out;
    }

    private String commonDir(List<String> names) {
        String[][] parts = new String[names.size()][];
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < names.size(); i++) {
            parts[i] = names.get(i).split("/");
            if (parts[i].length - 1 < min) min = parts[i].length - 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < min; k++) {
            String seg = parts[0][k];
            boolean same = true;
            for (int i = 1; i < parts.length; i++) {
                if (!seg.equals(parts[i][k])) {
                    same = false;
                    break;
                }
            }
            if (!same) break;
            sb.append(seg).append('/');
        }
        return sb.toString();
    }

    private String lastSeg(String pathWithSlash) {
        String p = pathWithSlash;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private void confirmInstall(final Uri zipUri, final List<Install> plan, final int total) {
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" 个文件\n");
        for (Install i : plan) {
            sb.append("→ Mods/").append(i.target).append("/   ")
                    .append(i.entries.size()).append(" 个文件\n");
        }
        sb.append("\n同名目录会被整个覆盖。");

        final Sheet s = new Sheet("确认安装", null);
        s.text(sb.toString());

        // 落点预览条
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        chips.setPadding(0, dp(6), 0, 0);
        for (Install i : plan) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView tag = label("MODS", 8f, 0xFF07100F, Typeface.BOLD);
            tag.setLetterSpacing(0.1f);
            tag.setPadding(dp(7), dp(3), dp(7), dp(3));
            tag.setBackgroundDrawable(new PixelPanel(C_GOLD, 0xFF050B0E, dpf(6), dpf(1.2f), 0));

            TextView nm = mono("/" + i.target, 10.5f, C_INK);
            nm.setPadding(dp(9), 0, 0, 0);
            row.addView(tag);
            row.addView(nm);
            chips.addView(row);
        }
        s.view(chips);

        s.primary("安装", new Runnable() {
            public void run() {
                s.dismiss();
                doInstall(zipUri, plan, total);
            }
        });
        s.secondary("取消");
        s.show();
    }

    private void doInstall(final Uri zipUri, final List<Install> plan, final int total) {
        setHead("安装中", C_GOLD);
        bar.setProgress(0f);
        new Thread(new Runnable() {
            public void run() {
                int done = 0;
                int errs = 0;
                String lastErr = null;
                try {
                    for (Install inst : plan) {
                        dirCache.clear();
                        String exist = findChildId(modsDocId, inst.target);
                        if (exist != null) deleteDoc(exist);
                        String rootDir = mkdir(modsDocId, inst.target);

                        Map<String, String> relOf = new HashMap<String, String>();
                        for (int i = 0; i < inst.entries.size(); i++) {
                            relOf.put(inst.entries.get(i), inst.rels.get(i));
                        }

                        InputStream in = getContentResolver().openInputStream(zipUri);
                        ZipInputStream zis = new ZipInputStream(in);
                        try {
                            ZipEntry e;
                            while ((e = zis.getNextEntry()) != null) {
                                if (e.isDirectory()) continue;
                                String rel = relOf.get(e.getName());
                                if (rel == null || rel.length() == 0) continue;
                                try {
                                    writeFile(rootDir, rel, zis);
                                    done++;
                                } catch (Exception we) {
                                    errs++;
                                    lastErr = shortMsg(we);
                                }
                                if (done % 20 == 0) {
                                    final float p = total <= 0 ? 0f
                                            : Math.min(1f, done / (float) total);
                                    post(new Runnable() {
                                        public void run() {
                                            bar.setProgress(p);
                                        }
                                    });
                                }
                            }
                        } finally {
                            zis.close();
                        }
                    }
                    final int fd = done;
                    final int fe = errs;
                    final String le = lastErr;
                    post(new Runnable() {
                        public void run() {
                            busy = false;
                            bar.setIdle();
                            if (fe == 0) {
                                setHead("安装完成", C_GREEN);
                                toast("写入 " + fd + " 个文件，重启游戏生效");
                            } else {
                                setHead("部分失败", C_ORANGE);
                                toast("成功 " + fd + "，失败 " + fe + "：" + le);
                            }
                            refresh();
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        public void run() {
                            busy = false;
                            bar.setIdle();
                            setHead("安装中断", C_RED);
                            toast(shortMsg(e));
                            refresh();
                        }
                    });
                }
            }
        }).start();
    }

    private void writeFile(String parentId, String rel, InputStream in) throws Exception {
        String[] segs = rel.split("/");
        String cur = parentId;
        StringBuilder keyBuf = new StringBuilder();
        for (int i = 0; i < segs.length - 1; i++) {
            if (segs[i].length() == 0) continue;
            keyBuf.append(segs[i]).append('/');
            String key = parentId + "|" + keyBuf;
            String id = dirCache.get(key);
            if (id == null) {
                String exist = findChildId(cur, segs[i]);
                id = exist != null ? exist : mkdir(cur, segs[i]);
                dirCache.put(key, id);
            }
            cur = id;
        }
        String fname = segs[segs.length - 1];
        if (fname.length() == 0) return;
        String exist = findChildId(cur, fname);
        if (exist != null) deleteDoc(exist);
        Uri pu = DocumentsContract.buildDocumentUriUsingTree(treeUri, cur);
        Uri file = DocumentsContract.createDocument(getContentResolver(), pu,
                mimeOf(fname), fname);
        if (file == null) throw new IOException("无法写入 " + fname);
        // 个别文件系统会按 MIME 补后缀（main.lua → main.lua.txt），改回来
        String actual = nameOfUri(file);
        if (actual != null && !fname.equals(actual)) {
            try {
                DocumentsContract.renameDocument(getContentResolver(), file, fname);
            } catch (Exception ignore) {
            }
        }
        OutputStream os = getContentResolver().openOutputStream(file);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            os.close();
        }
    }

    /** 只有 .txt 用 text/plain，其余走 octet-stream，避免系统自动补后缀。 */
    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    // ================= 日志 / 说明 =================

    private void showLog() {
        if (modsDocId == null) {
            toast("请先选择目录");
            return;
        }
        setHead("读日志", C_GOLD);
        new Thread(new Runnable() {
            public void run() {
                try {
                    String lovelyId = findChildId(modsDocId, "lovely");
                    if (lovelyId == null) {
                        post(new Runnable() {
                            public void run() {
                                setHead("无日志", C_ORANGE);
                                Sheet s = new Sheet("还没有日志", "游戏至少启动一次后才会生成 lovely/ 目录。");
                                s.secondary("好");
                                s.show();
                            }
                        });
                        return;
                    }
                    String logDir = findChildId(lovelyId, "log");
                    if (logDir == null) {
                        post(new Runnable() {
                            public void run() {
                                setHead("无日志", C_ORANGE);
                            }
                        });
                        return;
                    }
                    Doc latest = null;
                    final int pruned = pruneLogs(logDir);
                    for (Doc d : children(logDir)) {
                        if (!d.isDir && (latest == null || d.mtime > latest.mtime)) latest = d;
                    }
                    if (latest == null) {
                        setHead("日志为空", C_ORANGE);
                        return;
                    }
                    final String text = readText(latest.docId, 400000);
                    final String title = latest.name;
                    int applied = 0;
                    int warns = 0;
                    for (String ln : text.split("\n")) {
                        if (ln.contains("Applied ") && ln.contains("patch")) applied++;
                        if (ln.startsWith("WARN")) warns++;
                    }
                    final int fa = applied;
                    final int fw = warns;
                    post(new Runnable() {
                        public void run() {
                            setHead("日志已读", C_GREEN);
                            if (pruned > 0) toast("已清理 " + pruned + " 个过期日志");
                            showLogSheet(title, text, fa, fw);
                        }
                    });
                } catch (final Exception e) {
                    post(new Runnable() {
                        public void run() {
                            setHead("读日志失败", C_RED);
                            toast(shortMsg(e));
                        }
                    });
                }
            }
        }).start();
    }

    private void showLogSheet(String title, String body, int applied, int warns) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(0, 0, 0, dp(10));

        TextView a = label(applied + " 处补丁", 10f, C_GREEN, Typeface.BOLD);
        TextView w = label(warns + " 条警告", 10f, C_ORANGE, Typeface.BOLD);
        w.setPadding(dp(12), 0, 0, 0);
        top.addView(a);
        top.addView(w);

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        tv.setTypeface(TF_MONO);
        tv.setTextColor(0xFFC7D7D9);
        tv.setLineSpacing(dp(1), 1f);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundDrawable(new PixelPanel(0xC0101D23, C_BORDER, dpf(10), dpf(1.4f), 0));
        sv.setPadding(dp(10), dp(10), dp(10), dp(10));
        sv.addView(tv);

        // 日志区底部渐隐，避免最后一行被硬切成半截
        FrameLayout logWrap = new FrameLayout(this);
        logWrap.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Fade lf = new Fade(this, 0xFF101D23);
        FrameLayout.LayoutParams lflp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lflp.gravity = Gravity.BOTTOM;
        logWrap.addView(lf, lflp);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(top);
        wrap.addView(logWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Sheet s = new Sheet(title, "保留最近 1 小时，更早的会自动删掉");
        s.view(wrap);
        s.secondary("关闭");
        s.height(0.78f);
        s.show();
    }

    private void showOverflow() {
        Sheet s = new Sheet("操作", null);
        s.row("选择目录", "重新授权存档目录", C_CYAN, new Runnable() {
            public void run() {
                pickTree();
            }
        });
        s.row("Lovely 日志", "最近一次启动的补丁记录", C_PURPLE, new Runnable() {
            public void run() {
                showLog();
            }
        });
        s.row("使用说明", "目录约定与已知限制", C_MUTED, new Runnable() {
            public void run() {
                showHelp();
            }
        });
        s.secondary("关闭");
        s.show();
    }

    private void showHelp() {
        String txt = "目录约定\n"
                + "Documents/Balatro/game/save/Mods/<mod名>/\n"
                + "mod 只能有一层，别再套文件夹。\n\n"
                + "「套了一层」是什么意思\n"
                + "很多解压工具会拿 zip 文件名多建一层目录，游戏就会完全不认。"
                + "看到这个标记，直接删掉，再用「安装 ZIP」重装 —— 安装器会自动剥掉多余的层。\n\n"
                + "不需要任何权限\n"
                + "走系统存储访问框架，只对你在选择目录时点过的那个文件夹有读写权。\n\n"
                + "已知限制\n"
                + "内置 Lovely 为 0.9.0，部分 mod 的补丁按 0.10 语法写，日志里会出现 "
                + "Unknown key 之类的警告；带 native 组件的 mod（如 Talisman）在手机上跑不起来。\n\n"
                + "日志会自动清理\n"
                + "游戏每启动一次就写一个 lovely-*.log，攒久了很占地方。"
                + "管理器在「刷新」和看日志时会自动删掉超过 1 小时的，"
                + "并且永远留下最新的一份，免得日志区变空。\n\n"
                + "背景\n"
                + "移植自游戏原版 background.fs 着色器（极坐标漩涡 + 三色混合）。"
                + "原版主色是红黑白，这里用的是 Cryptid 改过之后的青绿配色 —— "
                + "这个颜色是它换上去的，不是原版。";
        Sheet s = new Sheet("使用说明", null);

        // 说明较长：超过半屏就收成可滚动，别把「好」按钮顶出屏幕
        TextView bodyTv = label(txt, 11.5f, 0xFFC9D9DB, Typeface.NORMAL);
        bodyTv.setLineSpacing(dp(4), 1f);
        final ScrollView sc = new ScrollView(this);
        sc.addView(bodyTv);
        final FrameLayout scWrap = new FrameLayout(this);
        scWrap.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.view(scWrap);
        scWrap.post(new Runnable() {
            public void run() {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
                if (sc.getHeight() <= maxH) return;
                ViewGroup.LayoutParams lp = sc.getLayoutParams();
                lp.height = maxH;
                sc.setLayoutParams(lp);
                Fade hf = new Fade(MainActivity.this, 0xFF14252C);
                FrameLayout.LayoutParams hflp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
                hflp.gravity = Gravity.BOTTOM;
                scWrap.addView(hf, hflp);
            }
        });

        TextView by = label("作者", 9.5f, C_DIM, Typeface.NORMAL);
        by.setLetterSpacing(0.18f);
        by.setPadding(0, dp(16), 0, dp(3));
        s.view(by);

        TextView who = label("Jasper Wang", 13f, C_GOLD, Typeface.BOLD);
        who.setLetterSpacing(0.04f);
        s.view(who);

        s.secondary("好");
        s.show();
    }

    // ================= 工具 =================

    private static String human(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }

    // ================= 底部弹层 =================

    private class Sheet {
        private final Dialog d;
        private final LinearLayout body;
        private final LinearLayout btnRow;

        Sheet(String title, String sub) {
            d = new Dialog(MainActivity.this);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout outer = new FrameLayout(MainActivity.this);
            outer.setPadding(dp(18), dp(26), dp(18), dp(30));

            LinearLayout panel = new LinearLayout(MainActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundDrawable(new PixelPanel(C_PANEL_SOLID, C_BORDER, dpf(16), dpf(3), dp(6)));
            panel.setPadding(dp(20), dp(18), dp(20), dp(16));

            TextView t = label(title, 15f, C_GOLD, Typeface.BOLD);
            t.setLetterSpacing(0.02f);
            panel.addView(t);

            if (sub != null) {
                TextView st = label(sub, 10.5f, C_DIM, Typeface.NORMAL);
                st.setPadding(0, dp(5), 0, 0);
                panel.addView(st);
            }

            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(12);
            panel.addView(body, blp);

            btnRow = new LinearLayout(MainActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(16);
            panel.addView(btnRow, rlp);

            outer.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            d.setContentView(outer);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.76f);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        Sheet text(CharSequence s) {
            TextView tv = label(s.toString(), 11.5f, 0xFFC9D9DB, Typeface.NORMAL);
            tv.setLineSpacing(dp(4), 1f);
            body.addView(tv);
            return this;
        }

        Sheet view(View v) {
            body.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return this;
        }

        Sheet row(String text, String hint, int color, final Runnable r) {
            final LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(13), dp(2), dp(13));

            View mark = new View(MainActivity.this);
            mark.setBackgroundColor(color);

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = label(text, 13.5f, C_INK, Typeface.BOLD);
            mid.addView(t);
            if (hint != null && hint.length() > 0) {
                TextView h = label(hint, 10f, C_DIM, Typeface.NORMAL);
                h.setPadding(0, dp(2), 0, 0);
                mid.addView(h);
            }
            TextView chev = label("›", 18f, 0x66FFFFFF, Typeface.NORMAL);

            LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(dp(7), dp(7));
            mlp2.rightMargin = dp(10);
            row.addView(mark, mlp2);
            row.addView(mid);
            row.addView(chev);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    ui.postDelayed(r, 90);
                }
            });
            press(row);

            View div = new View(MainActivity.this);
            div.setBackgroundColor(0x1AFFFFFF);

            body.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            return this;
        }

        Sheet primary(String text, final Runnable r) {
            return primary(text, r, C_RED);
        }

        Sheet primary(String text, final Runnable r, int color) {
            TextView b = label(text, 13f, 0xFF07100F, Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(20), dp(12), dp(20), dp(12));
            b.setBackgroundDrawable(new PixelPanel(color, C_BORDER, dpf(12), dpf(1.5f), 0));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    r.run();
                }
            });
            press(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            btnRow.addView(b, lp);
            return this;
        }

        Sheet secondary(String text) {
            TextView b = label(text, 13f, 0xFFBFD3D6, Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(18), dp(12), dp(18), dp(12));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                }
            });
            press(b);
            btnRow.addView(b);
            return this;
        }

        Sheet height(float ratio) {
            Window w = d.getWindow();
            if (w != null) {
                int h = (int) (getResources().getDisplayMetrics().heightPixels * ratio);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h);
            }
            return this;
        }

        void show() {
            d.setOnShowListener(new DialogInterface.OnShowListener() {
                public void onShow(DialogInterface di) {
                    Window w = d.getWindow();
                    if (w != null) {
                        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                                w.getAttributes().height);
                    }
                }
            });
            d.show();
        }

        void dismiss() {
            try {
                d.dismiss();
            } catch (Exception ignore) {
            }
        }
    }

    // ================= 进度条 =================

    static class Bar extends View {
        private float value = -1f;

        Bar(android.content.Context c) {
            super(c);
        }

        void setProgress(float v) {
            value = v;
            invalidate();
        }

        void setIndeterminate() {
            value = -2f;
            invalidate();
        }

        void setIdle() {
            value = -1f;
            invalidate();
        }

        protected void onDraw(Canvas cv) {
            if (value == -1f) return;
            int w = getWidth();
            int h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x22000000);
            cv.drawRect(0, 0, w, h, p);
            if (value >= 0f) {
                p.setColor(C_CYAN);
                cv.drawRect(0, 0, w * value, h, p);
            } else {
                float phase = (SystemClock.uptimeMillis() % 1300) / 1300f;
                float bw = w * 0.3f;
                float x = -bw + phase * (w + bw);
                p.setColor(C_CYAN);
                cv.drawRect(Math.max(0f, x), 0, Math.min(w, x + bw), h, p);
                postInvalidateOnAnimation();
            }
        }
    }

    // ================= 背景：原版 background.fs 移植 =================

    static class Swirl extends View {

        // 配色取自游戏内青绿漩涡
        private static final float[] C1 = rgb(0x0B4A57);
        private static final float[] C2 = rgb(0x2EEAEA);
        private static final float[] C3 = rgb(0x3B2E86);
        private static final float CONTRAST = 2.72f;
        private static final float SPIN_AMOUNT = 0f;

        private static final int RW = 190;
        private static final int FPS = 20;

        private int rh = 1;
        private Bitmap[] frames = new Bitmap[2];
        private volatile int front = 0;
        private int[] pix;
        private float[] cosA, sinA, uvLen;

        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final Matrix matrix = new Matrix();
        private final Paint vig = new Paint(Paint.ANTI_ALIAS_FLAG);

        private Thread worker;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile boolean sized = false;

        Swirl(android.content.Context c) {
            super(c);
            shade.setDither(true);
            bmpPaint.setDither(true);
        }

        private static float[] rgb(int v) {
            return new float[]{((v >> 16) & 255) / 255f,
                    ((v >> 8) & 255) / 255f, (v & 255) / 255f};
        }

        void resume() {
            paused = false;
            if (sized && (worker == null || !running)) start();
        }

        void pause() {
            paused = true;
        }

        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w <= 0 || h <= 0) return;
            rh = Math.max(2, Math.round(h / (float) w * RW));
            pix = new int[RW * rh];
            cosA = new float[RW * rh];
            sinA = new float[RW * rh];
            uvLen = new float[RW * rh];

            float L = (float) Math.hypot(RW, rh);
            int i = 0;
            for (int y = 0; y < rh; y++) {
                float uy = (y - 0.5f * rh) / L;
                for (int x = 0; x < RW; x++) {
                    float ux = (x - 0.5f * RW) / L - 0.12f;
                    float len = (float) Math.sqrt(ux * ux + uy * uy);
                    float a = (float) Math.atan2(uy, ux);
                    uvLen[i] = len;
                    cosA[i] = (float) Math.cos(a);
                    sinA[i] = (float) Math.sin(a);
                    i++;
                }
            }
            for (int k = 0; k < 2; k++) {
                if (frames[k] != null) frames[k].recycle();
                frames[k] = Bitmap.createBitmap(RW, rh, Bitmap.Config.ARGB_8888);
            }
            sized = true;
            if (!paused) start();

            // 顶部压暗，保证标题可读
            shade.setShader(new android.graphics.LinearGradient(
                    0, 0, 0, h,
                    new int[]{0x5E0B1A22, 0x140C222B, 0x52102630, 0x8C041117},
                    new float[]{0f, 0.28f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            vig.setShader(new RadialGradient(w * 0.55f, h * 0.30f, h * 0.86f,
                    new int[]{0x00000000, 0x4A000A0E}, null, Shader.TileMode.CLAMP));
        }

        private synchronized void start() {
            if (running) return;
            running = true;
            worker = new Thread(new Runnable() {
                public void run() {
                    long startNs = System.nanoTime();
                    while (running) {
                        if (paused) {
                            SystemClock.sleep(120);
                            startNs += 120000000L;
                            continue;
                        }
                        long t0 = System.nanoTime();
                        float t = (t0 - startNs) / 1e9f;
                        try {
                            frame(t);
                        } catch (Throwable th) {
                            // 背景只是装饰，出错不拖垮界面
                        }
                        front = 1 - front;
                        postInvalidateOnAnimation();
                        long used = (System.nanoTime() - t0) / 1000000L;
                        long wait = 1000L / FPS - used;
                        if (wait > 0) SystemClock.sleep(wait);
                    }
                }
            });
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }

        // ---- 数学工具：查表 sin / 快速 atan2 ----
        private static final int SN = 4096;
        private static final float[] SIN = new float[SN + 1];
        private static final float INV_TAU = (float) (SN / (Math.PI * 2));

        static {
            for (int i = 0; i <= SN; i++) {
                SIN[i] = (float) Math.sin(i * Math.PI * 2 / SN);
            }
        }

        private static float fsin(float x) {
            float t = x * INV_TAU;
            int i = (int) t;
            if (t < 0 && t != i) i--;
            float f = t - i;
            i &= (SN - 1);
            float a = SIN[i];
            return a + (SIN[i + 1] - a) * f;
        }

        private static float fcos(float x) {
            return fsin(x + 1.5707964f);
        }

        private void frame(float time) {
            int n = RW * rh;
            float spinSpeed = time * 0.1f + 302.2f - 10f;
            float cs = fcos(spinSpeed);
            float sn = fsin(spinSpeed);

            float cm = 0.25f * CONTRAST + 0.5f * SPIN_AMOUNT + 1.2f;
            float k = 0.3f / CONTRAST;
            float ws = time * 2f;
            float ws2 = ws * 0.131121f;
            float ws3 = ws * 0.113f;

            float c1r = C1[0], c1g = C1[1], c1b = C1[2];
            float c2r = C2[0], c2g = C2[1], c2b = C2[2];
            float c3r = C3[0], c3g = C3[1], c3b = C3[2];

            int[] out = pix;
            for (int i = 0; i < n; i++) {
                float ca = cosA[i], sa = sinA[i], len = uvLen[i];
                float ux = len * (ca * cs - sa * sn) * 30f;
                float uy = len * (sa * cs + ca * sn) * 30f;

                float v2x = ux + uy;
                float v2y = v2x;

                for (int it = 0; it < 5; it++) {
                    float mx = ux > uy ? ux : uy;
                    float s = fsin(mx);
                    v2x += s + ux;
                    v2y += s + uy;
                    ux += 0.5f * fcos(5.1123314f + 0.353f * v2y + ws2);
                    uy += 0.5f * fsin(v2x - ws3);
                    float dd = fcos(ux + uy) - fsin(ux * 0.711f - uy);
                    ux -= dd;
                    uy -= dd;
                }

                float paint = (float) Math.sqrt(ux * ux + uy * uy) * 0.035f * cm;
                if (paint > 2f) paint = 2f;
                if (paint < 0f) paint = 0f;

                float c1p = 1f - cm * Math.abs(1f - paint);
                if (c1p < 0f) c1p = 0f;
                float c2p = 1f - cm * paint;
                if (c2p < 0f) c2p = 0f;
                float c3p = 1f - Math.min(1f, c1p + c2p);

                float r = k * c1r + (1f - k) * (c1r * c1p + c2r * c2p + c3r * c3p);
                float g = k * c1g + (1f - k) * (c1g * c1p + c2g * c2p + c3g * c3p);
                float b = k * c1b + (1f - k) * (c1b * c1p + c2b * c2p + c3b * c3p);

                int ri = (int) (r * 255f);
                int gi = (int) (g * 255f);
                int bi = (int) (b * 255f);
                if (ri > 255) ri = 255;
                if (gi > 255) gi = 255;
                if (bi > 255) bi = 255;
                out[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
            }
            frames[1 - front].setPixels(out, 0, RW, 0, 0, RW, rh);
        }

        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            Bitmap bmp = frames[front];
            if (bmp != null) {
                float s = Math.max(w / (float) RW, h / (float) rh) * 1.02f;
                matrix.reset();
                matrix.setScale(s, s);
                matrix.postTranslate((w - RW * s) * 0.5f, (h - rh * s) * 0.5f);
                canvas.drawBitmap(bmp, matrix, bmpPaint);
            } else {
                canvas.drawColor(0xFF0A1A20);
            }
            canvas.drawRect(0, 0, w, h, shade);
            canvas.drawRect(0, 0, w, h, vig);
        }

        protected void onDetachedFromWindow() {
            running = false;
            super.onDetachedFromWindow();
        }
    }

    // ================= 底部渐隐 =================

    /** 列表滚到悬浮坞下面时逐渐隐入底色，避免在半透明卡片下露出半截行。 */
    static class Fade extends View {
        private final Paint p = new Paint();
        private final int base;

        Fade(android.content.Context c) {
            this(c, 0xFF0A1418);
        }

        Fade(android.content.Context c, int base) {
            super(c);
            this.base = base;
        }

        protected void onSizeChanged(int w, int h, int ow, int oh) {
            int a0 = base & 0x00FFFFFF;
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[]{a0, 0x8C000000 | a0, 0xF0000000 | a0, 0xFF000000 | a0},
                    new float[]{0f, 0.32f, 0.76f, 1f}, Shader.TileMode.CLAMP));
        }

        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }

    // ================= 像素面板 =================
    /** 像素级面板：阶梯方块边 + 量化色带填充，全程关抗锯齿，边缘是硬像素。 */
    static class PixelPanel extends Drawable {
        private final Paint p = new Paint();
        private final Path outer = new Path();
        private final Path inner = new Path();
        private final int fill;
        private final int stroke;
        private final float unit;      // 一级台阶的像素边长
        private final int steps;       // 每个角几级台阶
        private final float sw;        // 描边宽度（对齐到 unit 的整数倍）
        private final float sh;        // 硬阴影下移
        private final RectF clip = new RectF();

        PixelPanel(int fill, int stroke, float corner, float border, float shadowDy) {
            this.fill = fill;
            this.stroke = stroke;
            float c = Math.max(2f, corner);
            this.unit = Math.max(2f, c / 3f);
            this.steps = Math.max(1, Math.round(c / this.unit));
            this.sw = Math.max(0f, border);
            this.sh = shadowDy;
            p.setAntiAlias(false);
        }

        private static int scale(int c, float k, int add) {
            int a = (c >>> 24) & 255;
            int r = Math.min(255, (int) (((c >> 16) & 255) * k) + add);
            int g = Math.min(255, (int) (((c >> 8) & 255) * k) + add);
            int b = Math.min(255, (int) ((c & 255) * k) + add);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int mix(int c1, int c2, float t) {
            int a = (int) (((c1 >>> 24) & 255) * (1 - t) + ((c2 >>> 24) & 255) * t);
            int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
            int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
            int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        /** 生成四角阶梯轮廓。外侧像素被切成台阶，绝无圆滑过渡。 */
        private void build(Path o, float l, float t, float r, float b) {
            float u = unit;
            float maxC = Math.min((r - l) / 2f, (b - t) / 2f);
            float c = Math.min(steps * u, maxC);
            if (c < u) {          // 面板太窄，退化为直角
                o.reset();
                o.addRect(l, t, r, b, Path.Direction.CW);
                return;
            }
            int n = Math.max(1, (int) (c / u));
            c = n * u;
            o.reset();
            o.moveTo(l + c, t);
            o.lineTo(r - c, t);
            for (int i = 0; i < n; i++) {          // 右上
                float yy = t + (i + 1) * u;
                o.lineTo(r - c + i * u, yy);
                o.lineTo(r - c + (i + 1) * u, yy);
            }
            o.lineTo(r, b - c);
            for (int i = 0; i < n; i++) {          // 右下
                float xx = r - (i + 1) * u;
                o.lineTo(xx, b - c + i * u);
                o.lineTo(xx, b - c + (i + 1) * u);
            }
            o.lineTo(l + c, b);
            for (int i = 0; i < n; i++) {          // 左下
                float xx = l + c - (i + 1) * u;
                o.lineTo(xx, b - i * u);
                o.lineTo(xx, b - (i + 1) * u);
            }
            o.lineTo(l, t + c);
            for (int i = 0; i < n; i++) {          // 左上
                float yy = t + c - (i + 1) * u;
                o.lineTo(l + i * u, yy);
                o.lineTo(l + (i + 1) * u, yy);
            }
            o.close();
        }

        public void draw(Canvas c) {
            Rect b = getBounds();
            float bt = b.top, bb = b.bottom - sh;

            // 硬阴影：整块平移，不带模糊
            if (sh > 0f) {
                build(outer, b.left, b.top + sh, b.right, b.bottom);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x78000000);
                c.drawPath(outer, p);
            }

            build(outer, b.left, bt, b.right, bb);
            p.setStyle(Paint.Style.FILL);
            p.setColor(stroke);
            c.drawPath(outer, p);

            if (sw <= 0f) {
                p.setColor(fill);
                c.drawPath(outer, p);
                return;
            }

            // 内层（描边内侧）用 fill 覆盖，形成硬边黑框
            build(inner, b.left + sw, bt + sw, b.right - sw, bb - sw);
            c.save();
            c.clipPath(inner);

            // 量化色带：把连续渐变切成若干横条，条内纯色 → 像素感
            // 大面板（弹层）改用纯色，否则横向色带会像百叶窗
            float ih = bb - bt - sw * 2f;
            float d = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            boolean flat = ih > 200f * d;
            int bands = flat ? 1 : Math.max(2, Math.round(ih / (unit * 2.5f)));
            float bh = ih / bands;
            int top = scale(fill, flat ? 1.12f : 1.34f, flat ? 4 : 8);
            p.setStyle(Paint.Style.FILL);
            int yTop = Math.round(bt + sw);
            for (int i = 0; i < bands; i++) {
                float t = bands <= 1 ? 0f : i / (float) (bands - 1);
                p.setColor(flat ? fill : mix(top, fill, t));
                int y0 = yTop + Math.round(i * bh);
                int y1 = yTop + Math.round((i + 1) * bh);
                c.drawRect(b.left, y0, b.right, y1, p);
            }

            // 整圈像素描边：约 1dp 粗，上/左/右提亮、底部压暗，形成倒角画框
            int hi = Math.max(1, Math.round(d * 1.2f));
            int yBot = Math.round(bb - sw);
            p.setColor((fill & 0xFF000000)
                    | (mix(fill, 0xFFFFFFFF, flat ? 0.12f : 0.17f) & 0x00FFFFFF));
            c.drawRect(b.left, yTop, b.right, yTop + hi, p);
            c.drawRect(b.left, yTop, b.left + hi, yBot, p);
            c.drawRect(b.right - hi, yTop, b.right, yBot, p);
            p.setColor((fill & 0xFF000000) | (mix(fill, 0xFF000000, 0.24f) & 0x00FFFFFF));
            c.drawRect(b.left, yBot - hi, b.right, yBot, p);

            c.restore();
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ================= 数据结构 =================

    static class Doc {
        String docId;
        String name;
        String mime;
        long size;
        long mtime;
        boolean isDir;
    }

    static class Row {
        Doc doc;
        int state = ST_UNKNOWN;
        int childCount;

        String subtitle() {
            if (!doc.isDir) return human(doc.size);
            String base = childCount + " 项";
            if ("lovely".equalsIgnoreCase(doc.name)) return base + " · Lovely 的日志";
            if ("smods".equalsIgnoreCase(doc.name)) return base + " · Steamodded 本体";
            if (state == ST_NESTED) return base + " · 多套了一层";
            if (state == ST_UNKNOWN) return base + " · 找不到入口文件";
            return base;
        }
    }

    static class Install {
        String target;
        List<String> entries;
        List<String> rels;
    }
}
