import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用 Mock Minecraft 服务器(不属于交付物,仅用于自测)。
 * 同时监听:
 *   - TCP: Java 版 Server List Ping(现代协议)
 *   - UDP: 基岩版 RakNet Unconnected Ping
 * 在线人数从 countFile 的第一行实时读取,方便验证"每次下载都是最新人数"。
 *
 * 用法: java MockMcServer <javaPort> <bedrockPort> <countFile>
 */
public final class MockMcServer {

    private static final byte[] MAGIC = new byte[]{
            (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0x00,
            (byte) 0xFE, (byte) 0xFE, (byte) 0xFE, (byte) 0xFE,
            (byte) 0xFD, (byte) 0xFD, (byte) 0xFD, (byte) 0xFD,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78
    };

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static Path countFile;
    private static final int MAX_PLAYERS = 100;

    private MockMcServer() {
    }

    public static void main(String[] args) throws Exception {
        int javaPort = args.length > 0 ? Integer.parseInt(args[0]) : 25599;
        int bedrockPort = args.length > 1 ? Integer.parseInt(args[1]) : 19133;
        countFile = Paths.get(args.length > 2 ? args[2] : "mock-count.txt").toAbsolutePath();
        if (!Files.exists(countFile)) {
            Files.writeString(countFile, "42\n");
        }

        Thread javaThread = new Thread(() -> runJava(javaPort), "mock-java");
        javaThread.setDaemon(true);
        javaThread.start();

        Thread bedrockThread = new Thread(() -> runBedrock(bedrockPort), "mock-bedrock");
        bedrockThread.setDaemon(true);
        bedrockThread.start();

        System.out.println("Mock MC server started.");
        System.out.println("  java    TCP " + javaPort);
        System.out.println("  bedrock UDP " + bedrockPort);
        System.out.println("  count file  " + countFile);
        System.out.flush();
        Thread.currentThread().join();
    }

    private static int readCount() {
        try {
            String first = Files.readAllLines(countFile).get(0).trim();
            return Integer.parseInt(first);
        } catch (Exception ex) {
            return 42;
        }
    }

    private static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + message);
        System.out.flush();
    }

    // ------------------------------------------------------------------
    // Java 版(现代 Server List Ping)
    // ------------------------------------------------------------------

    private static void runJava(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))) {
            log("java listening on 127.0.0.1:" + port);
            while (true) {
                Socket socket = serverSocket.accept();
                Thread handler = new Thread(() -> handleJava(socket), "mock-java-conn");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (IOException ex) {
            log("java server error: " + ex);
        }
    }

    private static void handleJava(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            int length = readVarInt(in);
            readVarInt(in);          // packet id 0x00
            readVarInt(in);          // protocol version
            int hostLen = readVarInt(in);
            byte[] hostBytes = new byte[hostLen];
            in.readFully(hostBytes);
            in.readUnsignedShort();  // port
            readVarInt(in);          // next state

            int requestLength = readVarInt(in);
            readVarInt(in);          // status request id

            int online = readCount();
            String json = "{\"version\":{\"name\":\"1.20.4\",\"protocol\":765},"
                    + "\"players\":{\"online\":" + online + ",\"max\":" + MAX_PLAYERS + "},"
                    + "\"description\":{\"text\":\"Mock BoardLive 测试服\"},"
                    + "\"favicon\":\"\"}";

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream payloadOut = new DataOutputStream(payload);
            writeVarInt(payloadOut, 0x00);
            writeString(payloadOut, json);
            payloadOut.flush();

            writeVarInt(out, payload.size());
            out.write(payload.toByteArray());
            out.flush();

            long n = REQUESTS.incrementAndGet();
            log("java status #" + n + " -> online=" + online + " (handshake " + length + "B, request " + requestLength + "B)");
        } catch (Exception ex) {
            log("java connection error: " + ex);
        }
    }

    // ------------------------------------------------------------------
    // 基岩版(RakNet Unconnected Ping)
    // ------------------------------------------------------------------

    private static void runBedrock(int port) {
        try (DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"))) {
            log("bedrock listening on 127.0.0.1:" + port);
            byte[] buffer = new byte[2048];
            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                if ((request.getData()[0] & 0xFF) != 0x01) {
                    continue;
                }
                long requestTime = 0L;
                for (int i = 1; i <= 8; i++) {
                    requestTime = (requestTime << 8) | (request.getData()[i] & 0xFF);
                }
                int online = readCount();
                String motd = "MCPE;Mock Bedrock;671;1.20.60;" + online + ";" + MAX_PLAYERS
                        + ";123456789;BoardLive 测试服;Survival;1;" + port + ";" + port + ";";
                byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);

                ByteArrayOutputStream response = new ByteArrayOutputStream();
                response.write(0x1C);
                writeLong(response, requestTime);
                writeLong(response, 987654321L);
                response.write(MAGIC);
                response.write((motdBytes.length >>> 8) & 0xFF);
                response.write(motdBytes.length & 0xFF);
                response.write(motdBytes, 0, motdBytes.length);

                byte[] bytes = response.toByteArray();
                socket.send(new DatagramPacket(bytes, bytes.length, request.getAddress(), request.getPort()));

                long n = REQUESTS.incrementAndGet();
                log("bedrock ping #" + n + " -> online=" + online);
            }
        } catch (IOException ex) {
            log("bedrock server error: " + ex);
        }
    }

    // ------------------------------------------------------------------
    // 协议辅助
    // ------------------------------------------------------------------

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }

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

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("stream closed while reading VarInt");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("VarInt too long");
            }
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
