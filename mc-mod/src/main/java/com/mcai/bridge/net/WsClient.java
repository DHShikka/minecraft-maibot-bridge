package com.mcai.bridge.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 一个不依赖任何第三方库的 RFC 6455 WebSocket <b>客户端</b>。
 *
 * <p>自己实现而不用 Java-WebSocket / Netty 的原因是：这是一个 Forge 模组，
 * 把外部 jar 打进模组包（shading / jarJar）会显著提高构建复杂度和出问题的概率。
 * WebSocket 客户端协议本身很小，直接基于 {@link Socket} 实现可以让模组保持零额外依赖，
 * 同时也便于精确控制重连与心跳行为。</p>
 *
 * <p>支持：ws / wss、文本帧、二进制帧（忽略内容仅计数）、分片消息、ping/pong、close 握手、
 * 客户端掩码（协议强制要求）。</p>
 *
 * <p>线程模型：一个读线程。{@link #sendText(String)} 可以从任意线程调用，
 * 内部用 {@code writeLock} 串行化写入。</p>
 */
public final class WsClient implements AutoCloseable {

    /** WebSocket 握手使用的固定 GUID（RFC 6455 §1.3）。 */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 单条消息最大字节数，防止对端构造超大帧导致内存耗尽。 */
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    /** 连接生命周期回调。所有回调都在读线程上触发。 */
    public interface Listener {
        void onOpen();

        void onText(String text);

        void onClose(int statusCode, String reason, boolean remote);

        void onError(Throwable error);
    }

    private final URI uri;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final Listener listener;
    private final Map<String, String> extraHeaders;

    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile boolean open;
    private volatile boolean closedNotified;
    private volatile boolean started;
    private volatile int closeCode = -1;
    private volatile String closeReason = "";
    private Thread readerThread;

    public WsClient(final URI uri, final Listener listener, final Map<String, String> extraHeaders, final int connectTimeoutMillis)
            throws IOException {
        this.uri = uri;
        this.listener = listener;
        this.extraHeaders = extraHeaders == null ? Map.of() : new LinkedHashMap<>(extraHeaders);

        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IOException("不支持的协议: " + uri + "（只支持 ws:// 和 wss://）");
        }
        this.secure = "wss".equals(scheme);
        this.host = uri.getHost();
        if (host == null) {
            throw new IOException("URL 中缺少主机名: " + uri);
        }
        this.port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);

        String p = uri.getRawPath();
        if (p == null || p.isEmpty()) {
            p = "/";
        }
        final String query = uri.getRawQuery();
        this.path = query == null || query.isEmpty() ? p : p + "?" + query;

        connect(connectTimeoutMillis);
    }

    // ------------------------------------------------------------ 建立连接

    private void connect(final int connectTimeoutMillis) throws IOException {
        final Socket raw = secure
                ? SSLSocketFactory.getDefault().createSocket()
                : new Socket();
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(0);
        raw.connect(new InetSocketAddress(host, port), Math.max(1000, connectTimeoutMillis));

        if (raw instanceof SSLSocket ssl) {
            ssl.startHandshake();
        }

        this.socket = raw;
        this.input = raw.getInputStream();
        this.output = raw.getOutputStream();

        doHandshake();
    }

    /**
     * 启动读线程并回调 {@link Listener#onOpen()}。
     *
     * <p>刻意<b>不</b>在构造函数里回调 {@code onOpen}：调用方通常要在回调里用
     * {@code this.client} 发第一条消息（例如握手用的 hello），而那时构造函数还没返回、
     * 字段还是 null。把「连接建立」与「通知上层」拆开，调用方就能先保存引用再 start()。</p>
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        open = true;
        readerThread = new Thread(this::readLoop, "McAiBridge-WS-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        listener.onOpen();
    }

    public boolean isStarted() {
        return started;
    }

    private void doHandshake() throws IOException {
        final byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        final String key = Base64.getEncoder().encodeToString(nonce);

        final StringBuilder req = new StringBuilder(256);
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("User-Agent: McAiBridge/1.0 (Minecraft Forge 1.20.1)\r\n");
        for (final Map.Entry<String, String> e : extraHeaders.entrySet()) {
            // 不做换行注入
            final String name = e.getKey() == null ? "" : e.getKey().replaceAll("[\\r\\n]", "");
            final String value = e.getValue() == null ? "" : e.getValue().replaceAll("[\\r\\n]", "");
            if (!name.isBlank()) {
                req.append(name).append(": ").append(value).append("\r\n");
            }
        }
        req.append("\r\n");

        output.write(req.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        // 读取 HTTP 响应头（逐字节读到 \r\n\r\n，避免多读走 WebSocket 帧数据）
        final String headerBlock = readHttpHeaderBlock();
        final String[] lines = headerBlock.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("服务器返回了空的握手响应");
        }
        final String statusLine = lines[0];
        final String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !"101".equals(statusParts[1])) {
            throw new IOException("WebSocket 握手失败: " + statusLine);
        }

        String accept = null;
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            if (colon > 0) {
                final String name = lines[i].substring(0, colon).trim();
                if (name.equalsIgnoreCase("Sec-WebSocket-Accept")) {
                    accept = lines[i].substring(colon + 1).trim();
                }
            }
        }

        final String expected = base64Sha1(key + WS_GUID);
        if (accept == null || !accept.equals(expected)) {
            throw new IOException("Sec-WebSocket-Accept 校验失败（期望 " + expected + "，实际 " + accept + "）");
        }
    }

    /** 逐字节读取直到遇到 \r\n\r\n，只消费 HTTP 头，不会多读一个字节。 */
    private String readHttpHeaderBlock() throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        final int limit = 16 * 1024;
        while (state < 4) {
            final int b = input.read();
            if (b == -1) {
                throw new EOFException("握手过程中连接被关闭");
            }
            buf.write(b);
            if (buf.size() > limit) {
                throw new IOException("握手响应头过大");
            }
            switch (state) {
                case 0 -> state = (b == '\r') ? 1 : 0;
                case 1 -> state = (b == '\n') ? 2 : ((b == '\r') ? 1 : 0);
                case 2 -> state = (b == '\r') ? 3 : 0;
                case 3 -> state = (b == '\n') ? 4 : 0;
                default -> {
                }
            }
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static String base64Sha1(final String text) throws IOException {
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException("当前 JVM 缺少 SHA-1 实现", e);
        }
    }

    // ---------------------------------------------------------------- 读取

    private void readLoop() {
        int code = 1006;
        String reason = "连接中断";
        boolean remote = true;
        try {
            final ByteArrayOutputStream fragments = new ByteArrayOutputStream(4096);
            int fragmentOpcode = -1;

            while (open) {
                final int b0 = input.read();
                if (b0 == -1) {
                    reason = "对端关闭了 TCP 连接";
                    break;
                }
                final int b1 = readByte();
                final boolean fin = (b0 & 0x80) != 0;
                final int opcode = b0 & 0x0F;
                final boolean masked = (b1 & 0x80) != 0;
                long length = b1 & 0x7F;

                if (length == 126) {
                    length = ((long) readByte() << 8) | readByte();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | readByte();
                    }
                    if (length < 0) {
                        throw new IOException("帧长度非法（最高位为 1）");
                    }
                }
                if (length > MAX_MESSAGE_BYTES) {
                    throw new IOException("收到的帧过大: " + length + " 字节");
                }

                final byte[] maskKey = new byte[4];
                if (masked) {
                    readFully(maskKey);
                }

                final byte[] payload = new byte[(int) length];
                if (length > 0) {
                    readFully(payload);
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);
                        }
                    }
                }

                switch (opcode) {
                    case OP_PING -> sendFrame(OP_PONG, payload);
                    case OP_PONG -> {
                        // 心跳应答，由上层根据时间戳判断，这里不需要额外处理
                    }
                    case OP_CLOSE -> {
                        int peerCode = 1005;
                        String peerReason = "";
                        if (payload.length >= 2) {
                            peerCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                            if (payload.length > 2) {
                                peerReason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                            }
                        }
                        sendFrame(OP_CLOSE, payload.length >= 2 ? payload : new byte[]{3, (byte) 232});
                        code = peerCode;
                        reason = peerReason.isEmpty() ? "对端主动关闭" : peerReason;
                        remote = true;
                        open = false;
                        break;
                    }
                    case OP_TEXT, OP_BINARY -> {
                        if (fin) {
                            if (opcode == OP_TEXT) {
                                dispatchText(new String(payload, StandardCharsets.UTF_8));
                            }
                        } else {
                            fragmentOpcode = opcode;
                            fragments.reset();
                            fragments.write(payload);
                        }
                    }
                    case OP_CONTINUATION -> {
                        if (fragmentOpcode == -1) {
                            throw new IOException("收到孤立的续帧");
                        }
                        if (fragments.size() + payload.length > MAX_MESSAGE_BYTES) {
                            throw new IOException("分片消息超过上限");
                        }
                        fragments.write(payload);
                        if (fin) {
                            if (fragmentOpcode == OP_TEXT) {
                                dispatchText(fragments.toString(StandardCharsets.UTF_8));
                            }
                            fragmentOpcode = -1;
                            fragments.reset();
                        }
                    }
                    default -> throw new IOException("收到未知 opcode: " + opcode);
                }

                if (!open) {
                    break;
                }
            }
        } catch (final Throwable t) {
            if (open) {
                listener.onError(t);
                reason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
        } finally {
            final boolean wasOpen = open;
            open = false;
            closeQuietly();
            if (wasOpen) {
                code = 1006;
            }
            notifyClose(code, reason, remote);
        }
    }

    private void dispatchText(final String text) {
        try {
            listener.onText(text);
        } catch (final Throwable t) {
            listener.onError(t);
        }
    }

    private int readByte() throws IOException {
        final int b = input.read();
        if (b == -1) {
            throw new EOFException("帧数据不完整，连接已关闭");
        }
        return b;
    }

    private void readFully(final byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            final int n = input.read(target, offset, target.length - offset);
            if (n == -1) {
                throw new EOFException("帧数据不完整，连接已关闭");
            }
            offset += n;
        }
    }

    // ---------------------------------------------------------------- 发送

    /** 发送一个文本帧。未连接时静默丢弃并返回 false。 */
    public boolean sendText(final String text) {
        if (!open) {
            return false;
        }
        try {
            sendFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (final IOException e) {
            listener.onError(e);
            open = false;
            closeQuietly();
            notifyClose(1006, "发送失败: " + e.getMessage(), false);
            return false;
        }
    }

    /** 发送一个 ping 帧（用于保活与半开连接探测）。 */
    public boolean sendPing() {
        if (!open) {
            return false;
        }
        try {
            sendFrame(OP_PING, new byte[0]);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * 主动关闭连接。会先发送 close 帧做优雅挥手，再关闭 socket。
     */
    public void close(final int statusCode, final String reason) {
        if (!open) {
            closeQuietly();
            return;
        }
        final byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        final byte[] payload = new byte[2 + Math.min(reasonBytes.length, 123)];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
        try {
            sendFrame(OP_CLOSE, payload);
        } catch (final IOException ignored) {
            // 对端可能已经断开，忽略
        }
        open = false;
        closeQuietly();
        notifyClose(statusCode, reason == null ? "" : reason, false);
    }

    @Override
    public void close() {
        close(1000, "客户端主动关闭");
    }

    private void sendFrame(final int opcode, final byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (socket == null || socket.isClosed()) {
                throw new IOException("socket 已关闭");
            }
            final ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
            frame.write(0x80 | opcode); // FIN + opcode

            final int len = payload.length;
            if (len < 126) {
                frame.write(0x80 | len); // MASK 位置 1（客户端必须掩码）
            } else if (len <= 0xFFFF) {
                frame.write(0x80 | 126);
                frame.write((len >>> 8) & 0xFF);
                frame.write(len & 0xFF);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) (((long) len >>> (8 * i)) & 0xFF));
                }
            }

            final byte[] maskKey = new byte[4];
            random.nextBytes(maskKey);
            frame.write(maskKey, 0, 4);

            final byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) {
                masked[i] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
            frame.write(masked, 0, len);

            output.write(frame.toByteArray());
            output.flush();
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (final IOException ignored) {
            // 忽略
        }
    }

    private void notifyClose(final int code, final String reason, final boolean remote) {
        if (closedNotified) {
            return;
        }
        closedNotified = true;
        closeCode = code;
        closeReason = reason;
        // 读线程自身可能正在执行 notifyClose，此时不能再 join 自己
        if (Thread.currentThread() != readerThread) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // 忽略
            }
        }
        listener.onClose(code, reason, remote);
    }

    public boolean isOpen() {
        return open;
    }

    public int getCloseCode() {
        return closeCode;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public URI getUri() {
        return uri;
    }
}
