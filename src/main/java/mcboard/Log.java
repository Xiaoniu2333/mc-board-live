package mcboard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简控制台日志(Java 17)。输出到 stdout,不做任何编码转换。
 */
public final class Log {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile boolean debugEnabled = false;
    private static volatile boolean colorEnabled = true;

    private Log() {
    }

    /**
     * 输出被重定向/接管(日志收集、Web 终端、CI)时统一用 UTF-8,避免中文日志乱码;
     * 真正的控制台则沿用系统控制台编码。应在 main 的第一行调用。
     */
    public static void configureConsole() {
        try {
            if (System.console() == null) {
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                        StandardCharsets.UTF_8));
                System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                        StandardCharsets.UTF_8));
                colorEnabled = false; // 重定向到文件/管道时不需要 ANSI 颜色
            }
        } catch (RuntimeException ignored) {
            // 编码设置失败不影响主流程
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setColorEnabled(boolean enabled) {
        colorEnabled = enabled;
    }

    public static void debug(String message, Object... args) {
        if (debugEnabled) {
            write("DEBUG", "\u001B[90m", message, args);
        }
    }

    public static void info(String message, Object... args) {
        write("INFO", "\u001B[36m", message, args);
    }

    public static void warn(String message, Object... args) {
        write("WARN", "\u001B[33m", message, args);
    }

    public static void error(String message, Object... args) {
        write("ERROR", "\u001B[31m", message, args);
    }

    private static void write(String level, String color, String message, Object[] args) {
        String text;
        try {
            text = (args != null && args.length > 0) ? String.format(message, args) : message;
        } catch (RuntimeException ex) {
            text = message;
        }
        StringBuilder sb = new StringBuilder();
        if (colorEnabled) {
            sb.append(color);
        }
        sb.append('[').append(LocalTime.now().format(TIME_FORMAT)).append("] [").append(level).append(']');
        if (colorEnabled) {
            sb.append("\u001B[0m");
        }
        sb.append(' ').append(text);
        System.out.println(sb.toString());
        System.out.flush();
    }
}
