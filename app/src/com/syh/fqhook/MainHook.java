package com.syh.fqhook;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄小说「下载即落盘明文」LSPosed 模块 v1.2
 *
 * ── 为什么能成立 ───────────────────────────────────────────────
 *   com.dragon.read v7.2.7.32 未加固、未 DEX 加密（27 个合法 dex、类名未混淆）。
 *   章节解密存在唯一咽喉点：
 *       com.dragon.read.crypt.CryptManager.decrypt(String 密文, String 书级key, int mVer) -> byte[]
 *   public static native，实现在 libdragon_crypt.so，返回值恒为 gzip(1f8b) → 解压即 XHTML。
 *   全 GitHub 无任何公开模块 hook 过它（既有番茄 Xposed 模块只做去广告/伪 VIP）。
 *
 * ── 三个落点 ───────────────────────────────────────────────────
 *   1) CryptManager.decrypt  (after)  —— 被动：App 自己解密时截获明文，必然成功
 *   2) ChapterInfo.a(ItemContent,boolean) (after) —— 唯一的信息汇聚点，一次拿全：
 *         bookName / name(章节名) / bookId / chapterId / content(密文) / contentMd5 / keyVersion
 *      拿到后交给工作线程，自己调 decrypt → **下载瞬间即落盘**，无需点开阅读
 *   3) DecryptKey.<init>     (after)  —— 学到书级密钥(v2)与 mVer，供上面自解密使用
 *
 * ── 输出（公共目录，MediaStore 写入，targetSdk=35 作用域存储下唯一可靠路径）──
 *   /sdcard/Download/番茄导出/
 *     _开关.txt                可编辑开关（force / xhtml / images）
 *     _运行日志.txt            运行日志
 *     丁丁/<0001>_第1章 xxx.txt
 *     丁丁/插图/<hash>.jpg      内嵌插图（番茄私有目录里是明文 JPEG，进程内直读，无需 root）
 *     丁丁/_章节索引.tsv        逐章索引（追加）
 */
public class MainHook implements IXposedHookLoadPackage {

    // ══════════════ 常量 ══════════════
    private static final String VERSION = "1.6.1";
    private static final String TAG = "FQHook";
    private static final String PKG = "com.dragon.read";
    private static final String REL_ROOT = "Download/番茄导出";   // MediaStore RELATIVE_PATH
    private static final String SWITCH_FILE = "_开关.txt";
    private static final String LOG_FILE = "_运行日志.txt";
    private static final String IDX_FILE = "_章节索引.tsv";
    private static final String VERIFY_FILE = "_校对报告.txt";
    private static final String UNKNOWN_BOOK = "_未归类";
    private static final String PREFS = "fqhook";
    private static final String FMT_TXT = "txt";
    private static final String FMT_EPUB = "epub";
    private static final int MAX_PENDING = 600;
    private static final int MAX_MAP = 4000;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern P_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern P_NUM = Pattern.compile("第\\s*([0-9]+)\\s*[章节回卷]");
    private static final Pattern P_NUM_CN = Pattern.compile("第\\s*([〇零一二三四五六七八九两十百千万]+)\\s*[章节回卷]");
    private static final String CN_DIGITS = "一二三四五六七八九";
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    // ══════════════ 运行状态 ══════════════
    private static Context APP;
    private static Class<?> CLS_CRYPT;
    private static ExecutorService POOL;
    private static SharedPreferences EXPORTED;
    private static volatile boolean inited = false;
    private static final ThreadLocal<Boolean> SELF = new ThreadLocal<Boolean>();

    /**
     * 取上下文。handleLoadPackage 执行时 Application 往往还没创建，
     * AndroidAppHelper.currentApplication() 会返回 null —— 必须懒获取，
     * 否则 writePublic 会在第一行静默失败（连报错都没有）。
     */
    private static Context app() {
        Context c = APP;
        if (c != null) return c;
        try {
            c = AndroidAppHelper.currentApplication();
        } catch (Throwable ignore) {
        }
        if (c != null) {
            APP = c;
            if (EXPORTED == null) EXPORTED = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return c;
    }

    private static final Map<String, Info> BY_CONTENT = new ConcurrentHashMap<String, Info>();
    private static final List<Key> KEYS = new CopyOnWriteArrayList<Key>();
    private static final LinkedList<Info> PENDING = new LinkedList<Info>();
    private static final Map<String, File> BOOKDIR = new ConcurrentHashMap<String, File>();
    private static final Set<String> SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static volatile boolean cfgForce = false;
    private static volatile boolean cfgXhtml = false;
    private static volatile boolean cfgImages = true;
    /**
     * 导出格式：txt（默认，纯文本）或 epub（图文 + 目录）。
     * epub 用纯 Java 打包（ZipOutputStream），mimetype 必须 STORED 且为第一个条目，
     * 否则阅读器直接报「不是有效的 EPUB」。
     */
    private static volatile String cfgFormat = FMT_TXT;
    /**
     * 自动导出开关 —— **默认关（手动模式）**。
     * 实测番茄把「读过的章节」也缓存到 files/<uid>/prefix_public_<bookId>/，
     * 与「用户点下载」在解密层面完全同构，自动分辨不出来。
     * 所以默认不自动落盘：章节先进缓冲，用户点设置面板里的「导出本书」才写出。
     * 打开本开关则退回旧的「边读边导」行为。
     */
    private static volatile boolean cfgAuto = false;
    private static volatile boolean cfgReady = false;

    private static final LinkedList<String> RING = new LinkedList<String>();
    private static final AtomicInteger RING_NEW = new AtomicInteger(0);
    private static volatile long lastFlush = 0L;

    // ══════════════ 数据结构 ══════════════
    /** 一本书的某一章 */
    static final class Info {
        String bookId, bookName, chapterId, chapterName, content, contentMd5;
        int keyVersion = -1;
        String hash;              // md5(密文)，用于与 decrypt 的入参对齐
        volatile boolean done;    // 已导出，content 可释放

        String key() {
            return (bookId == null ? "" : bookId) + "|" + (chapterId == null ? "" : chapterId);
        }
    }

    /** 学到的书级密钥 */
    static final class Key {
        final String v2;
        final int ver, mver;
        Key(String v2, int ver, int mver) { this.v2 = v2; this.ver = ver; this.mver = mver; }
    }

    /** 正文块 */
    static final class Blk {
        static final int TEXT = 0, IMG = 1;
        int kind;
        String text;      // TEXT 正文 / IMG 图注
        String imgKey;    // IMG 本地图片键
        byte[] img;       // IMG 图片字节（拿到才非 null）
    }

    // ═══════════════════════════════════════════════════════════
    //  待导出缓冲（手动导出模式）
    //
    //  为什么改成手动：实测番茄把「读过的章节」也缓存到
    //  files/<uid>/prefix_public_<bookId>/<chapterId.hashCode()>，
    //  与「用户点下载」完全同构（同路径、同 boolean 参数、同调用栈、无通知），
    //  自动分辨不出来。于是改成：解密结果先进缓冲，用户点「导出本书」才落盘。
    // ═══════════════════════════════════════════════════════════
    private static final Map<String, Map<String, ChapterBuf>> BUF =
            new ConcurrentHashMap<String, Map<String, ChapterBuf>>();
    private static final int BUF_MAX_PER_BOOK = 5000;
    private static volatile String LAST_BOOK_ID;
    private static volatile String LAST_BOOK_NAME;

    static final class ChapterBuf {
        final int ord;          // 章节号（排序用，-1 = 没解析出来）
        final String title;     // 章节名
        final byte[] xml;       // 明文 XHTML
        ChapterBuf(int ord, String title, byte[] xml) {
            this.ord = ord; this.title = title; this.xml = xml;
        }
    }

    /** 收进缓冲。返回该书累计章节数；-1 表示放弃 */
    private static int bufPut(Info in, byte[] xml) {
        if (in == null || in.bookId == null || in.chapterId == null) return -1;
        Map<String, ChapterBuf> m = BUF.get(in.bookId);
        if (m == null) {
            m = new ConcurrentHashMap<String, ChapterBuf>();
            BUF.put(in.bookId, m);
        }
        if (m.size() >= BUF_MAX_PER_BOOK && !m.containsKey(in.chapterId)) return -1;
        m.put(in.chapterId, new ChapterBuf(ordinal(in.chapterName), in.chapterName, xml));
        LAST_BOOK_ID = in.bookId;
        LAST_BOOK_NAME = in.bookName;
        return m.size();
    }

    // ═══════════════════════════════════════════════════════════
    //  入口
    // ═══════════════════════════════════════════════════════════
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        XposedBridge.log(TAG + " load: " + lp.packageName + "/" + lp.processName);
        if (!PKG.equals(lp.packageName)) return;

        try {
            APP = AndroidAppHelper.currentApplication();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " no app ctx: " + t);
        }
        // 下载时大量章节要等「落盘确认」，单线程会被阻塞任务堵死
        if (POOL == null) POOL = Executors.newFixedThreadPool(8);

        log("=== 番茄导出 v" + VERSION + " 启动 proc=" + lp.processName
                + " api=" + Build.VERSION.SDK_INT + " ===");

        hookCrypt(lp.classLoader);
        hookReaderUtil(lp.classLoader);
        hookChapterInfo(lp.classLoader);
        hookDecryptKey(lp.classLoader);
        hookAppAttach(lp.classLoader);
        hookMineEntry(lp.classLoader);

        if (POOL != null) POOL.execute(new Runnable() {
            @Override public void run() { ready(); }
        });
    }

    /** Application 一就绪就把上下文补上（handleLoadPackage 阶段它是 null） */
    private void hookAppAttach(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Context c = (Context) p.thisObject;
                                if (c == null) return;
                                APP = c;
                                EXPORTED = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                                if (POOL != null) POOL.execute(new Runnable() {
                                    @Override public void run() { ready(); }
                                });
                            } catch (Throwable t) {
                                log("!! appAttach: " + t);
                            }
                        }
                    });
            log("hook Application.attach OK");
        } catch (Throwable t) {
            log("!! hook Application.attach 失败: " + t);
        }
    }

    /** 上下文就绪后的一次性初始化 */
    private void ready() {
        if (inited) return;
        Context c = app();
        if (c == null) return;
        inited = true;
        try {
            log("ctx 就绪 pkg=" + c.getPackageName()
                    + " extMgr=" + Environment.isExternalStorageManager()
                    + " dir=" + Environment.getExternalStorageDirectory());
        } catch (Throwable ignore) {}
        loadSwitch();
        flushLog(true);
    }

    // ═══════════════════════════════════════════════════════════
    //  落点 1：CryptManager.decrypt —— 被动截获（最可靠，必然成功）
    // ═══════════════════════════════════════════════════════════
    private void hookCrypt(ClassLoader cl) {
        try {
            CLS_CRYPT = XposedHelpers.findClass("com.dragon.read.crypt.CryptManager", cl);
            XposedBridge.hookAllMethods(CLS_CRYPT, "decrypt", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        onDecrypt(p);
                    } catch (Throwable t) {
                        log("!! onDecrypt: " + t);
                    }
                }
            });
            Object v = XposedHelpers.callStaticMethod(CLS_CRYPT, "getMaxMetaKeyVersion");
            log("hook decrypt OK, getMaxMetaKeyVersion=" + v);
        } catch (Throwable t) {
            log("!! hook decrypt 失败: " + t);
        }
    }

    private void onDecrypt(XC_MethodHook.MethodHookParam p) {
        if (SELF.get() != null) return;                 // 自己发起的调用，跳过（避免重复导出）
        Object r = p.getResult();
        if (!(r instanceof byte[])) return;
        byte[] res = (byte[]) r;
        if (res.length == 0) return;

        String a0 = (p.args != null && p.args.length > 0 && p.args[0] instanceof String)
                ? (String) p.args[0] : null;
        Info info = (a0 == null) ? null : BY_CONTENT.get(md5hex(a0));

        final Info fi = info;
        final byte[] fr = res;
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (fi == null || fi.bookId == null) return;
                    byte[] xml = isGzip(fr) ? gunzip(fr) : fr;
                    if (xml == null || xml.length == 0) return;
                    int n = bufPut(fi, xml);
                    if (n > 0) {
                        log("已缓存(截获) 《" + fi.bookName + "》" + fi.chapterName
                                + "（待导出 " + n + " 章）");
                    }
                } catch (Throwable t) {
                    log("!! bufPut(onDecrypt): " + t);
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  落点 0：com.dragon.read.reader.utils.n.a(ChapterInfo, DecryptKey)
    //    阅读器的章节解密入口，同时覆盖「在线拉取」与「已下载从 DB 读」两条路径，
    //    返回的 String 已是解压后的明文 XHTML —— 有了它就不再需要靠 <h1> 猜书名。
    //    （类名是单字母混淆名，换版本可能变，失败只降级不报错）
    // ═══════════════════════════════════════════════════════════
    private void hookReaderUtil(ClassLoader cl) {
        try {
            Class<?> u = XposedHelpers.findClass("com.dragon.read.reader.utils.n", cl);
            XposedBridge.hookAllMethods(u, "a", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length < 1) return;
                        Info in = readInfo(p.args[0]);
                        if (in != null && in.hash != null) BY_CONTENT.put(in.hash, in);
                    } catch (Throwable t) {
                        log("!! readerUtil(before): " + t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (!(r instanceof String)) return;
                        String x = (String) r;
                        if (x.length() < 32) return;
                        if (x.indexOf("<?xml") < 0 && x.indexOf("<blk") < 0) return;
                        final Info fi = (p.args != null && p.args.length > 0)
                                ? readInfo(p.args[0]) : null;
                        if (fi == null || fi.bookId == null) return;
                        final byte[] body = x.getBytes(UTF8);
                        POOL.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    // 这条路径覆盖「已下载/已缓存，从 DB 读」的章节
                                    // —— 那些章节不会经过 ChapterInfo.a，必须在这里兜住
                                    int n = bufPut(fi, body);
                                    if (n > 0) {
                                        log("已缓存(读) 《" + fi.bookName + "》"
                                                + fi.chapterName + "（待导出 " + n + " 章）");
                                    }
                                } catch (Throwable t) {
                                    log("!! bufPut(readerUtil): " + t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        log("!! readerUtil(after): " + t);
                    }
                }
            });
            log("hook reader.utils.n.a OK");
        } catch (Throwable t) {
            log("!! hook reader.utils.n.a 失败（降级，仅靠 decrypt 兜底）: " + t);
        }
    }

    /** 从 ChapterInfo 抽出导出所需信息 */
    private static Info readInfo(Object o) {
        if (o == null) return null;
        Info in = new Info();
        in.bookId = str(o, "bookId");
        in.bookName = str(o, "bookName");
        in.chapterId = str(o, "chapterId");
        in.chapterName = str(o, "name");
        in.content = str(o, "content");
        in.contentMd5 = str(o, "contentMd5");
        in.keyVersion = num(o, "keyVersion");
        if (in.content != null) in.hash = md5hex(in.content);
        return in;
    }

    // ═══════════════════════════════════════════════════════════
    //  落点 2：ChapterInfo.a(ItemContent, boolean) —— 信息安全汇聚点
    // ═══════════════════════════════════════════════════════════

    /** 诊断用：同一条调用路径只记一次，避免刷屏 */
    private static final Set<String> DBG_STACKS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 调用栈摘要 —— 用来区分「阅读」与「下载」两条链路 */
    private static String briefStack() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("com.syh.fqhook") || cn.startsWith("java.lang.Thread")
                    || cn.startsWith("de.robv") || cn.startsWith("android.os.")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(cn).append('.').append(e.getMethodName());
            if (++n >= 7) break;
        }
        return sb.toString();
    }

    private void hookChapterInfo(ClassLoader cl) {
        try {
            Class<?> ci = XposedHelpers.findClass("com.dragon.read.reader.download.ChapterInfo", cl);
            XposedBridge.hookAllMethods(ci, "a", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Info in = readInfo(p.getResult());
                        if (in == null || in.content == null || in.content.isEmpty()) return;

                        // ── 诊断：搞清「阅读」与「下载」在参数/调用链上的区别 ──
                        StringBuilder arg = new StringBuilder();
                        if (p.args != null) {
                            for (int i = 0; i < p.args.length; i++) {
                                Object v = p.args[i];
                                String s = (v == null) ? "null"
                                        : (v instanceof String ? ("str(" + ((String) v).length() + ")")
                                        : String.valueOf(v));
                                if (arg.length() > 0) arg.append(' ');
                                arg.append(i).append('=').append(s);
                            }
                        }
                        String sig = "b=" + (p.args != null && p.args.length > 1
                                ? String.valueOf(p.args[1]) : "?")
                                + " | " + briefStack();
                        if (DBG_STACKS.add(sig)) {
                            log("DBG ChapterInfo.a(" + arg + ") stack=" + sig);
                        }

                        if (BY_CONTENT.size() > MAX_MAP) BY_CONTENT.clear();
                        BY_CONTENT.put(in.hash, in);

                        if (in.chapterName == null || in.chapterName.isEmpty()) {
                            log("ChapterInfo: 无章节名 book=" + in.bookName
                                    + " cid=" + in.chapterId + " content.len=" + in.content.length());
                        }
                        submitActive(in);
                    } catch (Throwable t) {
                        log("!! ChapterInfo.a: " + t);
                    }
                }
            });
            log("hook ChapterInfo.a OK");
        } catch (Throwable t) {
            log("!! hook ChapterInfo.a 失败: " + t);
        }
    }

    /** 解密结果只入缓冲，等用户点「导出本书」再落盘（手动导出模式） */
    private void submitActive(final Info in) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (KEYS.isEmpty()) { queuePending(in); return; }
                    byte[] gz = tryDecrypt(in.content, in.keyVersion);
                    if (gz == null) { queuePending(in); return; }
                    byte[] xml = isGzip(gz) ? gunzip(gz) : gz;
                    if (xml == null || xml.length == 0) return;
                    int n = bufPut(in, xml);
                    if (n < 0) return;
                    log("已缓存《" + in.bookName + "》" + in.chapterName
                            + "（待导出 " + n + " 章）");
                } catch (Throwable t) {
                    log("!! submitActive: " + t);
                }
            }
        });
    }

    private void queuePending(Info in) {
        synchronized (PENDING) {
            if (PENDING.size() < MAX_PENDING) PENDING.add(in);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  落点 3：DecryptKey.<init> —— 学书级密钥
    //     字段：a=v1 key(String)  b=v2 key(String)  c=ver(int)
    //           d=m_ver(int)     e=Source         f=notNeedDecrypt(boolean)
    // ═══════════════════════════════════════════════════════════
    private void hookDecryptKey(ClassLoader cl) {
        try {
            Class<?> dk = XposedHelpers.findClass("com.dragon.read.reader.DecryptKey", cl);
            XposedBridge.hookAllConstructors(dk, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object o = p.thisObject;
                        if (o == null) return;
                        String v2 = str(o, "b");
                        int ver = num(o, "c");
                        int mver = num(o, "d");
                        boolean nd = bool(o, "f");
                        if (nd || v2 == null || v2.isEmpty()) return;
                        learnKey(v2, ver, mver);
                    } catch (Throwable t) {
                        log("!! DecryptKey: " + t);
                    }
                }
            });
            log("hook DecryptKey.<init> OK");
        } catch (Throwable t) {
            log("!! hook DecryptKey 失败: " + t);
        }
    }

    private void learnKey(String v2, int ver, int mver) {
        for (Key k : KEYS) {
            if (k.v2.equals(v2) && k.mver == mver) return;
        }
        KEYS.add(new Key(v2, ver, mver));
        log("学到书级密钥: v2.len=" + v2.length() + " ver=" + ver + " mVer=" + mver);
        flushLog(true);
        // 把排队中的章节补做一遍
        final List<Info> todo = new ArrayList<Info>();
        synchronized (PENDING) {
            while (!PENDING.isEmpty() && todo.size() < 120) todo.add(PENDING.removeFirst());
        }
        for (final Info fi : todo) {
            POOL.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (isDone(fi)) return;
                        byte[] gz = tryDecrypt(fi.content, fi.keyVersion);
                        if (gz != null) export(fi, gz, "下载·补做");
                        else {
                            synchronized (PENDING) {
                                if (PENDING.size() < MAX_PENDING) PENDING.add(fi);
                            }
                        }
                    } catch (Throwable t) {
                        log("!! 补做: " + t);
                    }
                }
            });
        }
    }

    /**
     * 用学到的候选密钥逐个尝试。返回值自带校验（gzip 魔数 1f8b），
     * 所以即使 keyVersion 语义对不上，也能靠「谁能解出 gzip」把正确组合试出来。
     */
    private byte[] tryDecrypt(String content, int keyVerHint) {
        if (CLS_CRYPT == null || content == null) return null;
        List<Key> cand = new ArrayList<Key>(KEYS);
        // 先试与提示版本一致的
        for (int i = 0; i < cand.size(); i++) {
            if (cand.get(i).mver == keyVerHint || cand.get(i).ver == keyVerHint) {
                cand.add(0, cand.remove(i));
                break;
            }
        }
        for (Key k : cand) {
            try {
                SELF.set(Boolean.TRUE);
                Object r = XposedHelpers.callStaticMethod(CLS_CRYPT, "decrypt", content, k.v2, k.mver);
                if (r instanceof byte[]) {
                    byte[] b = (byte[]) r;
                    if (b.length > 3 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) return b;
                }
            } catch (Throwable t) {
                // 密钥不对时 native 可能抛异常，属正常
            } finally {
                SELF.remove();
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  下载判据 —— 把「阅读」与「下载」分开
    //
    //  番茄把「下载」的章节落到
    //      files/<uid>/prefix_public_<bookId>/<chapterId.hashCode()>
    //  阅读（含预加载）不写这个目录。
    //  所以「该文件存在」== 这一章是用户主动下载的。
    //  模块跑在宿主进程内，getFilesDir() 直接可达，不需要 root。
    // ═══════════════════════════════════════════════════════════
    private static File chapterFileOf(String bookId, String chapterId) {
        if (bookId == null || bookId.isEmpty() || chapterId == null || chapterId.isEmpty()) {
            return null;
        }
        Context ctx = app();
        if (ctx == null) return null;
        File files = ctx.getFilesDir();
        File[] subs = (files == null) ? null : files.listFiles();
        if (subs == null) return null;
        String name = String.valueOf(chapterId.hashCode());
        for (int i = 0; i < subs.length; i++) {
            File s = subs[i];
            if (!s.isDirectory()) continue;
            File d = new File(s, "prefix_public_" + bookId);
            if (!d.isDirectory()) continue;
            File f = new File(d, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static boolean isDownloaded(String bookId, String chapterId) {
        return chapterFileOf(bookId, chapterId) != null;
    }

    /** 刚点下载的章节可能还没落盘，短暂等一下（命中就立刻返回） */
    private static boolean waitDownloaded(String bookId, String chapterId, long ms) {
        if (isDownloaded(bookId, chapterId)) return true;
        if (!hasDownloadDir(bookId)) {
            // 目录可能刚建出来，给它 2 秒；还是没有就当这本书没下载过
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return false;
            }
            if (isDownloaded(bookId, chapterId)) return true;
            if (!hasDownloadDir(bookId)) return false;
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return false;
            }
            if (isDownloaded(bookId, chapterId)) return true;
        }
        return false;
    }

    /** 这本书有没有下载目录（没有就说明从没下载过，不必等） */
    private static boolean hasDownloadDir(String bookId) {
        if (bookId == null || bookId.isEmpty()) return false;
        Context ctx = app();
        if (ctx == null) return false;
        File files = ctx.getFilesDir();
        File[] subs = (files == null) ? null : files.listFiles();
        if (subs == null) return false;
        for (int i = 0; i < subs.length; i++) {
            File s = subs[i];
            if (!s.isDirectory()) continue;
            if (new File(s, "prefix_public_" + bookId).isDirectory()) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  导出
    // ═══════════════════════════════════════════════════════════
    private void export(Info info, byte[] raw, String src) {
        ready();                                            // 保证开关已读、上下文已就绪
        if (!cfgAuto) return;      // 手动模式：自动导出整体关闭，走「导出本书」
        if (info != null && isDone(info)) return;          // 同章同内容已导出过
        byte[] xml = isGzip(raw) ? gunzip(raw) : raw;
        if (xml == null || xml.length == 0) return;
        String x = new String(xml, UTF8);
        // 只要章节正文 XHTML；封面/图片等其它 blob 放过
        if (x.indexOf("<?xml") < 0 && x.indexOf("<blk") < 0) {
            log("跳过非正文: len=" + xml.length + " head=" + printable(xml, 30));
            return;
        }

        String book = (info != null && notEmpty(info.bookName)) ? info.bookName : UNKNOWN_BOOK;
        String bookId = (info == null) ? null : info.bookId;
        String title = (info != null && notEmpty(info.chapterName)) ? info.chapterName.trim() : "";
        Map<String, byte[]> imgs = new HashMap<String, byte[]>();
        List<Blk> blks = extractBlocks(x, bookId, imgs);
        if (blks.isEmpty()) return;
        if (title.isEmpty()) title = firstBlockText(blks);
        if (title.isEmpty()) {
            title = "章节 " + (info == null ? String.valueOf(SEQ.incrementAndGet()) : info.chapterId);
        }
        // 首块就是章节标题 → 去掉，避免正文里重复一行
        if (!blks.isEmpty() && blks.get(0).kind == Blk.TEXT
                && title.equals(blks.get(0).text == null ? "" : blks.get(0).text.trim())) {
            blks.remove(0);
        }

        String rel = REL_ROOT + "/" + safe(book, 60);
        final String pf = prefix(info, title);
        String fname = pf + "_" + safe(title, 80) + ".txt";
        String hashTag = (info != null && info.hash != null) ? info.hash : md5hex(xml);

        if (info == null && !cfgForce && SEEN.contains(hashTag)) return;

        // 1) 正文
        byte[] txt = buildTxt(book, title, blks);
        boolean ok = writePublic(rel, fname, txt, "text/plain");
        if (!ok) {
            log("!! 正文写出失败，本次不记录、下次重试: " + rel + "/" + fname);
            flushLog(true);
            return;                     // 不 markDone、不入 SEEN → 允许重试
        }
        if (SEEN.size() > 20000) SEEN.clear();
        SEEN.add(hashTag);
        // 2) 插图
        int imgOk = 0;
        if (cfgImages) {
            for (Blk b : blks) {
                if (b.kind != Blk.IMG || b.img == null) continue;
                boolean png = isPng(b.img);
                if (writePublic(rel + "/插图", b.imgKey + (png ? ".png" : ".jpg"),
                        b.img, png ? "image/png" : "image/jpeg")) imgOk++;
            }
        }
        // 3) xhtml（开关）
        if (cfgXhtml) {
            writePublic(rel, pf + "_" + safe(title, 80) + ".xhtml", xml, "application/octet-stream");
        }
        // 4) 索引
        appendIndex(rel, info, pf, title, fname, txt.length, imgOk, src);

        if (info != null) {
            markDone(info);
            info.content = null;   // 释放内存
        }
        log((ok ? "✔ " : "✘ ") + src + " 《" + book + "》" + title
                + " | " + txt.length + "B | 图 " + imgOk + "/" + countImg(blks)
                + " | -> " + rel + "/" + fname);
        flushLog(true);      // 导出是关键节点，日志强制落盘（否则节流会吞掉最后几行）
    }

    private static int countImg(List<Blk> blks) {
        int n = 0;
        for (Blk b : blks) if (b.kind == Blk.IMG) n++;
        return n;
    }

    private static byte[] buildTxt(String book, String title, List<Blk> blks) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("《").append(book).append("》\n");
        b.append(title).append("\n");
        b.append("──────────────────────────────\n\n");
        for (Blk x : blks) {
            if (x.kind == Blk.IMG) {
                b.append("【插图】");
                if (notEmpty(x.text)) b.append(x.text);
                if (x.img != null) b.append("  →  插图/").append(x.imgKey).append(isPng(x.img) ? ".png" : ".jpg");
                else if (notEmpty(x.imgKey)) b.append("  (图片未缓存)");
                b.append("\n");
            } else {
                b.append(x.text).append("\n");
            }
        }
        return b.toString().getBytes(UTF8);
    }

    /** 章节序号：优先「第N章」（阿拉伯/中文数字），否则用 chapterId，再否则自增 */
    private static String prefix(Info info, String title) {
        int ord = ordinal(title);
        if (ord > 0) return String.format(Locale.US, "%04d", ord);
        if (info != null && notEmpty(info.chapterId)) {
            String cid = info.chapterId.replaceAll("[^0-9]", "");
            if (!cid.isEmpty()) {
                while (cid.length() < 10) cid = "0" + cid;
                return "zz" + cid;
            }
        }
        return "zz" + String.format(Locale.US, "%06d", SEQ.incrementAndGet());
    }

    private static int ordinal(String title) {
        if (title == null) return -1;
        Matcher m = P_NUM.matcher(title);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignore) {}
        }
        Matcher c = P_NUM_CN.matcher(title);
        if (c.find()) {
            int v = cn2int(c.group(1));
            if (v > 0) return v;
        }
        return -1;
    }

    private static int cn2int(String s) {
        int total = 0, section = 0, number = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '零' || ch == '〇') { any = true; continue; }
            int d = CN_DIGITS.indexOf(ch);
            if (d >= 0) { number = d + 1; any = true; continue; }
            if (ch == '两') { number = 2; any = true; continue; }
            int u;
            if (ch == '十') u = 10;
            else if (ch == '百') u = 100;
            else if (ch == '千') u = 1000;
            else if (ch == '万') u = 10000;
            else return -1;
            any = true;
            if (u == 10000) {
                section = (section + (number == 0 ? 1 : number)) * u;
                total += section;
                section = 0; number = 0;
            } else {
                if (number == 0) number = 1;
                section += number * u;
                number = 0;
            }
        }
        if (!any) return -1;
        return total + section + number;
    }

    // ═══════════════════════════════════════════════════════════
    //  XHTML → 块（与独立导出器 v1.4 同源逻辑）
    // ═══════════════════════════════════════════════════════════
    private List<Blk> extractBlocks(String xml, String bookId, Map<String, byte[]> imgs) {
        List<Blk> out = new ArrayList<Blk>();
        int i = 0, n = xml.length();
        while (i < n) {
            int nb = xml.indexOf("<blk", i);
            int nd = xml.indexOf("<div", i);
            if (nb < 0 && nd < 0) break;
            if (nd >= 0 && (nb < 0 || nd < nb)) {
                int te = xml.indexOf('>', nd);
                if (te < 0) break;
                String tag = xml.substring(nd, te + 1);
                if (tag.indexOf("image") >= 0) {
                    int close = xml.indexOf("</div>", te);
                    int end = (close < 0) ? n : close;
                    Blk b = parseImageDiv(xml.substring(te + 1, end), bookId);
                    if (b != null) out.add(b);
                    i = (close < 0) ? n : close + 6;
                } else {
                    i = te + 1;   // 普通 div：只跳过标签，内部 <blk> 照收
                }
                continue;
            }
            int gt = xml.indexOf('>', nb);
            if (gt < 0) break;
            int e = xml.indexOf("</blk>", gt);
            if (e < 0) break;
            String t = cleanText(xml.substring(gt + 1, e));
            if (!t.isEmpty()) out.add(text(t));
            i = e + 6;
        }
        if (out.isEmpty()) {
            String t = cleanText(xml);
            if (!t.isEmpty()) out.add(text(t));
        }
        return out;
    }

    private Blk parseImageDiv(String seg, String bookId) {
        String src = null, cap = "";
        int is = seg.indexOf("<img");
        if (is >= 0) {
            int ite = seg.indexOf('>', is);
            String itag = (ite < 0) ? seg.substring(is) : seg.substring(is, ite + 1);
            src = attr(itag, "src");
        }
        int bs = seg.indexOf("<blk");
        if (bs >= 0) {
            int gt = seg.indexOf('>', bs);
            if (gt >= 0) {
                int be = seg.indexOf("</blk>", gt);
                if (be > gt) cap = cleanText(seg.substring(gt + 1, be));
            }
        }
        String key = imgKeyOf(src);
        Blk b = new Blk();
        b.kind = Blk.IMG;
        b.text = cap;
        b.imgKey = key;
        b.img = (key == null) ? null : findImage(key, bookId);
        if (key == null && b.img == null && cap.isEmpty()) return null;
        return b;
    }

    /**
     * 番茄把章节插图放在「书目录/image/」下，明文 JPEG，文件名 = 该 key 的 Java hashCode。
     * 模块跑在番茄进程内 → 直接 File 读它的私有目录，无需 root。
     * 书目录：<filesDir>/<任意账号子目录>/prefix_public_<bookId>/
     */
    private static byte[] findImage(String key, String bookId) {
        Context ctx = app();
        if (ctx == null || key == null || bookId == null) return null;
        try {
            File files = ctx.getFilesDir();
            File[] subs = files == null ? null : files.listFiles();
            if (subs == null) return null;
            for (File s : subs) {
                if (!s.isDirectory()) continue;
                File img = new File(new File(s, "prefix_public_" + bookId), "image/" + key);
                if (img.isFile()) return readAll(img);
            }
        } catch (Throwable t) {
            log("!! findImage: " + t);
        }
        return null;
    }

    private static String imgKeyOf(String src) {
        if (src == null) return null;
        String s = src;
        int k = s.indexOf("novel-pic-r/");
        if (k >= 0) s = s.substring(k + "novel-pic-r/".length());
        else {
            int slash = s.lastIndexOf('/');
            s = (slash >= 0) ? s.substring(slash + 1) : s;
        }
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.isEmpty()) return null;
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return String.valueOf(h);
    }

    private static Blk text(String t) {
        Blk b = new Blk();
        b.kind = Blk.TEXT;
        b.text = t;
        return b;
    }

    private static String firstBlockText(List<Blk> blks) {
        for (Blk b : blks) {
            if (b.kind == Blk.TEXT && notEmpty(b.text)) return b.text.trim();
        }
        return "";
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        String t = (s.indexOf('<') < 0) ? s : P_TAG.matcher(s).replaceAll("");
        return unescape(t).trim();
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') { b.append(c); i++; continue; }
            int sc = s.indexOf(';', i);
            if (sc < 0 || sc - i > 10) { b.append(c); i++; continue; }
            String e = s.substring(i + 1, sc);
            if ("amp".equals(e)) b.append('&');
            else if ("lt".equals(e)) b.append('<');
            else if ("gt".equals(e)) b.append('>');
            else if ("quot".equals(e)) b.append('"');
            else if ("apos".equals(e)) b.append('\'');
            else if ("nbsp".equals(e)) b.append(' ');
            else if (e.startsWith("#")) {
                try {
                    int cp = (e.startsWith("#x") || e.startsWith("#X"))
                            ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                    b.appendCodePoint(cp);
                } catch (Exception ex) { b.append('&').append(e).append(';'); }
            } else b.append('&').append(e).append(';');
            i = sc + 1;
        }
        return b.toString();
    }

    private static String attr(String tag, String name) {
        String p = name + "=\"";
        int i = tag.indexOf(p);
        if (i < 0) return null;
        int s = i + p.length();
        int e = tag.indexOf('"', s);
        return (e < 0) ? null : tag.substring(s, e);
    }

    // ═══════════════════════════════════════════════════════════
    //  公共目录写出（MediaStore，targetSdk 35 下唯一可靠路径）
    // ═══════════════════════════════════════════════════════════
    private static boolean writePublic(String rel, String name, byte[] data, String mime) {
        if (app() == null) return false;
        String r = rel.replaceAll("/+$", "");
        if (Build.VERSION.SDK_INT >= 29) {
            // 策略 A：Downloads 集合 + IS_PENDING
            if (tryInsert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, r, name, data, mime, true, "Downloads+pending")) {
                return true;
            }
            // 策略 B：Downloads 集合，不带 IS_PENDING
            if (tryInsert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, r, name, data, mime, false, "Downloads")) {
                return true;
            }
            // 策略 C：Files 集合（非媒体文件的另一条正规路径）
            if (tryInsert(MediaStore.Files.getContentUri("external"), r, name, data, mime, false, "Files")) {
                return true;
            }
        }
        // 策略 D：直接 File 写（API<29，或持有 MANAGE_EXTERNAL_STORAGE 时可用）
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), r);
            if (!dir.exists() && !dir.mkdirs()) {
                log("!! 直接写 建目录失败: " + dir);
                return false;
            }
            File f = new File(dir, name);
            OutputStream os = new java.io.FileOutputStream(f);
            try { os.write(data); } finally { os.close(); }
            log("写盘(直接File) OK: " + f);
            return true;
        } catch (Throwable t) {
            log("!! 直接写失败 " + r + "/" + name + " : " + t);
            return false;
        }
    }

    /** 查已存在的同名条目（避免 insert 新建导致 MediaStore 自动改名成 "xxx (1).txt"） */
    private static Uri findExisting(ContentResolver cr, Uri coll, String rel, String name) {
        Cursor c = null;
        try {
            c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", name}, null);
            if (c != null && c.moveToFirst()) {
                return Uri.withAppendedPath(coll, String.valueOf(c.getLong(0)));
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static boolean tryInsert(Uri coll, String rel, String name, byte[] data,
                                     String mime, boolean pending, String tag) {
        ContentResolver cr = app().getContentResolver();
        try {
            // 先找同名同目录的既有条目，直接覆盖写。
            // 不走 delete+insert —— 实测 delete 在部分 ROM 上匹配不到
            // （RELATIVE_PATH 存成 "a/b/" 还是 "a/b" 因设备而异），
            // 于是 insert 每次新建，MediaStore 自动改名成 "xxx (1).txt"，
            // 日志文件会疯狂增殖（实测一次攒了 23 个副本）。
            Uri exist = findExisting(cr, coll, rel, name);
            if (exist != null) {
                OutputStream os = cr.openOutputStream(exist, "wt");
                if (os != null) {
                    try {
                        os.write(data);
                        os.flush();
                    } finally {
                        os.close();
                    }
                    return true;
                }
            }
        } catch (Throwable ignore) {}
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            if (pending) cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(coll, cv);
            if (uri == null) {
                log("!! insert[" + tag + "] 返回 null : " + rel + "/" + name);
                return false;
            }
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) {
                log("!! openOutputStream[" + tag + "] 返回 null : " + uri);
                cr.delete(uri, null, null);
                return false;
            }
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            if (pending) {
                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, cv2, null, null);
            }
            return true;
        } catch (Throwable t) {
            log("!! 写盘[" + tag + "] 异常 " + rel + "/" + name + " : " + t);
            return false;
        }
    }

    private void appendIndex(String rel, Info info, String pf, String title, String fname,
                             int bytes, int imgs, String src) {
        StringBuilder sb = new StringBuilder();
        if (!indexExists(rel)) {
            sb.append("时间\t来源\t章节号\t文件名\t章节名\tchapterId\t字节\t插图\n");
        }
        sb.append(TS.format(new Date())).append('\t')
          .append(src).append('\t')
          .append(pf).append('\t')
          .append(fname).append('\t')
          .append(title == null ? "" : title).append('\t')
          .append(info == null || info.chapterId == null ? "" : info.chapterId).append('\t')
          .append(bytes).append('\t')
          .append(imgs).append('\n');
        appendPublic(rel, IDX_FILE, sb.toString().getBytes(UTF8), "text/tab-separated-values");
    }

    private boolean indexExists(String rel) {
        try {
            Cursor c = app().getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", IDX_FILE}, null);
            if (c == null) return false;
            try { return c.getCount() > 0; } finally { c.close(); }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 追加：读出旧内容 + 新内容一起重写（自产文件可读可写） */
    private void appendPublic(String rel, String name, byte[] add, String mime) {
        byte[] old = null;
        try {
            Cursor c = app().getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", name}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        Uri u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                String.valueOf(c.getLong(0)));
                        InputStream is = app().getContentResolver().openInputStream(u);
                        if (is != null) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int k;
                            while ((k = is.read(buf)) > 0) bo.write(buf, 0, k);
                            is.close();
                            old = bo.toByteArray();
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignore) {}
        byte[] all;
        if (old != null && old.length > 0) {
            if (old.length > 4 * 1024 * 1024) {   // 索引过大则截断重来
                all = add;
            } else {
                all = new byte[old.length + add.length];
                System.arraycopy(old, 0, all, 0, old.length);
                System.arraycopy(add, 0, all, old.length, add.length);
            }
        } else {
            all = add;
        }
        writePublic(rel, name, all, mime);
    }

    // ═══════════════════════════════════════════════════════════
    //  开关文件 / 日志
    // ═══════════════════════════════════════════════════════════
    private static final String CFG_DEFAULT =
            "# 番茄导出 开关（改完保存，下次启动番茄生效）\n"
          + "# force=1  忽略已导出记录，全部重导一次\n"
          + "# xhtml=1  额外保存 .xhtml 原文\n"
          + "# images=1 导出插图\n"
          + "# auto=1   打开自动导出（边读边导）；默认 0 = 手动，只在你点「导出本书」时落盘\n"
          + "# format  导出格式：txt（默认）或 epub（含插图与目录）\n"
          + "force=0\n"
          + "xhtml=0\n"
          + "images=1\n"
          + "auto=0\n"
          + "format=txt\n";

    private void loadSwitch() {
        try {
            byte[] b = readPublic(REL_ROOT, SWITCH_FILE);
            if (b == null) {
                writePublic(REL_ROOT, SWITCH_FILE, CFG_DEFAULT.getBytes(UTF8), "text/plain");
                cfgForce = false; cfgXhtml = false; cfgImages = true;
                cfgFormat = FMT_TXT;
            } else {
                String s = new String(b, UTF8);
                for (String line : s.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    boolean v = "1".equals(val) || "true".equalsIgnoreCase(val);
                    if ("force".equals(k)) cfgForce = v;
                    else if ("xhtml".equals(k)) cfgXhtml = v;
                    else if ("images".equals(k)) cfgImages = v;
                    else if ("auto".equals(k)) cfgAuto = v;
                    else if ("format".equals(k)) cfgFormat = normalizeFormat(val);
                }
            }
            cfgReady = true;
            log("开关: auto=" + cfgAuto + " force=" + cfgForce
                    + " xhtml=" + cfgXhtml + " images=" + cfgImages
                    + " format=" + cfgFormat);
        } catch (Throwable t) {
            cfgReady = true;
            log("!! loadSwitch: " + t);
        }
    }

    /** 格式串归一：认 epub，其余一律回落到 txt */
    private static String normalizeFormat(String v) {
        return (v != null && v.toLowerCase(Locale.US).contains(FMT_EPUB)) ? FMT_EPUB : FMT_TXT;
    }

    private byte[] readPublic(String rel, String name) {
        try {
            Cursor c = app().getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", name}, null);
            if (c == null) return null;
            try {
                if (!c.moveToFirst()) return null;
                Uri u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        String.valueOf(c.getLong(0)));
                InputStream is = app().getContentResolver().openInputStream(u);
                if (is == null) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int k;
                while ((k = is.read(buf)) > 0) bo.write(buf, 0, k);
                is.close();
                return bo.toByteArray();
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String s) {
        String line = TS.format(new Date()) + "  " + s;
        XposedBridge.log(TAG + " " + line);
        synchronized (RING) {
            RING.add(line);
            while (RING.size() > 600) RING.removeFirst();
        }
        RING_NEW.incrementAndGet();
        if (RING_NEW.get() >= 4) flushLog(true);
    }

    private static void flushLog(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < 2000) return;
        lastFlush = now;
        RING_NEW.set(0);
        StringBuilder sb = new StringBuilder();
        synchronized (RING) {
            for (String l : RING) sb.append(l).append('\n');
        }
        if (sb.length() == 0) return;
        writePublic(REL_ROOT, LOG_FILE, sb.toString().getBytes(UTF8), "text/plain");
    }

    // ═══════════════════════════════════════════════════════════
    //  记录已导出 / 工具
    // ═══════════════════════════════════════════════════════════
    private boolean isDone(Info in) {
        if (cfgForce || in == null || in.hash == null || EXPORTED == null) return false;
        String k = in.key();
        if (k.startsWith("|")) k = "h:" + in.hash;
        return in.hash.equals(EXPORTED.getString("d." + k, null));
    }

    private void markDone(Info in) {
        if (in == null || in.hash == null || EXPORTED == null) return;
        String k = in.key();
        if (k.startsWith("|")) k = "h:" + in.hash;
        EXPORTED.edit().putString("d." + k, in.hash).apply();
        in.done = true;
    }

    private static boolean isGzip(byte[] b) {
        return b != null && b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static byte[] gunzip(byte[] in) {
        try {
            GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(in));
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(4096, in.length * 3));
            byte[] buf = new byte[16384];
            int k;
            try {
                while ((k = g.read(buf)) > 0) o.write(buf, 0, k);
            } catch (Exception ignore) {}
            g.close();
            return o.size() > 0 ? o.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) {
        try {
            long len = f.length();
            if (len <= 0 || len > 32L * 1024 * 1024) return null;
            FileInputStream is = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) len];
                int off = 0;
                while (off < b.length) {
                    int k = is.read(b, off, b.length - off);
                    if (k <= 0) break;
                    off += k;
                }
                return (off == b.length) ? b : null;
            } finally {
                is.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String md5hex(String s) {
        return md5hex(s.getBytes(UTF8));
    }

    private static String md5hex(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(raw);
            StringBuilder b = new StringBuilder(32);
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "h" + Integer.toHexString(new String(raw, UTF8).hashCode()) + raw.length;
        }
    }

    private static String safe(String name, int max) {
        if (name == null) return "x";
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '\'' || c == '`'
                    || c == '\n' || c == '\r' || c == '\t') b.append('_');
            else b.append(c);
        }
        String s = b.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "x";
        if (s.length() > max) s = s.substring(0, max);
        return s.trim();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String printable(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            char c = (char) (b[i] & 0xFF);
            s.append((c >= 32 && c < 127) ? c : '.');
        }
        return s.toString().replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    private static String str(Object o, String field) {
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            return (v == null) ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int num(Object o, String field) {
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            return (v instanceof Number) ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean bool(Object o, String field) {
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            return (v instanceof Boolean) && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  落点 5：UI 注入 —— 在「我的」页面底部挂一个设置入口
    //
    //    注入位置（实测 dump 的「我的」页视图结构）：
    //      LinearLayout
    //        ├ ViewGroup  id=ime     ← 顶部工具栏（扫码 / 搜索 / 夜间 / 三条杠）
    //        └ ScrollView id=i7o     ← 内容区
    //            └ RelativeLayout    ← 所有卡片都在这，往它尾部 addView 即可
    //
    //    怎么认出「我的」页：**不按类名、不按 id**。
    //      FanqieMineFragmentV2 是混淆过的内部类，实测它并不 override
    //      onViewCreated，hook 它会直接 NoSuchMethodError。
    //      改成 hook Fragment 基类的 onViewCreated，谁 inflate 出来的视图里
    //      含「我的订单」字样，谁就是「我的」页 —— 类名/混淆/改版都免疫。
    //
    //    为什么不用右上角三条杠那个抽屉：抽屉内容是 ComposeView 渲染的，
    //      Compose 自行管理布局，插原生 View 进去不会被排版，因此放弃。
    // ═══════════════════════════════════════════════════════════
    private static final String TAG_ENTRY = "fqhook_entry";
    /** 「我的」页识别锚点（命中任一即可）—— 不看类名、不看 id，改版也不怕 */
    private static final String[] PAGE_MARKS = {
            "我的订单", "浏览历史", "我的消息", "成为作家", "我的预约"
    };
    /** 已经挂上去的入口（全局唯一：Fragment 与 Activity 两条通道都会走到注入，只留一个） */
    private static volatile View sEntry;
    /** 最近一次 onResume 的 Activity —— root.getContext() 可能是 DecorContext，强转会崩 */
    private static volatile Activity CURRENT;
    /** 已经探测过的 Fragment 类名，避免日志刷屏 */
    private static final Set<String> PROBED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private void hookMineEntry(ClassLoader cl) {
        hookFragmentBase("androidx.fragment.app.Fragment", cl);
        hookFragmentBase("android.support.v4.app.Fragment", cl);
        hookFragmentBase("android.app.Fragment", cl);
        hookMineByActivity(cl);
    }

    /**
     * 兜底通道：番茄启动时若直接停在「我的」tab，Fragment 的 onViewCreated
     * 可能在我们 hook 之前就跑完了。这里挂主 Activity 的 onResume，随后短暂轮询。
     */
    private void hookMineByActivity(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object o = p.thisObject;
                                if (!(o instanceof Activity)) return;
                                Activity a = (Activity) o;
                                CURRENT = a;                    // 记录前台 Activity，供点击时兜底
                                String cn = a.getClass().getName();
                                if (cn == null || cn.indexOf("MainFragmentActivity") < 0) return;
                                final View decor = a.getWindow().getDecorView();
                                decor.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            pollInject(decor, 1);
                                        } catch (Throwable t) {
                                            log("!! pollInject: " + t);
                                        }
                                    }
                                }, 1500);
                            } catch (Throwable t) {
                                log("!! onResume hook: " + t);
                            }
                        }
                    });
            log("hook Activity.onResume（「我的」页兜底通道）OK");
        } catch (Throwable t) {
            log("!! hook Activity.onResume 失败: " + t);
        }
    }

    private void pollInject(final View decor, final int n) {
        if (findAnchor(decor) != null) {
            injectEntry(decor, null, n);
            return;
        }
        if (n > 10) {
            log("兜底注入放弃：轮询 " + n + " 次仍未见「我的」页锚点");
            return;
        }
        decor.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    pollInject(decor, n + 1);
                } catch (Throwable t) {
                    log("!! pollInject 重试: " + t);
                }
            }
        }, 500);
    }

    private void hookFragmentBase(String cn, ClassLoader cl) {
        try {
            final Class<?> f = XposedHelpers.findClass(cn, cl);
            XposedHelpers.findAndHookMethod(f, "onViewCreated", View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args == null || p.args.length < 1
                                        || !(p.args[0] instanceof View)) return;
                                final View root = (View) p.args[0];
                                final String cls = p.thisObject.getClass().getName();
                                root.post(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            TextView anchor = findAnchor(root);
                                            if (anchor == null) {
                                                if (PROBED.add(cls)) {
                                                    log("Fragment " + cls + " → 无「我的」页锚点");
                                                }
                                                return;
                                            }
                                            log("识别到「我的」页: " + cls);
                                            injectEntry(root, null, 1);
                                        } catch (Throwable t) {
                                            log("!! 我的页注入: " + t);
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                log("!! frag onViewCreated: " + t);
                            }
                        }
                    });
            log("hook " + cn + ".onViewCreated OK");
        } catch (Throwable t) {
            log("hook " + cn + " 不可用: " + t);
        }
    }

    /** 找出「我的」页的锚点控件（多候选，命中任一即可） */
    private static TextView findAnchor(View v) {
        for (int i = 0; i < PAGE_MARKS.length; i++) {
            TextView t = findByText(v, PAGE_MARKS[i]);
            if (t != null) return t;
        }
        return null;
    }

    /** 视图树里含指定文字的 TextView，用作定位锚点 */
    private static TextView findByText(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().contains(needle)) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findByText(g.getChildAt(i), needle);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 滚动类容器（NestedScrollView 并不是 ScrollView 的子类，得按类名认） */
    private static boolean isScrollish(View v) {
        if (v instanceof ScrollView) return true;
        String n = v.getClass().getName();
        return n.endsWith("NestedScrollView") || n.endsWith("ParentNestedScrollView");
    }

    private static void collectScrollish(View v, List<View> out) {
        if (isScrollish(v)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectScrollish(g.getChildAt(i), out);
        }
    }

    private static boolean treeContains(ViewGroup g, View target) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == target) return true;
            if (c instanceof ViewGroup && treeContains((ViewGroup) c, target)) return true;
        }
        return false;
    }

    /** 「子树含锚点、面积最大」的滚动容器 */
    private static View biggestScrollish(View root, View anchor) {
        List<View> all = new ArrayList<View>();
        collectScrollish(root, all);
        View best = null;
        long bestArea = -1;
        for (int i = 0; i < all.size(); i++) {
            View s = all.get(i);
            if (anchor != null && s instanceof ViewGroup && !treeContains((ViewGroup) s, anchor)) {
                continue;
            }
            long a = (long) s.getWidth() * (long) s.getHeight();
            if (a > bestArea) { bestArea = a; best = s; }
        }
        return best;
    }

    /**
     * 找「我的」页的内容容器。
     *
     * ⚠ 两条都是从坑里爬出来的：
     *   1) 不能从锚点向上爬 —— 「我的订单」这类文字往往是 GridView 的一个 item，
     *      往里 addView 会把那一格顶掉（实测「我的订单」会直接消失）。
     *   2) 不能只取第一个滚动容器 —— 页面里有嵌套的小滚动区，取错入口会挂到页面中段。
     */
    private static ViewGroup contentBoxOf(View root, View anchor) {
        View best = biggestScrollish(root, anchor);
        if (!(best instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) best;
        if (g.getChildCount() == 0) return null;
        View c = g.getChildAt(0);
        return (c instanceof ViewGroup) ? (ViewGroup) c : null;
    }

    /**
     * 把设置入口挂到「我的」页。
     * 首选：挂在 ScrollView 的兄弟位（内容容器是垂直 LinearLayout 时），做成一条固定底栏
     *       —— 显眼、不遮挡内容、不随滚动跑掉。
     * 回退：挂进内容容器。
     */
    private void injectEntry(View root, Object frag, int attempt) {
        TextView anchor = findAnchor(root);
        if (anchor == null) {                   // 先确认这是「我的」页
            retryInject(root, frag, attempt);
            return;
        }

        View sv = biggestScrollish(root, anchor);
        ViewGroup host = null;
        boolean asBar = false;
        if (sv != null) {
            android.view.ViewParent p = sv.getParent();
            if (p instanceof LinearLayout) {
                host = (LinearLayout) p;
                asBar = true;
            }
        }
        if (host == null) {
            host = contentBoxOf(root, anchor);
            asBar = false;
        }
        if (host == null) { retryInject(root, frag, attempt); return; }

        View prev = sEntry;
        if (prev != null) {
            if (prev.getParent() == host) return;        // 已经在目标容器里，别重复挂
            if (prev.getParent() instanceof ViewGroup) {
                ((ViewGroup) prev.getParent()).removeView(prev);
            }
            sEntry = null;
        }

        Activity act = activityOf(root, frag);
        Context ctx = (act != null) ? act : root.getContext();
        View entry = buildEntry(ctx, act);

        if (asBar) {
            host.addView(entry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 让 ScrollView 让出这条底栏的高度，否则它会把底栏挤出屏幕
            ViewGroup.LayoutParams slp = sv.getLayoutParams();
            if (slp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) slp;
                if (l.height != 0 || l.weight == 0f) {
                    l.height = 0;
                    l.weight = 1f;
                    sv.setLayoutParams(l);
                }
            }
        } else {
            addInto(host, entry);
        }
        sEntry = entry;
        log("已注入「我的」页入口 → " + (asBar ? "底部栏 " : "内容容器 ")
                + host.getClass().getSimpleName()
                + " " + host.getWidth() + "x" + host.getHeight()
                + " 子视图数=" + host.getChildCount() + "（第 " + attempt + " 次尝试）");
    }

    /**
     * 把入口挂进内容容器。
     * 容器是 RelativeLayout 时必须补布局规则，否则默认落在左上角挡住昵称区。
     */
    private static void addInto(ViewGroup box, View entry) {
        Context c = box.getContext();
        int ml = dp(c, 12), mt = dp(c, 10), mr = dp(c, 12), mb = dp(c, 32);
        ViewGroup.LayoutParams lp;
        if (box instanceof RelativeLayout) {
            RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 这个 RelativeLayout 的子视图是层叠的（都从顶部起算），BELOW 会落在页面中段，
            // 所以统一贴底放，「我的」页内容底部正好是空白区。
            rlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            rlp.setMargins(ml, mt, mr, mb);
            lp = rlp;
        } else if (box instanceof LinearLayout) {
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.setMargins(ml, mt, mr, mb);
            lp = llp;
        } else if (box instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.gravity = Gravity.BOTTOM;
            flp.setMargins(ml, mt, mr, mb);
            lp = flp;
        } else {
            lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        box.addView(entry, lp);
    }

    private void retryInject(final View root, final Object frag, final int attempt) {
        if (attempt > 15) {
            log("注入放弃：重试 " + attempt + " 次仍未找到「我的」页锚点");
            return;
        }
        root.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    injectEntry(root, frag, attempt + 1);
                } catch (Throwable t) {
                    log("!! 注入重试: " + t);
                }
            }
        }, 400);
    }

    private static Activity activityOf(View v, Object frag) {
        if (frag != null) {
            try {
                Object a = XposedHelpers.callMethod(frag, "getActivity");
                if (a instanceof Activity) return (Activity) a;
            } catch (Throwable ignore) {}
        }
        Activity a = findActivity(v.getContext());
        return (a != null) ? a : CURRENT;
    }

    /** 沿着 Context 链找 Activity。注意某些 View 的 Context 是 DecorContext，不是 Activity */
    private static Activity findActivity(Context c) {
        while (c != null) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            Context b = ((android.content.ContextWrapper) c).getBaseContext();
            if (b == c) return null;
            c = b;
        }
        return null;
    }

    /** 入口条目：番茄🍅 + 标题 + 副标题 + 箭头，样式贴近番茄的深色卡片 */
    private View buildEntry(final Context ctx, final Activity act) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(TAG_ENTRY);
        row.setClickable(true);
        row.setFocusable(true);

        int p = dp(ctx, 16);
        row.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E22);
        bg.setCornerRadius(dp(ctx, 14));
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 32));
        row.setLayoutParams(lp);

        TextView icon = new TextView(ctx);
        icon.setText("🍅");
        icon.setTextSize(20);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = dp(ctx, 12);
        row.addView(icon, ilp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(ctx);
        title.setText("番茄导出设置");
        title.setTextSize(16);
        title.setTextColor(0xFFFFFFFF);
        col.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("下载即落盘明文 · v" + VERSION);
        sub.setTextSize(12);
        sub.setTextColor(0xFF9A9AA0);
        col.addView(sub);

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(0xFF8A8A90);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Activity a = act;
                    if (a == null || a.isFinishing()) a = CURRENT;
                    if (a == null) a = findActivity(ctx);
                    if (a == null) {
                        log("!! 点开设置失败：拿不到 Activity");
                        return;
                    }
                    showSettings(a);
                } catch (Throwable t) {
                    log("!! showSettings: " + t);
                }
            }
        });
        return row;
    }

    // ── 设置面板 ────────────────────────────────────────────────
    interface BoolSet { void set(boolean v); }

    private void showSettings(final Activity act) {
        if (act == null || act.isFinishing()) return;

        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = newDialogBox(act);

        TextView t = new TextView(act);
        t.setText("🍅  番茄导出设置");
        t.setTextSize(18);
        t.setTextColor(0xFFFFFFFF);
        box.addView(t);

        TextView ver = new TextView(act);
        ver.setText("v" + VERSION + " · 读番茄本地缓存，调用番茄自己的解密例程，离线可用");
        ver.setTextSize(11);
        ver.setTextColor(0xFF8E8E94);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(act, 4);
        vlp.bottomMargin = dp(act, 10);
        box.addView(ver, vlp);

        // 输出目录
        TextView dir = new TextView(act);
        dir.setText("输出目录\n/sdcard/Download/番茄导出/");
        dir.setTextSize(12);
        dir.setTextColor(0xFFB8B8BE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(act, 6);
        box.addView(dir, dlp);

        box.addView(divider(act));

        // ── 手动导出（主操作）──
        LinearLayout expRow = new LinearLayout(act);
        expRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams erlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erlp.topMargin = dp(act, 8);
        erlp.bottomMargin = dp(act, 2);
        box.addView(expRow, erlp);

        TextView expBtn = new TextView(act);
        expBtn.setText(exportBtnText());
        expBtn.setTextSize(15);
        expBtn.setGravity(Gravity.CENTER);
        expBtn.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        GradientDrawable ebg = new GradientDrawable();
        ebg.setCornerRadius(dp(act, 12));
        ebg.setColor(0xFFFF5A5F);
        expBtn.setBackground(ebg);
        expBtn.setTextColor(0xFFFFFFFF);
        expBtn.setClickable(true);
        expBtn.setFocusable(true);
        expBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { exportBook(act); } catch (Throwable t) { log("!! exportBook: " + t); }
            }
        });
        expRow.addView(expBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView expHint = new TextView(act);
        expHint.setText(LAST_BOOK_ID == null
                ? "还没捕获到章节 —— 先打开一本书读几页，或点一次「下载」"
                : "已缓存 " + bufCount(LAST_BOOK_ID) + " 章，点上面按钮写出整本（导出后自动与原始 XHTML 校对）");
        expHint.setTextSize(11);
        expHint.setTextColor(0xFF8E8E94);
        expHint.setPadding(0, dp(act, 6), 0, 0);
        expRow.addView(expHint);

        box.addView(divider(act));

        box.addView(formatRow(act, expBtn));

        box.addView(switchRow(act, "自动导出",
                "开 = 边读边导（旧行为）；关 = 只在你点上面按钮时落盘", cfgAuto, new BoolSet() {
                    @Override public void set(boolean v) { cfgAuto = v; saveSwitch(); }
                }));
        box.addView(switchRow(act, "导出插图",
                "把章节内嵌插图另存到「插图/」子目录", cfgImages, new BoolSet() {
                    @Override public void set(boolean v) { cfgImages = v; saveSwitch(); }
                }));
        box.addView(switchRow(act, "保存 XHTML 原文",
                "额外保留番茄原始排版文件（.xhtml）", cfgXhtml, new BoolSet() {
                    @Override public void set(boolean v) { cfgXhtml = v; saveSwitch(); }
                }));
        box.addView(switchRow(act, "强制重新导出",
                "忽略已导出记录，同一章再导一次（用于补文件）", cfgForce, new BoolSet() {
                    @Override public void set(boolean v) { cfgForce = v; saveSwitch(); }
                }));

        box.addView(divider(act));

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(act, 12);
        box.addView(btns, blp);

        btns.addView(button(act, "运行日志", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { showLog(act); } catch (Throwable t) { log("!! showLog: " + t); }
            }
        }));
        btns.addView(button(act, "校对报告", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { showVerify(act); } catch (Throwable t) { log("!! showVerify: " + t); }
            }
        }));
        btns.addView(button(act, "完成", true, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        }));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void showLog(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try { flushLog(true); } catch (Throwable ignore) {}

        String txt = null;
        try {
            byte[] b = readPublic(REL_ROOT, LOG_FILE);
            if (b != null) txt = new String(b, UTF8);
        } catch (Throwable ignore) {}
        if (txt == null || txt.trim().isEmpty()) txt = "(暂无日志)";

        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = newDialogBox(act);

        TextView t = new TextView(act);
        t.setText("运行日志");
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        box.addView(t);

        ScrollView sv = new ScrollView(act);
        TextView body = new TextView(act);
        body.setText(txt);
        body.setTextSize(11);
        body.setTextColor(0xFFCCCCCC);
        body.setTypeface(Typeface.MONOSPACE);
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 400));
        slp.topMargin = dp(act, 10);
        box.addView(sv, slp);

        LinearLayout btns = new LinearLayout(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(act, 12);
        box.addView(btns, blp);
        btns.addView(button(act, "复制到剪贴板", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String s = new String(readPublic(REL_ROOT, LOG_FILE), UTF8);
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("fqhook", s));
                    Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(act, "复制失败: " + t, Toast.LENGTH_SHORT).show();
                }
            }
        }));
        btns.addView(button(act, "关闭", true, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        }));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(dp(act, 340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private LinearLayout newDialogBox(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 20);
        box.setPadding(p, p, p, dp(ctx, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B1F);
        bg.setCornerRadius(dp(ctx, 18));
        box.setBackground(bg);
        return box;
    }

    private View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFF2E2E33);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.6f)));
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 2);
        v.setLayoutParams(lp);
        return v;
    }

    private View switchRow(Context ctx, String title, String sub, boolean init, final BoolSet cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xFFFFFFFF);
        col.addView(t);

        TextView s = new TextView(ctx);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(0xFF8E8E94);
        col.addView(s);

        Switch sw = new Switch(ctx);
        sw.setChecked(init);
        try {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked}, new int[]{}};
            sw.setThumbTintList(new ColorStateList(states,
                    new int[]{0xFFFF5A5F, 0xFF4A4A50}));
            sw.setTrackTintList(new ColorStateList(states,
                    new int[]{0x66FF5A5F, 0x33FFFFFF}));
        } catch (Throwable ignore) {}
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                try { cb.set(v); } catch (Throwable t) { log("!! switch: " + t); }
            }
        });
        row.addView(sw);
        return row;
    }

    /** 导出按钮文案：书名 + 当前格式（切格式时同步刷新） */
    private static String exportBtnText() {
        return "导出本书"
                + (LAST_BOOK_NAME != null ? " · " + LAST_BOOK_NAME : "")
                + "　[" + cfgFormat.toUpperCase(Locale.US) + "]";
    }

    // ── 导出格式：txt / epub 二选一 ──
    private View formatRow(Context ctx, final TextView expBtn) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 12), 0, dp(ctx, 12));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText("导出格式");
        t.setTextSize(15);
        t.setTextColor(0xFFFFFFFF);
        col.addView(t);

        final TextView sub = new TextView(ctx);
        sub.setTextSize(11);
        sub.setTextColor(0xFF8E8E94);
        col.addView(sub);

        final TextView bTxt = segBtn(ctx, "TXT");
        final TextView bEpub = segBtn(ctx, "EPUB");
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.addView(bTxt);
        seg.addView(bEpub);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(ctx, 8);
        row.addView(seg, slp);

        View.OnClickListener l = new View.OnClickListener() {
            @Override public void onClick(View v) {
                cfgFormat = (v == bEpub) ? FMT_EPUB : FMT_TXT;
                paintSeg(bTxt, FMT_TXT.equals(cfgFormat));
                paintSeg(bEpub, FMT_EPUB.equals(cfgFormat));
                sub.setText(FMT_EPUB.equals(cfgFormat)
                        ? "epub：插图嵌正文 + 章节目录，可导入阅读器"
                        : "txt：纯文本，插图处留 ［插图］ 占位");
                if (expBtn != null) expBtn.setText(exportBtnText());
                saveSwitch();
            }
        };
        bTxt.setOnClickListener(l);
        bEpub.setOnClickListener(l);

        boolean epub = FMT_EPUB.equals(cfgFormat);
        paintSeg(bTxt, !epub);
        paintSeg(bEpub, epub);
        sub.setText(epub
                ? "epub：插图嵌正文 + 章节目录，可导入阅读器"
                : "txt：纯文本，插图处留 ［插图］ 占位");
        return row;
    }

    private TextView segBtn(Context ctx, String label) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void paintSeg(TextView b, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(b.getContext(), 8));
        bg.setColor(on ? 0xFFFF5A5F : 0xFF2C2C31);
        b.setBackground(bg);
        b.setTextColor(on ? 0xFFFFFFFF : 0xFF9A9AA0);
    }

    private View button(Context ctx, String text, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11));
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        bg.setColor(primary ? 0xFFFF5A5F : 0xFF2C2C31);
        b.setBackground(bg);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFFD0D0D6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 4);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** 把设置面板里的开关写回 _开关.txt，并立即更新内存（不用重启番茄） */
    private void saveSwitch() {
        try {
            String s = "# 番茄导出 开关（由模块设置界面写入）\n"
                    + "force=" + (cfgForce ? 1 : 0) + "\n"
                    + "xhtml=" + (cfgXhtml ? 1 : 0) + "\n"
                    + "images=" + (cfgImages ? 1 : 0) + "\n"
                    + "auto=" + (cfgAuto ? 1 : 0) + "\n"
                    + "format=" + cfgFormat + "\n";
            writePublic(REL_ROOT, SWITCH_FILE, s.getBytes(UTF8), "text/plain");
            log("设置已保存: format=" + cfgFormat + " auto=" + cfgAuto + " images=" + cfgImages
                    + " xhtml=" + cfgXhtml + " force=" + cfgForce);
            flushLog(true);
        } catch (Throwable t) {
            log("!! saveSwitch: " + t);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  手动导出：把缓冲里这本书的全部章节写成一份 <书名>.txt
    // ═══════════════════════════════════════════════════════════
    private static int bufCount(String bookId) {
        Map<String, ChapterBuf> m = (bookId == null) ? null : BUF.get(bookId);
        return (m == null) ? 0 : m.size();
    }

    private void exportBook(final Activity act) {
        final String bid = LAST_BOOK_ID;
        if (bid == null) {
            toast(act, "还没捕获到章节 —— 先打开一本书读几页，或点一次「下载」");
            return;
        }
        final Map<String, ChapterBuf> m = BUF.get(bid);
        if (m == null || m.isEmpty()) {
            toast(act, "暂无可导出的章节");
            return;
        }
        final String book = (LAST_BOOK_NAME == null) ? UNKNOWN_BOOK : LAST_BOOK_NAME;
        final Prog prog = showProgress(act, "导出《" + book + "》");
        POOL.execute(new Runnable() {
            @Override public void run() {
                String summary;
                try {
                    summary = doExportBook(bid, book, m, prog);
                } catch (Throwable t) {
                    log("!! doExportBook: " + t);
                    summary = "导出失败: " + t;
                }
                if (prog != null) prog.done(summary);
                else toast(act, summary.replace('\n', ' '));
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  逐章解析结果 —— 导出与校对共用同一份解析，避免两套逻辑打架
    // ═══════════════════════════════════════════════════════════
    static final class ChapDoc {
        String title;
        List<Blk> blks;
        int srcChars;      // 原始 XHTML 去标签后的非空白字符数
        int srcBlkTags;    // 原始 XHTML 里 <blk 出现次数
        int srcImgDivs;    // 原始 XHTML 里 image div 数
        int outChars;      // 导出侧正文非空白字符数
        int outImgs;       // 实际嵌出的插图数
    }

    /**
     * 导出整本。返回多行摘要（同时写进日志与校对报告）。
     * 格式由 cfgFormat 决定：txt（.txt + .html）或 epub（图文 + 目录）。
     * prog 可为 null（无进度条）。
     */
    private String doExportBook(String bookId, String book, Map<String, ChapterBuf> m, Prog prog) {
        ready();
        List<ChapterBuf> list = new ArrayList<ChapterBuf>(m.values());
        Collections.sort(list, new Comparator<ChapterBuf>() {
            @Override public int compare(ChapterBuf a, ChapterBuf b) {
                int x = (a.ord < 0) ? Integer.MAX_VALUE : a.ord;
                int y = (b.ord < 0) ? Integer.MAX_VALUE : b.ord;
                if (x != y) return (x < y) ? -1 : 1;
                return String.valueOf(a.title).compareTo(String.valueOf(b.title));
            }
        });

        final boolean epub = FMT_EPUB.equals(cfgFormat);
        String head = "《" + book + "》";
        String stamp = TS.format(new Date());
        String rel = REL_ROOT + "/" + safe(book, 60);
        String base = safe(book, 80);
        int total = list.size();

        // ── 1) 逐章解析（进度 0~55%）──
        if (prog != null) prog.set(0, "解析章节 0/" + total);
        List<ChapDoc> docs = new ArrayList<ChapDoc>(total);
        for (int i = 0; i < total; i++) {
            ChapterBuf c = list.get(i);
            String xml = new String(c.xml, UTF8);
            ChapDoc d = new ChapDoc();
            d.title = (c.title == null || c.title.isEmpty()) ? ("第" + (i + 1) + "章") : c.title;
            d.blks = extractBlocks(xml, bookId, null);
            d.srcChars = rawTextLen(xml);
            d.srcBlkTags = countOf(xml, "<blk");
            d.srcImgDivs = countImgDivs(xml);
            d.outChars = outChars(d.blks);
            docs.add(d);
            if (prog != null) prog.set(55 * (i + 1) / Math.max(1, total),
                    "解析章节 " + (i + 1) + "/" + total);
        }

        // ── 2) 收集插图（统一命名 i0001.jpg，key→文件 去重）──
        LinkedHashMap<String, String> keyToFile = new LinkedHashMap<String, String>();
        LinkedHashMap<String, byte[]> imgData = new LinkedHashMap<String, byte[]>();
        LinkedHashMap<String, String> imgMime = new LinkedHashMap<String, String>();
        if (cfgImages) {
            int n = 0;
            for (int i = 0; i < docs.size(); i++) {
                List<Blk> bs = docs.get(i).blks;
                for (int k = 0; k < bs.size(); k++) {
                    Blk b = bs.get(k);
                    if (b.kind != Blk.IMG || b.img == null || b.imgKey == null) continue;
                    if (keyToFile.containsKey(b.imgKey)) continue;
                    boolean png = isPng(b.img);
                    String fn = String.format(Locale.US, "i%04d", ++n) + (png ? ".png" : ".jpg");
                    keyToFile.put(b.imgKey, fn);
                    imgData.put(fn, b.img);
                    imgMime.put(fn, png ? "image/png" : "image/jpeg");
                }
            }
        }

        // ── 3) 写正文 ──
        int ok = 0, imgOk = 0;
        String summary;
        if (epub) {
            byte[] eb = buildEpub(book, bookId, docs, stamp, keyToFile, imgData, imgMime, prog);
            if (eb == null) {
                summary = "✘ EPUB 打包失败（见运行日志）";
            } else {
                boolean w = writePublic(rel, base + ".epub", eb, "application/epub+zip");
                for (int i = 0; i < docs.size(); i++) {
                    ChapDoc d = docs.get(i);
                    if (d.blks.isEmpty()) continue;
                    ok++;
                    imgOk += d.outImgs;
                }
                summary = (w ? "✔ " : "✘ ") + "导出 EPUB《" + book + "》 " + ok + "/" + total
                        + " 章 · 插图 " + imgOk + " 张 · " + (eb.length / 1024) + " KB";
            }
        } else {
            StringBuilder txt = new StringBuilder(1 << 16);
            txt.append(head).append('\n')
               .append("导出时间 ").append(stamp)
               .append("　共 ").append(total).append(" 章\n")
               .append("──────────────────────────────\n\n");

            StringBuilder h = new StringBuilder(1 << 16);
            h.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n")
             .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
             .append("<title>").append(esc(head)).append("</title>\n<style>\n")
             .append("body{max-width:42em;margin:2em auto;padding:0 1.2em;line-height:1.9;")
             .append("font-family:-apple-system,BlinkMacSystemFont,\"PingFang SC\",\"Microsoft YaHei\",sans-serif;color:#222;}\n")
             .append("h1{font-size:1.5em;}h2{font-size:1.2em;margin:2.2em 0 .8em;padding-bottom:.3em;border-bottom:1px solid #e5e5e5;}\n")
             .append("p{margin:.9em 0;text-indent:2em;}\n")
             .append("img{max-width:100%;height:auto;display:block;margin:1.2em auto;}\n")
             .append(".cap{text-align:center;color:#999;font-size:.88em;text-indent:0;}\n")
             .append(".meta{color:#999;font-size:.85em;text-indent:0;}\n")
             .append("</style>\n</head>\n<body>\n")
             .append("<h1>").append(esc(head)).append("</h1>\n")
             .append("<p class=\"meta\">导出时间 ").append(stamp)
             .append("　共 ").append(total).append(" 章</p>\n");

            for (int i = 0; i < docs.size(); i++) {
                ChapDoc d = docs.get(i);
                if (d.blks.isEmpty()) continue;
                txt.append(d.title).append('\n');
                h.append("<h2>").append(esc(d.title)).append("</h2>\n");
                int imgs = 0;
                for (int k = 0; k < d.blks.size(); k++) {
                    Blk b = d.blks.get(k);
                    if (b.kind == Blk.TEXT) {
                        if (b.text != null && !b.text.isEmpty()) {
                            txt.append(b.text).append('\n');
                            h.append("<p>").append(esc(b.text)).append("</p>\n");
                        }
                    } else if (b.kind == Blk.IMG) {
                        String fn = (b.imgKey == null) ? null : keyToFile.get(b.imgKey);
                        if (fn != null && imgData.containsKey(fn)) {
                            if (writePublic(rel + "/插图", fn, imgData.get(fn), imgMime.get(fn))) {
                                h.append("<img src=\"插图/").append(fn)
                                 .append("\" alt=\"插图\" loading=\"lazy\">\n");
                                imgs++;
                            }
                        }
                        if (b.text != null && !b.text.isEmpty()) {
                            txt.append("［插图］").append(b.text).append('\n');
                            h.append("<p class=\"cap\">").append(esc(b.text)).append("</p>\n");
                        } else {
                            txt.append("［插图］").append('\n');
                        }
                    }
                }
                txt.append('\n');
                d.outImgs = imgs;
                imgOk += imgs;
                ok++;
                if (prog != null) prog.set(75 + 15 * (i + 1) / Math.max(1, docs.size()),
                        "写入正文 " + (i + 1) + "/" + docs.size());
            }
            h.append("</body>\n</html>\n");

            boolean w1 = writePublic(rel, base + ".txt", txt.toString().getBytes(UTF8), "text/plain");
            boolean w2 = writePublic(rel, base + ".html", h.toString().getBytes(UTF8), "text/html");
            summary = (w1 && w2 ? "✔ " : "✘ ") + "导出《" + book + "》 " + ok + "/" + total
                    + " 章 · 插图 " + imgOk + " 张 · txt " + txt.length()
                    + " 字 / html " + h.length() + " 字";
        }

        // ── 4) 与原始排版 XHTML 校对 ──
        if (prog != null) prog.set(94, "与原始 XHTML 校对…");
        String report = verifyReport(book, stamp, epub, docs, summary);
        boolean rv = writePublic(rel, VERIFY_FILE, report.getBytes(UTF8), "text/plain");
        String brief = verifyBrief(docs);
        if (prog != null) prog.set(100, "完成");

        log(summary + " -> " + rel);
        log((rv ? "校对报告已写出: " : "!! 校对报告写出失败: ") + rel + "/" + VERIFY_FILE
                + " | " + brief);
        flushLog(true);
        return summary + "\n\n" + brief;
    }

    // ═══════════════════════════════════════════════════════════
    //  EPUB 打包（纯 Java，EPUB 2.0.1 + NCX 目录）
    //
    //  ⚠ mimetype 必须是 zip 里的**第一个**条目且**不压缩**（STORED），
    //    否则几乎所有阅读器都会判定「不是有效的 EPUB」。这条是硬规范。
    // ═══════════════════════════════════════════════════════════
    private static final String EPUB_CSS =
            "body{line-height:1.75;margin:0 1em;font-family:serif;}\n"
          + "h2{font-size:1.15em;margin:1.4em 0 .8em;padding-bottom:.3em;border-bottom:1px solid #ddd;}\n"
          + "p{margin:.75em 0;text-indent:2em;}\n"
          + ".pic{text-align:center;margin:1.2em 0;}\n"
          + ".pic img{max-width:100%;height:auto;}\n"
          + ".cap{text-align:center;color:#888;font-size:.88em;text-indent:0;margin-top:.3em;}\n";

    private byte[] buildEpub(String book, String bookId, List<ChapDoc> docs, String stamp,
                             Map<String, String> keyToFile, Map<String, byte[]> imgData,
                             Map<String, String> imgMime, Prog prog) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            ZipOutputStream z = new ZipOutputStream(bo);
            z.setLevel(6);

            // 1) mimetype —— STORED + 第一个条目
            byte[] mt = "application/epub+zip".getBytes(UTF8);
            ZipEntry e0 = new ZipEntry("mimetype");
            e0.setMethod(ZipOutputStream.STORED);
            e0.setSize(mt.length);
            CRC32 crc = new CRC32();
            crc.update(mt);
            e0.setCrc(crc.getValue());
            z.putNextEntry(e0);
            z.write(mt);
            z.closeEntry();

            // 2) container.xml
            zipPut(z, "META-INF/container.xml",
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                   + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                   + "  <rootfiles>\n"
                   + "    <rootfile full-path=\"OEBPS/content.opf\""
                   + " media-type=\"application/oebps-package+xml\"/>\n"
                   + "  </rootfiles>\n"
                   + "</container>\n").getBytes(UTF8));

            // 3) 样式
            zipPut(z, "OEBPS/style.css", EPUB_CSS.getBytes(UTF8));

            // 4) 各章 XHTML（严格 XML）+ 目录
            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            StringBuilder nav = new StringBuilder();
            int ok = 0, imgOk = 0, play = 0;

            for (int i = 0; i < docs.size(); i++) {
                ChapDoc d = docs.get(i);
                if (d.blks.isEmpty()) continue;
                String id = String.format(Locale.US, "c%04d", i + 1);
                String href = "text/" + id + ".xhtml";

                StringBuilder x = new StringBuilder(1 << 12);
                x.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                 .append("<!DOCTYPE html>\n")
                 .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh\" lang=\"zh\">\n")
                 .append("<head>\n<meta charset=\"utf-8\"/>\n")
                 .append("<title>").append(esc(d.title)).append("</title>\n")
                 .append("<link rel=\"stylesheet\" type=\"text/css\" href=\"../style.css\"/>\n")
                 .append("</head>\n<body>\n")
                 .append("<h2>").append(esc(d.title)).append("</h2>\n");

                int imgs = 0;
                for (int k = 0; k < d.blks.size(); k++) {
                    Blk b = d.blks.get(k);
                    if (b.kind == Blk.TEXT) {
                        if (b.text != null && !b.text.isEmpty()) {
                            x.append("<p>").append(esc(b.text)).append("</p>\n");
                        }
                    } else if (b.kind == Blk.IMG) {
                        String fn = (b.imgKey == null) ? null : keyToFile.get(b.imgKey);
                        if (fn != null) {
                            x.append("<div class=\"pic\"><img src=\"../images/").append(fn)
                             .append("\" alt=\"插图\"/></div>\n");
                            imgs++;
                        }
                        if (b.text != null && !b.text.isEmpty()) {
                            x.append("<p class=\"cap\">").append(esc(b.text)).append("</p>\n");
                        }
                    }
                }
                x.append("</body>\n</html>\n");
                zipPut(z, "OEBPS/" + href, x.toString().getBytes(UTF8));

                manifest.append("    <item id=\"").append(id).append("\" href=\"").append(href)
                        .append("\" media-type=\"application/xhtml+xml\"/>\n");
                spine.append("    <itemref idref=\"").append(id).append("\"/>\n");
                play++;
                nav.append("    <navPoint id=\"n").append(play)
                   .append("\" playOrder=\"").append(play).append("\">\n")
                   .append("      <navLabel><text>").append(esc(d.title)).append("</text></navLabel>\n")
                   .append("      <content src=\"").append(href).append("\"/>\n")
                   .append("    </navPoint>\n");

                d.outImgs = imgs;
                imgOk += imgs;
                ok++;
                if (prog != null) prog.set(75 + 15 * (i + 1) / Math.max(1, docs.size()),
                        "打包 EPUB " + (i + 1) + "/" + docs.size());
            }

            // 5) 插图
            StringBuilder imanifest = new StringBuilder();
            for (Map.Entry<String, byte[]> en : imgData.entrySet()) {
                String fn = en.getKey();
                zipPut(z, "OEBPS/images/" + fn, en.getValue());
                imanifest.append("    <item id=\"").append(fn.replace('.', '_'))
                        .append("\" href=\"images/").append(fn)
                        .append("\" media-type=\"").append(imgMime.get(fn)).append("\"/>\n");
            }

            // 6) content.opf
            String uuid = "urn:uuid:" + UUID.nameUUIDFromBytes(
                    ("fq:" + bookId).getBytes(UTF8)).toString();
            StringBuilder opf = new StringBuilder(1 << 12);
            opf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
               .append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\"")
               .append(" unique-identifier=\"bookid\">\n")
               .append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
               .append(" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n")
               .append("    <dc:title>").append(esc(book)).append("</dc:title>\n")
               .append("    <dc:language>zh</dc:language>\n")
               .append("    <dc:identifier id=\"bookid\">").append(uuid).append("</dc:identifier>\n")
               .append("    <dc:creator>番茄小说</dc:creator>\n")
               .append("    <dc:date>").append(stamp).append("</dc:date>\n")
               .append("    <meta name=\"fq:chapters\" content=\"").append(ok).append("\"/>\n")
               .append("    <meta name=\"fq:images\" content=\"").append(imgOk).append("\"/>\n")
               .append("  </metadata>\n")
               .append("  <manifest>\n")
               .append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
               .append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
               .append(manifest)
               .append(imanifest)
               .append("  </manifest>\n")
               .append("  <spine toc=\"ncx\">\n")
               .append(spine)
               .append("  </spine>\n")
               .append("</package>\n");
            zipPut(z, "OEBPS/content.opf", opf.toString().getBytes(UTF8));

            // 7) toc.ncx（章节目录）
            zipPut(z, "OEBPS/toc.ncx",
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                   + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                   + "  <head>\n"
                   + "    <meta name=\"dtb:uid\" content=\"" + uuid + "\"/>\n"
                   + "    <meta name=\"dtb:depth\" content=\"1\"/>\n"
                   + "    <meta name=\"dtb:totalPageCount\" content=\"0\"/>\n"
                   + "    <meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n"
                   + "  </head>\n"
                   + "  <docTitle><text>" + esc(book) + "</text></docTitle>\n"
                   + "  <navMap>\n" + nav + "  </navMap>\n"
                   + "</ncx>\n").getBytes(UTF8));

            z.finish();
            z.close();
            return bo.toByteArray();
        } catch (Throwable t) {
            log("!! buildEpub: " + t);
            return null;
        }
    }

    private static void zipPut(ZipOutputStream z, String name, byte[] data) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipOutputStream.DEFLATED);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    // ═══════════════════════════════════════════════════════════
    //  校对：导出结果 vs 原始排版 XHTML
    //
    //  两边**独立计算**：源侧直接把 XHTML 去标签后数非空白字符；
    //  导出侧数最终落盘内容。所以这不是「自己跟自己比」，
    //  能真实暴露解析中断、块丢失、图注被吞等问题。
    // ═══════════════════════════════════════════════════════════
    /** 原始 XHTML 去标签后的非空白字符数（源侧） */
    private static int rawTextLen(String xml) {
        if (xml == null) return 0;
        String t = P_TAG.matcher(xml).replaceAll("");
        t = unescape(t);
        int n = 0;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isWhitespace(t.charAt(i))) n++;
        }
        return n;
    }

    /** 导出侧正文非空白字符数（含图注） */
    private static int outChars(List<Blk> blks) {
        int n = 0;
        for (int i = 0; i < blks.size(); i++) {
            Blk b = blks.get(i);
            if (b.text == null) continue;
            for (int k = 0; k < b.text.length(); k++) {
                if (!Character.isWhitespace(b.text.charAt(k))) n++;
            }
        }
        return n;
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** 原始 XHTML 里 image div 数 */
    private static int countImgDivs(String xml) {
        int n = 0, i = 0;
        while (true) {
            int d = xml.indexOf("<div", i);
            if (d < 0) break;
            int te = xml.indexOf('>', d);
            if (te < 0) break;
            if (xml.substring(d, te + 1).indexOf("image") >= 0) n++;
            i = te + 1;
        }
        return n;
    }

    /** 校对统计（章节/字数/图） */
    private static int[] verifyStats(List<ChapDoc> docs) {
        int chap = 0, src = 0, out = 0, si = 0, oi = 0, bad = 0;
        for (int i = 0; i < docs.size(); i++) {
            ChapDoc d = docs.get(i);
            if (d.blks.isEmpty()) continue;
            chap++;
            src += d.srcChars;
            out += d.outChars;
            si += d.srcImgDivs;
            oi += d.outImgs;
            if (d.outChars != d.srcChars || d.outImgs < d.srcImgDivs) bad++;
        }
        return new int[]{chap, src, out, si, oi, bad};
    }

    private static String verifyBrief(List<ChapDoc> docs) {
        int[] s = verifyStats(docs);
        String verdict = (s[5] == 0)
                ? "✔ 与原始 XHTML 逐字一致"
                : "✘ " + s[5] + " 章存在差异";
        return "校对：" + verdict + "（章节 " + s[0] + " · 正文 "
                + String.format(Locale.US, "%,d", s[2]) + "/"
                + String.format(Locale.US, "%,d", s[1]) + " 字 · 插图 " + s[4] + "/" + s[3] + "）";
    }

    /** 完整校对报告（写成 _校对报告.txt） */
    private String verifyReport(String book, String stamp, boolean epub,
                                List<ChapDoc> docs, String summary) {
        int[] s = verifyStats(docs);
        StringBuilder r = new StringBuilder(1 << 12);
        r.append("《").append(book).append("》导出校对报告\n");
        r.append("时间 ").append(stamp).append('\n');
        r.append("格式 ").append(epub ? "EPUB" : "TXT").append('\n');
        r.append("──────────────────────────────\n");
        r.append("导出结果  ").append(summary.replace('\n', ' ')).append('\n');
        r.append("章节      ").append(s[0]).append(" / ").append(docs.size()).append('\n');
        r.append("正文      ").append(String.format(Locale.US, "%,d", s[2]))
         .append(" 字  |  原始 XHTML ").append(String.format(Locale.US, "%,d", s[1]))
         .append(" 字  |  偏差 ").append(String.format(Locale.US, "%+,d", s[2] - s[1])).append('\n');
        r.append("插图      ").append(s[4]).append(" / ").append(s[3]).append(" 张\n");
        r.append("──────────────────────────────\n");
        r.append("结论      ").append(s[5] == 0
                ? "✔ 与原始排版一致，未发现丢字/丢块"
                : "✘ " + s[5] + " 章存在差异（明细见下）").append('\n');

        if (s[5] > 0) {
            r.append("\n逐章差异：\n");
            r.append("  序号   章节                          字数(导出/原始)  插图(导出/原始)\n");
            for (int i = 0; i < docs.size(); i++) {
                ChapDoc d = docs.get(i);
                if (d.blks.isEmpty()) continue;
                if (d.outChars == d.srcChars && d.outImgs >= d.srcImgDivs) continue;
                r.append(String.format(Locale.US, "  %-6s %-28s %6d/%-6d   %2d/%2d\n",
                        String.format(Locale.US, "%04d", i + 1),
                        cut(d.title, 28), d.outChars, d.srcChars, d.outImgs, d.srcImgDivs));
            }
        }

        // 空章节单独列出（解析为空的，最可能是 hook 没覆盖到）
        int empty = 0;
        StringBuilder em = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            if (!docs.get(i).blks.isEmpty()) continue;
            empty++;
            if (empty <= 20) {
                em.append("  ").append(String.format(Locale.US, "%04d", i + 1))
                  .append(' ').append(cut(docs.get(i).title, 28)).append('\n');
            }
        }
        if (empty > 0) {
            r.append("\n解析为空的章节 ").append(empty).append(" 章")
             .append(empty > 20 ? "（只列前 20）" : "").append("：\n").append(em);
        }
        r.append("\n注：字数按「非空白字符」计，图注计入正文；偏差 0 表示逐字对齐。\n");
        return r.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return (s.length() <= max) ? s : s.substring(0, max);
    }

    private void showVerify(final Activity act) {
        if (act == null || act.isFinishing()) return;
        if (LAST_BOOK_NAME == null) {
            toast(act, "还没有导出记录");
            return;
        }
        byte[] b = readPublic(REL_ROOT + "/" + safe(LAST_BOOK_NAME, 60), VERIFY_FILE);
        String txt = (b == null) ? null : new String(b, UTF8);
        if (txt == null || txt.trim().isEmpty()) txt = "(暂无校对报告 —— 先点「导出本书」)";

        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = newDialogBox(act);

        TextView t = new TextView(act);
        t.setText("校对报告");
        t.setTextSize(16);
        t.setTextColor(0xFFFFFFFF);
        box.addView(t);

        ScrollView sv = new ScrollView(act);
        TextView body = new TextView(act);
        body.setText(txt);
        body.setTextSize(11);
        body.setTextColor(0xFFCCCCCC);
        body.setTypeface(Typeface.MONOSPACE);
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 400));
        slp.topMargin = dp(act, 10);
        box.addView(sv, slp);

        LinearLayout btns = new LinearLayout(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(act, 12);
        box.addView(btns, blp);
        btns.addView(button(act, "关闭", true, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        }));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(dp(act, 340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    // ═══════════════════════════════════════════════════════════
    //  导出进度弹窗
    // ═══════════════════════════════════════════════════════════
    static final class Prog {
        final Activity act;
        final Dialog d;
        final TextView label;
        final ProgressBar bar;
        final TextView close;
        Prog(Activity a, Dialog dd, TextView l, ProgressBar b, TextView c) {
            act = a; d = dd; label = l; bar = b; close = c;
        }
        /** 从任意线程更新进度 */
        void set(final int pct, final String msg) {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        bar.setProgress(pct < 0 ? 0 : (pct > 100 ? 100 : pct));
                        label.setText(msg);
                    } catch (Throwable ignore) {}
                }
            });
        }
        /** 结束：进度满、可关闭 */
        void done(final String msg) {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        bar.setProgress(100);
                        label.setText(msg);
                        close.setText("完成");
                        close.setTextColor(0xFFFFFFFF);
                        GradientDrawable bg = new GradientDrawable();
                        bg.setCornerRadius(dp(act, 10));
                        bg.setColor(0xFFFF5A5F);
                        close.setBackground(bg);
                        close.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { d.dismiss(); }
                        });
                    } catch (Throwable ignore) {}
                }
            });
        }
    }

    private Prog showProgress(Activity act, String title) {
        if (act == null || act.isFinishing()) return null;
        try {
            final Dialog d = new Dialog(act);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCancelable(false);
            LinearLayout box = newDialogBox(act);

            TextView t = new TextView(act);
            t.setText(title);
            t.setTextSize(16);
            t.setTextColor(0xFFFFFFFF);
            box.addView(t);

            ProgressBar bar = new ProgressBar(act, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            try {
                bar.setProgressTintList(ColorStateList.valueOf(0xFFFF5A5F));
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2E2E33));
            } catch (Throwable ignore) {}
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 8));
            blp.topMargin = dp(act, 16);
            box.addView(bar, blp);

            TextView label = new TextView(act);
            label.setText("准备中…");
            label.setTextSize(12);
            label.setTextColor(0xFFB8B8BE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(act, 10);
            box.addView(label, llp);

            TextView close = new TextView(act);
            close.setText("导出中…");
            close.setTextSize(14);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(act, 14), dp(act, 11), dp(act, 14), dp(act, 11));
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(dp(act, 10));
            cbg.setColor(0xFF2C2C31);
            close.setBackground(cbg);
            close.setTextColor(0xFF9A9AA0);
            close.setClickable(false);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(act, 16);
            box.addView(close, clp);

            d.setContentView(box);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setLayout(dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.show();
            return new Prog(act, d, label, bar, close);
        } catch (Throwable t) {
            log("!! showProgress: " + t);
            return null;
        }
    }

    /** HTML 转义 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static void toast(final Activity a, final String s) {
        if (a == null) return;
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignore) {}
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
