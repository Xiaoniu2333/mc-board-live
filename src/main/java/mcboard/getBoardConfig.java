package mcboard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【功能二】读取 Board.xaml,识别其中的 %player_count% 变量,并提供渲染能力。
 *
 * <p>用法:
 * <pre>
 *   getBoardConfig board = new getBoardConfig(new File("Board.xaml"));
 *   board.getContent();               // 原始内容
 *   board.containsPlaceholder();      // 是否含 %player_count%
 *   board.render(42);                 // 把 %player_count% 换成 42
 * </pre>
 *
 * <p>文件被修改后会自动重新加载(可在 config.yml 里关闭)。
 */
public class getBoardConfig {

    public static final String DEFAULT_PLACEHOLDER = "%player_count%";

    /** 形如 %player_count% / %player_max% 的变量。 */
    private static final Pattern VARIABLE = Pattern.compile("%[A-Za-z0-9_]+%");

    private final File file;
    private final Charset forcedCharset;
    private final String placeholder;
    private final boolean extras;
    private final boolean reloadOnChange;

    private String content = "";
    private Charset charset = StandardCharsets.UTF_8;
    private long lastModified = -1L;
    private long loadedAt = 0L;

    public getBoardConfig(File file) {
        this(file, null, DEFAULT_PLACEHOLDER, true, true);
    }

    /**
     * @param file           Board.xaml 路径
     * @param forcedCharset  null 表示自动识别
     * @param placeholder    人数占位符,默认 %player_count%
     * @param extras         是否同时替换 %player_max% 等扩展变量
     * @param reloadOnChange 文件变化后是否自动重载
     */
    public getBoardConfig(File file, Charset forcedCharset, String placeholder,
                          boolean extras, boolean reloadOnChange) {
        if (file == null) {
            throw new IllegalArgumentException("Board 文件路径不能为空");
        }
        this.file = file.getAbsoluteFile();
        this.forcedCharset = forcedCharset;
        this.placeholder = (placeholder == null || placeholder.isEmpty()) ? DEFAULT_PLACEHOLDER : placeholder;
        this.extras = extras;
        this.reloadOnChange = reloadOnChange;
    }

    public File getFile() {
        return file;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public Charset getCharset() {
        return charset;
    }

    public long getLoadedAt() {
        return loadedAt;
    }

    /** 读取(必要时重新加载)Board.xaml 原文。 */
    public synchronized String getContent() throws IOException {
        ensureLoaded();
        return content;
    }

    /** 原文中占位符出现的次数。 */
    public synchronized int countPlaceholders() throws IOException {
        ensureLoaded();
        return countOccurrences(content, placeholder);
    }

    public synchronized boolean containsPlaceholder() throws IOException {
        return countPlaceholders() > 0;
    }

    /** 强制重新读取文件。 */
    public synchronized void reload() throws IOException {
        if (!file.isFile()) {
            throw new IOException("找不到 Board 文件: " + file.getPath());
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        Charset detected = forcedCharset != null ? forcedCharset : detectCharset(bytes);
        String text = new String(bytes, detected);
        this.content = text;
        this.charset = detected;
        this.lastModified = file.lastModified();
        this.loadedAt = System.currentTimeMillis();
    }

    private void ensureLoaded() throws IOException {
        long currentModified = file.lastModified();
        boolean needLoad = loadedAt == 0L
                || lastModified != currentModified
                || (reloadOnChange && currentModified != lastModified);
        if (needLoad) {
            reload();
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 只替换人数变量。 */
    public String render(int playerCount) throws IOException {
        return render(PlayerCountResult.success("custom", playerCount, 0, "", "", 0L));
    }

    /** 替换人数变量与(可选的)扩展变量。 */
    public String render(PlayerCountResult result) throws IOException {
        String text = getContent();
        String count = String.valueOf(result.getOnline());

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("%player_count%", count);
        variables.put("%player_online%", count);
        if (extras) {
            variables.put("%player_max%", String.valueOf(result.getMax()));
            variables.put("%motd%", escapeForXmlText(result.getMotdOneLine()));
            variables.put("%version%", escapeForXmlText(result.getVersion()));
            variables.put("%ping_ms%", String.valueOf(result.getLatencyMs()));
            variables.put("%server_edition%", result.getEdition());
        }
        if (!DEFAULT_PLACEHOLDER.equals(placeholder)) {
            variables.put(placeholder, count);
        }
        return substitute(text, variables);
    }

    /** 渲染成字节(沿用源文件编码,保证 UTF-8 / GBK / UTF-16 原样输出)。 */
    public byte[] renderBytes(PlayerCountResult result) throws IOException {
        return render(result).getBytes(charset);
    }

    private String substitute(String text, Map<String, String> variables) {
        // 只替换注释之外的内容:模板开头的说明注释里常常原样写着 %player_count%,
        // 那些不应该被替换掉。
        StringBuilder out = new StringBuilder(text.length() + 16);
        int index = 0;
        while (index < text.length()) {
            int commentStart = text.indexOf("<!--", index);
            if (commentStart < 0) {
                out.append(substituteSegment(text.substring(index), variables));
                index = text.length();
                break;
            }
            out.append(substituteSegment(text.substring(index, commentStart), variables));
            int commentEnd = text.indexOf("-->", commentStart + 4);
            if (commentEnd < 0) {
                out.append(text, commentStart, text.length());
                index = text.length();
                break;
            }
            out.append(text, commentStart, commentEnd + 3);
            index = commentEnd + 3;
        }
        String rendered = out.toString();

        // 自定义占位符(不是 %xxx% 形式)再补一次替换
        if (!DEFAULT_PLACEHOLDER.equals(placeholder)) {
            String value = variables.get(placeholder);
            if (value != null && rendered.contains(placeholder)) {
                rendered = rendered.replace(placeholder, value);
            }
        }
        return rendered;
    }

    /** 单段文本内的变量替换。 */
    private String substituteSegment(String segment, Map<String, String> variables) {
        if (segment.isEmpty()) {
            return segment;
        }
        Matcher matcher = VARIABLE.matcher(segment);
        StringBuilder out = new StringBuilder(segment.length() + 8);
        int last = 0;
        while (matcher.find()) {
            String value = variables.get(matcher.group());
            if (value == null) {
                continue;
            }
            out.append(segment, last, matcher.start());
            out.append(value);
            last = matcher.end();
        }
        out.append(segment, last, segment.length());
        return out.toString();
    }

    private static int countOccurrences(String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** 扩展变量写进 XML 属性/文本时,避免破坏结构。 */
    private static String escapeForXmlText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    // ------------------------------------------------------------------
    // 编码识别
    // ------------------------------------------------------------------

    static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return Charset.forName("UTF-16");
            }
        }
        // 严格校验 UTF-8,失败则按 GBK 处理(中文 Windows 常见)
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            strict.decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException ex) {
            if (Charset.isSupported("GBK")) {
                return Charset.forName("GBK");
            }
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** 写文件时若源文件带 BOM,这里原样保留。 */
    public static byte[] withBomIfNeeded(byte[] content, Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            return content;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 3);
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    /** 命令行入口: java -cp BoardLive.jar mcboard.getBoardConfig [Board.xaml] */
    public static void main(String[] args) throws Exception {
        Log.configureConsole();
        File file = new File(args.length > 0 ? args[0] : "Board.xaml");
        getBoardConfig board = new getBoardConfig(file);
        System.out.println("文件      : " + board.getFile().getPath());
        System.out.println("编码      : " + board.getCharset().name());
        System.out.println("字符数    : " + board.getContent().length());
        System.out.println("占位符    : " + board.getPlaceholder() + " x " + board.countPlaceholders());
        System.out.println("含占位符  : " + board.containsPlaceholder());
    }
}
