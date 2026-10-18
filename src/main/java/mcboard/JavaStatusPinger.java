package mcboard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minecraft Java 版服务器列表查询(Server List Ping)。
 * 先走现代协议(1.7+ 的 handshake + status),失败再退化为 1.6 的 legacy 0xFE 查询。
 */
final class JavaStatusPinger {

    private JavaStatusPinger() {
    }

    static PlayerCountResult fetch(String host, int port, int protocolVersion, int timeoutMs) {
        long start = System.currentTimeMillis();
        String modernError;
        try {
            return fetchModern(host, port, protocolVersion, timeoutMs, start);
        } catch (Exception ex) {
            modernError = describe(ex);
            Log.debug("Java 现代协议查询失败(%s),尝试 legacy 协议", modernError);
        }
        try {
            return fetchLegacy(host, port, timeoutMs, start);
        } catch (Exception ex) {
            long cost = System.currentTimeMillis() - start;
            String legacyError = describe(ex);
            String detail = modernError.equals(legacyError) ? modernError : modernError + " / " + legacyError;
            return PlayerCountResult.failure("java", cost,
                    "无法连接 Java 版服务器 " + host + ":" + port + " (" + detail + ")");
        }
    }

    private static PlayerCountResult fetchModern(String host, int port, int protocolVersion,
                                                 int timeoutMs, long start) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // 1) handshake
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, protocolVersion);
            writeString(handshake, host);
            handshake.writeShort(port & 0xFFFF);
            writeVarInt(handshake, 1); // next state = status
            handshake.flush();

            writeVarInt(out, handshakeBytes.size());
            out.write(handshakeBytes.toByteArray());

            // 2) status request
            writeVarInt(out, 1);
            writeVarInt(out, 0x00);
            out.flush();

            // 3) 读取响应
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int packetLength = readVarInt(in);
            if (packetLength <= 0) {
                throw new IOException("响应长度异常: " + packetLength);
            }
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                throw new IOException("非预期的数据包 ID: " + packetId);
            }
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > 8 * 1024 * 1024) {
                throw new IOException("JSON 长度异常: " + jsonLength);
            }
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            Map<String, Object> root = Json.asObject(Json.parse(json));
            Map<String, Object> players = Json.asObject(root.get("players"));
            int online = Json.optInt(players, "online", -1);
            int max = Json.optInt(players, "max", -1);
            if (online < 0) {
                throw new IOException("响应中缺少 players.online 字段");
            }
            Map<String, Object> version = Json.asObject(root.get("version"));
            String versionName = Json.optString(version, "name", "");
            String motd = Json.flatten(root.get("description"));
            return PlayerCountResult.success("java", online, max, motd, versionName,
                    System.currentTimeMillis() - start);
        } finally {
            closeQuietly(socket);
        }
    }

    private static PlayerCountResult fetchLegacy(String host, int port, int timeoutMs, long start) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(0xFE);
            out.write(0x01);
            out.flush();

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int packetId = in.readUnsignedByte();
            if (packetId != 0xFF) {
                throw new IOException("非预期的 Legacy 响应 ID: " + packetId);
            }
            int length = in.readUnsignedShort();
            if (length <= 0) {
                throw new IOException("Legacy 响应长度异常: " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            String text = new String(payload, StandardCharsets.UTF_16BE);
            String[] fields = text.split("\u0000", -1);
            if (fields.length < 6) {
                throw new IOException("Legacy 响应字段不足: " + fields.length);
            }
            int online = parseIntSafe(fields[4], -1);
            int max = parseIntSafe(fields[5], -1);
            if (online < 0) {
                throw new IOException("Legacy 响应中缺少在线人数");
            }
            String motd = fields[3].startsWith("\u00A71") ? fields[3].substring(2) : fields[3];
            return PlayerCountResult.success("java-legacy", online, max, motd, fields[2],
                    System.currentTimeMillis() - start);
        } finally {
            closeQuietly(socket);
        }
    }

    // ------------------------------------------------------------------
    // 协议辅助
    // ------------------------------------------------------------------

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            int bits = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out.write(bits | 0x80);
            } else {
                out.write(bits);
                return;
            }
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readByte() & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("VarInt 过长");
            }
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int parseIntSafe(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
