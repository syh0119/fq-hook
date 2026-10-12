import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * fq-hook 离线自测 —— 直接反射调用 MainHook 里的纯静态逻辑，不需要设备。
 *
 * 覆盖本轮改动：
 *   1. esc()            —— XML 非法字符过滤 / 代理对处理 / 转义
 *   2. countIllegalXml()—— 脏字符计数
 *   3. epubSelfCheck()  —— EPUB 结构自检（正常件 / mimetype 被压缩件 / 缺条目件 / 引用悬空件）
 *
 * 用法：
 *   javac -encoding UTF-8 -d out FqSelfTest.java
 *   java -cp "out;<android.jar>;<api-82.jar>;<MainHook.class 所在目录>" FqSelfTest
 */
public class FqSelfTest {

    static Class<?> C;
    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        C = Class.forName("com.syh.fqhook.MainHook");
        System.out.println("加载 MainHook OK: " + C.getName());

        // ── esc() ──
        eq("esc 控制字符", esc("a\u0001b"), "a\uFFFDb");
        eq("esc 转义 5 字符", esc("<&>\"'"), "&lt;&amp;&gt;&quot;&#39;");
        eq("esc 保留 \\t\\n\\r", esc("a\tb\nc\rd"), "a\tb\nc\rd");
        eq("esc 保留 emoji 代理对", esc("hi\uD83C\uDF45"), "hi\uD83C\uDF45");
        eq("esc 孤立高位代理", esc("x\uD83Cy"), "x\uFFFDy");
        eq("esc 0x0B/0x0C", esc("p\u000Bq\u000C"), "p\uFFFDq\uFFFD");
        eq("esc 正常中文原样", esc("第一章 风起"), "第一章 风起");

        // ── countIllegalXml() ──
        eq("计数 2 个脏字符", String.valueOf(illegal("a\u0000b\u001Fc")), "2");
        eq("计数 代理对不算脏", String.valueOf(illegal("a\uD83C\uDF45b")), "0");
        eq("计数 孤立代理算脏", String.valueOf(illegal("a\uD83Cb")), "1");
        eq("计数 干净文本", String.valueOf(illegal("干净文本")), "0");

        // ── epubSelfCheck()：正常件 ──
        byte[] good = epub(false, false, false);
        String r1 = check(good, 2, 1);
        ok("自检通过正常 EPUB", r1.startsWith("✔"), r1);

        // ── 异常件 A：mimetype 被 DEFLATE 压缩（最常见的「不是有效 EPUB」）──
        byte[] deflated = epub(true, false, false);
        String r2 = check(deflated, 2, 1);
        ok("抓到 mimetype 未 STORED", r2.startsWith("✘") && r2.contains("STORED"), r2);

        // ── 异常件 B：缺 toc.ncx ──
        byte[] noNcx = epub(false, true, false);
        String r3 = check(noNcx, 2, 1);
        ok("抓到缺条目", r3.startsWith("✘") && r3.contains("toc.ncx"), r3);

        // ── 异常件 C：manifest 引用了 zip 里不存在的文件 ──
        byte[] dangling = epub(false, false, true);
        String r4 = check(dangling, 2, 1);
        ok("抓到 manifest 悬空引用", r4.startsWith("✘") && r4.contains("不存在的条目"), r4);

        // ── 异常件 D：章数与声明不符 ──
        String r5 = check(good, 99, 1);
        ok("抓到章数不符", r5.startsWith("✘") && r5.contains("章节文件数"), r5);

        // ── 异常件 E：非 zip 字节流 ──
        String r6 = check("not a zip at all".getBytes("UTF-8"), 2, 1);
        ok("抓到非 zip", r6.startsWith("✘"), r6);

        // ── 校对三态（verdictOf / verifyStats）—— P0-1 / P0-2 的核心 ──
        java.util.List<Object> normal = new java.util.ArrayList<Object>();
        normal.add(doc("第1章 起", 100, 100, 0, 0, false, 0));
        String v1 = verdict(normal);
        ok("正常章 -> ✔", v1.startsWith("✔"), v1);

        // 降级章：字数与源侧「虚假相等」，改之前会报 ✔ 一致
        java.util.List<Object> deg = new java.util.ArrayList<Object>();
        deg.add(doc("第1章 起", 100, 100, 0, 0, true, 0));
        String v2 = verdict(deg);
        ok("降级章 -> ✘ 且不得为一致", v2.startsWith("✘") && v2.contains("兜底"), v2);
        String b2 = brief(deg);
        ok("降级章 brief 不含 ✔", !b2.contains("✔"), b2);

        // 空章：捕获到了但解析不出正文
        java.util.List<Object> empty = new java.util.ArrayList<Object>();
        empty.add(doc("第1章 起", 100, 0, 0, 0, false, 0));
        setField(empty.get(0), "blks", new java.util.ArrayList<Object>());
        setField(empty.get(0), "outChars", 0);
        String v3 = verdict(empty);
        ok("空章 -> ✘ 且提示缺章", v3.startsWith("✘") && v3.contains("解析不出正文"), v3);

        // 字数偏差
        java.util.List<Object> diff = new java.util.ArrayList<Object>();
        diff.add(doc("第1章 起", 100, 98, 0, 0, false, 0));
        String v4 = verdict(diff);
        ok("字数偏差 -> ✘ 存在差异", v4.startsWith("✘") && v4.contains("存在差异"), v4);

        // 脏字符计数进入统计
        java.util.List<Object> dirty = new java.util.ArrayList<Object>();
        dirty.add(doc("第1章 起", 100, 100, 0, 0, false, 3));
        int[] st = stats(dirty);
        ok("verifyStats 第[8]位 = 脏字符数", st[8] == 3, "stats[8]=" + st[8]);
        ok("有脏字符但仍判一致(仅提示)", verdict(dirty).startsWith("✔"), verdict(dirty));

        // ───────── v1.7.0 书籍台账（多本可选导出） ─────────
        clearBuf();
        putBook("bk_old", "《旧书》", "第一章", 1000L);
        putBook("bk_new", "《新书》", "第三章", 2000L);
        putBook("bk_mid", "《中间》", "第五章", 1500L);

        java.util.List<Object> byRecent = bookIdsByRecent();
        eq("列表长度 = 捕获过的书数", String.valueOf(byRecent.size()), "3");
        eq("最近捕获的排最前", String.valueOf(byRecent.get(0)), "bk_new");
        eq("次新排第二", String.valueOf(byRecent.get(1)), "bk_mid");
        eq("最旧排最后", String.valueOf(byRecent.get(2)), "bk_old");

        // 各本书的章节数独立统计（这正是「只能导最后一本书」的病根）
        putBook("bk_new", "《新书》", "第四章", 2500L);
        eq("新书 2 章", String.valueOf(bufCount("bk_new")), "2");
        eq("旧书仍 1 章", String.valueOf(bufCount("bk_old")), "1");
        eq("中间 1 章", String.valueOf(bufCount("bk_mid")), "1");
        eq("台账不因再捕获而增长", String.valueOf(bookIdsByRecent().size()), "3");

        eq("nameOf 取台账书名", nameOf("bk_new"), "《新书》");
        ok("nameOf 未登记回退", nameOf("nope").startsWith("book_"), nameOf("nope"));

        // 同名不同 id 不能互相覆盖（台账必须按 id 而非书名归并）
        putBook("bk_dup", "《新书》", "第一章", 3000L);
        eq("同名不同 id 各自独立", String.valueOf(bufCount("bk_dup")), "1");
        eq("同名后原书章数不变", String.valueOf(bufCount("bk_new")), "2");

        // 未捕获过的书不能进列表
        eq("bufCount 未捕获 = 0", String.valueOf(bufCount("never_seen")), "0");

        clearBuf();
        eq("clear 后列表空", String.valueOf(bookIdsByRecent().size()), "0");

        // ───────── v1.10.0 多选集合（可一次导多本） ─────────
        clearBuf();
        putBook("bk_a", "甲", "第一章", 1000L);
        putBook("bk_b", "乙", "第二章", 2000L);
        putBook("bk_c", "丙", "第三章", 3000L);

        sel().clear();
        eq("初始无选中", String.valueOf(sel().size()), "0");

        // 点第一本 -> 选中 1 本；再点第二本 -> **不能**把第一本顶掉
        eq("翻选甲", String.valueOf(toggle("bk_a")), "1");
        eq("翻选乙后仍 2 本", String.valueOf(toggle("bk_b")), "2");
        eq("集合含甲", String.valueOf(sel().contains("bk_a")), "true");
        eq("集合含乙", String.valueOf(sel().contains("bk_b")), "true");
        eq("丙未被连带选中", String.valueOf(sel().contains("bk_c")), "false");

        // 再点已选中的 -> 取消
        eq("再点乙取消", String.valueOf(toggle("bk_b")), "1");
        eq("取消后只剩甲", String.valueOf(sel().contains("bk_a")), "true");
        eq("乙已移除", String.valueOf(sel().contains("bk_b")), "false");
        eq("重复点回来仍是 2 本", String.valueOf(toggle("bk_b")), "2");

        // 按钮文案：单本显示书名，多本显示本数+总章数
        String l1 = exportLabel(selOnly("bk_a"));
        ok("单本按钮带书名", l1.contains("《甲》") && l1.contains("1 章"), l1);
        String l2 = exportLabel(selOnly("bk_a", "bk_b"));
        ok("多本按钮显示本数与总章数", l2.contains("2 本") && l2.contains("2 章"), l2);

        // 列表标签：单选显示书名，多选显示本数 + 总章数
        selOnly("bk_a");
        String t1 = label(idsArg("bk_a"));
        ok("列表标签单选摘要", t1.contains("已选《甲》"), t1);
        sel().clear();
        sel().add("bk_a");
        sel().add("bk_b");
        sel().add("bk_c");
        String t2 = label(idsArg("bk_a", "bk_b", "bk_c"));
        ok("列表标签多选摘要", t2.contains("已选 3 本") && t2.contains("3 章"), t2);
        sel().clear();
        String t4 = label(idsArg("bk_a", "bk_b", "bk_c"));
        ok("清空后标签回到引导语", t4.contains("先勾选"), t4);
        sel().clear();
        sel().add("bk_a");
        sel().add("bk_b");
        sel().add("bk_c");
        String h2 = hint();
        ok("多本提示说明逐本进行", h2.contains("3 本") && h2.contains("逐本"), h2);
        sel().clear();
        String h0 = hint();
        ok("无选中提示引导勾选", h0.contains("勾选"), h0);

        // 批量todo：按列表顺序（最近在前）过滤已勾选且有章节的
        // 传真实排序结果，而不是随手排的 id 列表 —— 排序是bookIdsByRecent 的职责
        sel().clear();
        sel().add("bk_a");
        sel().add("bk_c");
        java.util.List<Object> recent = bookIdsByRecent();
        java.util.List<Object> todo = exportOrder(recent);
        eq("todo 长度 = 已勾选本数", String.valueOf(todo.size()), "2");
        eq("todo 按最近捕获排序（丙在前）", String.valueOf(todo.get(0)), "bk_c");
        eq("todo 次为甲", String.valueOf(todo.get(1)), "bk_a");

        // 勾了一本没章节的书 -> 被过滤掉
        sel().clear();
        sel().add("bk_ghost");
        eq("勾选无章节的书不算", String.valueOf(exportOrder(idsArg("bk_a")).size()), "0");

        // 面板打开期间台账被清（番茄重启）-> 选中项要收敛回真实存在的书
        sel().clear();
        sel().add("bk_a");
        sel().add("bk_gone");
        sel().retainAll(idsArg("bk_a", "bk_b"));
        eq("retainAll 剔除已消失的书", String.valueOf(sel().contains("bk_gone")), "false");
        eq("retainAll 保留仍存在的", String.valueOf(sel().contains("bk_a")), "true");
        sel().clear();

        clearBuf();
        eq("clear 后列表空", String.valueOf(bookIdsByRecent().size()), "0");

        System.out.println("\n==== 通过 " + pass + " / 失败 " + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    // ───────── 书籍台账反射辅助 ─────────
    static void putBook(String bookId, String bookName, String chapName, long seen)
            throws Exception {
        Object info = newInfo(bookId, bookName, "cp_" + bookId + "_" + chapName, chapName);
        bufPut(info, ("<html><body><blk>" + chapName + "</blk></body></html>")
                .getBytes("UTF-8"));
        // 覆盖 lastSeen，构造确定的排序（真实并发写序不可预测）
        seenMap().put(bookId, Long.valueOf(seen));
    }

    @SuppressWarnings("unchecked")
    static java.util.Map<String, Object> seenMap() throws Exception {
        java.lang.reflect.Field f = C.getDeclaredField("BOOK_SEEN");
        f.setAccessible(true);
        return (java.util.Map<String, Object>) f.get(null);
    }

    static void clearBuf() throws Exception {
        java.util.Map<Object, Object> b = bufMap();
        b.clear();
        seenMap().clear();
        nameMap().clear();
        java.lang.reflect.Field last = C.getDeclaredField("LAST_BOOK_ID");
        last.setAccessible(true);
        last.set(null, null);
    }

    @SuppressWarnings("unchecked")
    static java.util.Map<Object, Object> bufMap() throws Exception {
        java.lang.reflect.Field f = C.getDeclaredField("BUF");
        f.setAccessible(true);
        return (java.util.Map<Object, Object>) f.get(null);
    }

    @SuppressWarnings("unchecked")
    static java.util.Map<String, Object> nameMap() throws Exception {
        java.lang.reflect.Field f = C.getDeclaredField("BOOK_NAME");
        f.setAccessible(true);
        return (java.util.Map<String, Object>) f.get(null);
    }

    static Object newInfo(String bookId, String bookName, String chapId, String chapName)
            throws Exception {
        Class<?> ci = Class.forName("com.syh.fqhook.MainHook$Info");
        java.lang.reflect.Constructor<?> c = ci.getDeclaredConstructor();
        c.setAccessible(true);
        Object o = c.newInstance();
        setField(o, "bookId", bookId);
        setField(o, "bookName", bookName);
        setField(o, "chapterId", chapId);
        setField(o, "chapterName", chapName);
        return o;
    }

    // ───────── 校对相关反射辅助 ─────────
    static Class<?> CD;
    static Class<?> BLK;

    static Object doc(String title, int srcChars, int outChars, int srcImg, int outImg,
                      boolean degraded, int illegal) throws Exception {
        if (CD == null) {
            CD = Class.forName("com.syh.fqhook.MainHook$ChapDoc");
            BLK = Class.forName("com.syh.fqhook.MainHook$Blk");
        }
        java.lang.reflect.Constructor<?> c = CD.getDeclaredConstructor();
        c.setAccessible(true);
        Object d = c.newInstance();
        java.util.List<Object> blks = new java.util.ArrayList<Object>();
        if (outChars > 0) {
            java.lang.reflect.Constructor<?> bc = BLK.getDeclaredConstructor();
            bc.setAccessible(true);
            Object b = bc.newInstance();
            setField(b, "kind", 0);
            setField(b, "text", "这是一段正文");
            blks.add(b);
        }
        setField(d, "title", title);
        setField(d, "blks", blks);
        setField(d, "srcChars", srcChars);
        setField(d, "outChars", outChars);
        setField(d, "srcImgDivs", srcImg);
        setField(d, "outImgs", outImg);
        setField(d, "degraded", Boolean.valueOf(degraded));
        setField(d, "illegalXml", illegal);
        return d;
    }

    static void setField(Object o, String name, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static String verdict(java.util.List<Object> docs) throws Exception {
        int[] s = stats(docs);
        Method m = C.getDeclaredMethod("verdictOf", int[].class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s, docs.size());
    }

    static String brief(java.util.List<Object> docs) throws Exception {
        Method m = C.getDeclaredMethod("verifyBrief", java.util.List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, docs);
    }

    static int[] stats(java.util.List<Object> docs) throws Exception {
        Method m = C.getDeclaredMethod("verifyStats", java.util.List.class);
        m.setAccessible(true);
        return (int[]) m.invoke(null, docs);
    }

    // ───────── 调用被测私有静态方法 ─────────
    static String esc(String s) throws Exception {
        Method m = C.getDeclaredMethod("esc", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    static int illegal(String s) throws Exception {
        Method m = C.getDeclaredMethod("countIllegalXml", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, s);
    }

    static String check(byte[] data, int ch, int img) throws Exception {
        Method m = C.getDeclaredMethod("epubSelfCheck", byte[].class, int.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, data, ch, img);
    }

    // ───────── 构造测试用 EPUB ─────────
    /**
     * @param deflateMimetype 把 mimetype 用 DEFLATE（违反规范）
     * @param dropNcx         不写 toc.ncx
     * @param danglingRef     manifest 里引用一个不存在的 OEBPS/gone.xhtml
     */
    static byte[] epub(boolean deflateMimetype, boolean dropNcx, boolean danglingRef)
            throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        byte[] mt = "application/epub+zip".getBytes("UTF-8");
        ZipEntry e0 = new ZipEntry("mimetype");
        if (deflateMimetype) {
            e0.setMethod(ZipOutputStream.DEFLATED);
        } else {
            e0.setMethod(ZipOutputStream.STORED);
            e0.setSize(mt.length);
            CRC32 crc = new CRC32();
            crc.update(mt);
            e0.setCrc(crc.getValue());
        }
        z.putNextEntry(e0);
        z.write(mt);
        z.closeEntry();

        put(z, "META-INF/container.xml", "<container/>");
        put(z, "OEBPS/style.css", "body{}");
        put(z, "OEBPS/text/c0001.xhtml", "<html/>");
        put(z, "OEBPS/text/c0002.xhtml", "<html/>");
        put(z, "OEBPS/images/i0001.jpg", new byte[]{1, 2, 3});
        if (!dropNcx) put(z, "OEBPS/toc.ncx", "<ncx/>");

        StringBuilder opf = new StringBuilder("<?xml version=\"1.0\"?><package><manifest>");
        opf.append("<item href=\"style.css\" media-type=\"text/css\"/>");
        opf.append("<item href=\"text/c0001.xhtml\"/>");
        opf.append("<item href=\"text/c0002.xhtml\"/>");
        opf.append("<item href=\"images/i0001.jpg\"/>");
        if (!dropNcx) opf.append("<item href=\"toc.ncx\"/>");
        if (danglingRef) opf.append("<item href=\"text/gone.xhtml\"/>");
        opf.append("</manifest></package>");
        // 注意：缺 toc.ncx 时必须让 opf 也不引用它，否则先报「悬空引用」，测不到「缺条目」
        String opfText = dropNcx
                ? opf.toString().replace("<item href=\"toc.ncx\"/>", "")
                : opf.toString();
        put(z, "OEBPS/content.opf", opfText);

        z.finish();
        z.close();
        return bo.toByteArray();
    }

    static void put(ZipOutputStream z, String name, String s) throws Exception {
        put(z, name, s.getBytes("UTF-8"));
    }

    static void put(ZipOutputStream z, String name, byte[] b) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipOutputStream.DEFLATED);
        z.putNextEntry(e);
        z.write(b);
        z.closeEntry();
    }

    // ───────── 台账相关私有静态方法 ─────────
    @SuppressWarnings("unchecked")
    static java.util.Set<String> sel() throws Exception {
        java.lang.reflect.Field f = C.getDeclaredField("SEL_BOOKS");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    /** 复刻 MainHook.toggleBook 的翻转语义（面板代码要 Activity，自检跑不了 UI） */
    static int toggle(String bookId) throws Exception {
        return sel().remove(bookId) ? sel().size() : (sel().add(bookId) ? sel().size() : -1);
    }

    static java.util.List<Object> idsArg(String... ids) {
        return new java.util.ArrayList<Object>(java.util.Arrays.asList((Object[]) ids));
    }

    /** 只勾选给定这些（其它先清空），返回该集合本身 */
    static java.util.Set<String> selOnly(String... ids) throws Exception {
        sel().clear();
        sel().addAll(java.util.Arrays.asList(ids));
        return sel();
    }

    static String exportLabel(java.util.Set<String> s) throws Exception {
        Method m = C.getDeclaredMethod("exportLabelText");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    static String label(java.util.List<Object> ids) throws Exception {
        Method m = C.getDeclaredMethod("listLabelText", java.util.List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, ids);
    }

    static String hint() throws Exception {
        Method m = C.getDeclaredMethod("exportHintText");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    /** 复刻 exportSelected 的 todo 构造：按列表顺序留下已勾选且有章节的 */
    static java.util.List<Object> exportOrder(java.util.List<Object> ids) throws Exception {
        java.util.List<Object> out = new java.util.ArrayList<Object>();
        for (Object id : ids) {
            String s = (String) id;
            if (sel().contains(s) && bufCount(s) > 0) out.add(s);
        }
        return out;
    }

    static int bufPut(Object info, byte[] xml) throws Exception {
        Method m = C.getDeclaredMethod("bufPut", ci(), byte[].class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, info, xml);
    }

    static Class<?> ci() {
        try { return Class.forName("com.syh.fqhook.MainHook$Info"); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static int bufCount(String bookId) throws Exception {
        Method m = C.getDeclaredMethod("bufCount", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, bookId);
    }

    @SuppressWarnings("unchecked")
    static java.util.List<Object> bookIdsByRecent() throws Exception {
        Method m = C.getDeclaredMethod("bookIdsByRecent");
        m.setAccessible(true);
        return (java.util.List<Object>) m.invoke(null);
    }

    static String nameOf(String bookId) throws Exception {
        Method m = C.getDeclaredMethod("nameOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, bookId);
    }

    // ───────── 断言 ─────────
    static void eq(String name, String got, String want) {
        boolean ok = got.equals(want);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name
                + (ok ? "" : "\n         期望: " + vis(want) + "\n         实际: " + vis(got)));
    }

    static void ok(String name, boolean cond, String detail) {
        if (cond) pass++; else fail++;
        System.out.println((cond ? "  [OK]   " : "  [FAIL] ") + name + "\n         " + detail);
    }

    static String vis(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0xD800 && c <= 0xDFFF)) {
                b.append(String.format("\\u%04X", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
