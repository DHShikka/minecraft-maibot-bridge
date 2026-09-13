package com.mcai.bridge.util;

import com.mcai.bridge.McAiBridge;
import net.minecraft.client.Minecraft;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从游戏日志里捞 Baritone 的聊天回话。
 *
 * <h2>为什么要绕到日志文件</h2>
 *
 * <p>Baritone 是用 {@code ChatComponent.addMessage()} 直接把话打到聊天栏的，**不走服务端报文** ——
 * 所以 Forge 的 {@code ClientChatReceivedEvent} 根本不会触发。真机验证：{@code mc_query} 的聊天
 * 事件里一条 Baritone 的话都没有，而它的原话就躺在日志里：</p>
 *
 * <pre>
 * [Render thread/INFO] [minecraft/ChatComponent]: [System] [CHAT] [Baritone] Paused
 * </pre>
 *
 * <p>于是这里直接读日志文件的**尾巴**：回答「它刚说了什么」这件事，没有任何 API 比这更可靠 ——
 * 而且零依赖，没装 Baritone 时只是永远读不到东西。</p>
 *
 * <p>只在被问到时读一次（不轮询、不起线程），最多看最后 256 KB。</p>
 */
public final class BaritoneChatLog {

    /** 日志行里的聊天标记（客户端聊天栏的每一句话都会打进日志）。 */
    private static final String CHAT_MARK = "[CHAT]";
    /** Baritone 自己的前缀。 */
    private static final String PREFIX = "[Baritone]";
    /** 一次最多往回看多少字节 —— 别为了找一句话把整个日志读进来。 */
    private static final int MAX_BYTES = 256 * 1024;
    /** 最多返回几条。 */
    private static final int MAX_LINES = 8;
    /** 旧式颜色代码（§a 这种），AI 不需要。 */
    private static final Pattern COLOR = Pattern.compile("\u00a7.");

    /** 上一次问的时候日志尾巴里有哪些 Baritone 行 —— 用来算出「这次新说了什么」。 */
    private static final Set<String> seen = new LinkedHashSet<>();

    private BaritoneChatLog() {
    }

    /** 日志尾巴里的 Baritone 行（旧的在前、新的在后）。 */
    public static List<String> tail() {
        final List<String> out = new ArrayList<>();
        final Path log = logPath();
        if (log == null) {
            return out;
        }
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
            final long length = raf.length();
            final long start = Math.max(0L, length - MAX_BYTES);
            raf.seek(start);
            final byte[] buffer = new byte[(int) (length - start)];
            raf.readFully(buffer);
            // 从中间截断可能会切坏第一个字符，那一行本来就解析不出 [CHAT]，无所谓
            for (final String line : new String(buffer, StandardCharsets.UTF_8).split("\n")) {
                if (line.contains(CHAT_MARK) && line.contains(PREFIX)) {
                    out.add(clean(line));
                }
            }
        } catch (final Throwable t) {
            McAiBridge.LOGGER.debug("[MaiBot Bridge] 读日志找 Baritone 回话失败: {}", t.toString());
        }
        if (out.size() <= MAX_LINES) {
            return out;
        }
        return new ArrayList<>(out.subList(out.size() - MAX_LINES, out.size()));
    }

    /**
     * 自上次调用以来**新出现**的 Baritone 回话。
     *
     * <p>用法：发指令之前先调一次（把之前的都记成「见过」），发完再调一次 ——
     * 第二次拿到的就是这条指令的回话。</p>
     */
    public static List<String> collectNew() {
        final List<String> lines = tail();
        final List<String> fresh = new ArrayList<>();
        for (final String line : lines) {
            if (!seen.contains(line)) {
                fresh.add(line);
            }
        }
        seen.clear();
        seen.addAll(lines);
        return fresh;
    }

    /** 忘记「见过哪些」——测试用，或者换了世界/重连之后调用。 */
    public static void reset() {
        seen.clear();
    }

    // ---------------------------------------------------------------- 内部

    private static Path logPath() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return null;
        }
        return mc.gameDirectory.toPath().resolve("logs").resolve("latest.log");
    }

    /** 把一整行日志削成「Baritone 原话」：去掉前缀、颜色代码和首尾空白。 */
    private static String clean(final String line) {
        final int at = line.lastIndexOf(PREFIX);
        final String text = at >= 0 ? line.substring(at + PREFIX.length()) : line;
        return COLOR.matcher(text).replaceAll("").trim();
    }
}
