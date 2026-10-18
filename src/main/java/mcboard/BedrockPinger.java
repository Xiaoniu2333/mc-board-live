package mcboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Minecraft 基岩版(Bedrock)服务器列表查询。
 * 使用 RakNet 的 Unconnected Ping(0x01) / Unconnected Pong(0x1C)。
 * 响应字符串形如:
 * MCPE;MOTD;协议号;版本;在线人数;最大人数;服务器ID;MOTD2;...
 */
final class BedrockPinger {

    private static final byte[] RAKNET_MAGIC = new byte[]{
            (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0x00,
            (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
            (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78
    };

    private static final Random RANDOM = new Random();

    private BedrockPinger() {
    }

    static PlayerCountResult fetch(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(0x01); // Unconnected Ping
            writeLong(payload, System.currentTimeMillis());
            payload.write(RAKNET_MAGIC);
            writeLong(payload, RANDOM.nextLong());
            byte[] request = payload.toByteArray();

            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(request, request.length, address, port));

            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            long cost = System.currentTimeMillis() - start;

            int length = response.getLength();
            if (length < 35) {
                throw new IOException("响应长度过短: " + length);
            }
            if ((buffer[0] & 0xFF) != 0x1C) {
                throw new IOException("非预期的数据包 ID: " + (buffer[0] & 0xFF));
            }
            int stringLength = ((buffer[33] & 0xFF) << 8) | (buffer[34] & 0xFF);
            int available = Math.min(stringLength, length - 35);
            if (available <= 0) {
                throw new IOException("响应中 MOTD 为空");
            }
            String text = new String(buffer, 35, available, StandardCharsets.UTF_8);
            String[] fields = text.split(";", -1);
            if (fields.length < 6) {
                throw new IOException("响应字段不足: " + fields.length);
            }
            int online = parseIntSafe(fields[4], -1);
            int max = parseIntSafe(fields[5], -1);
            if (online < 0) {
                throw new IOException("响应中缺少在线人数: " + text);
            }
            String motd = fields[1];
            if (fields.length > 7 && !fields[7].isEmpty()) {
                motd = motd + " " + fields[7];
            }
            return PlayerCountResult.success("bedrock", online, max, motd, fields[3], cost);
        } catch (SocketTimeoutException ex) {
            return PlayerCountResult.failure("bedrock", System.currentTimeMillis() - start,
                    "连接基岩版服务器 " + host + ":" + port + " 超时(UDP 无响应,请确认端口与 edition 配置)");
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.isEmpty()) {
                message = ex.getClass().getSimpleName();
            }
            return PlayerCountResult.failure("bedrock", System.currentTimeMillis() - start,
                    "无法连接基岩版服务器 " + host + ":" + port + " (" + message + ")");
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        out.write((byte) (value >>> 56));
        out.write((byte) (value >>> 48));
        out.write((byte) (value >>> 40));
        out.write((byte) (value >>> 32));
        out.write((byte) (value >>> 24));
        out.write((byte) (value >>> 16));
        out.write((byte) (value >>> 8));
        out.write((byte) value);
    }

    private static int parseIntSafe(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }
}
