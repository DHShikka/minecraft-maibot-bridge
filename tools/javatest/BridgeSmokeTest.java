import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcai.bridge.net.WsClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 冒烟测试：让模组里那份手写的 WebSocket 客户端（{@link WsClient}）去连一个真实的
 * RFC 6455 服务端（就是 MaiBot 插件用的那个 Python asyncio 实现），跑一遍完整交互。
 *
 * <p>这件事无法用「编译通过」替代：握手、掩码、帧长度编码、分片、ping/pong、
 * close 握手任何一处写错，都只有在真的连一次时才会暴露。</p>
 *
 * <p>用法：{@code java BridgeSmokeTest ws://127.0.0.1:PORT/mc <结果文件>}</p>
 */
public final class BridgeSmokeTest {

    private static final List<String> LOG = new ArrayList<>();
    private static final CountDownLatch DONE = new CountDownLatch(1);
    private static final AtomicInteger ACTIONS_HANDLED = new AtomicInteger();
    private static final Map<String, JsonObject> ACTION_PARAMS = new LinkedHashMap<>();
    private static volatile boolean opened;
    private static volatile boolean gotHelloAck;
    private static volatile String helloAckError = "";
    private static volatile int closeCode = -1;
    private static volatile WsClient client;

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: BridgeSmokeTest <ws-url> <result-file>");
            System.exit(2);
        }
        final String url = args[0];
        final Path resultFile = Path.of(args[1]);

        client = new WsClient(URI.create(url), new WsClient.Listener() {
            @Override
            public void onOpen() {
                opened = true;
                note("opened");
                sendHello();
                sendStateAndEvent();
            }

            @Override
            public void onText(final String text) {
                try {
                    handle(text);
                } catch (final Throwable t) {
                    note("ERROR handling message: " + t);
                }
            }

            @Override
            public void onClose(final int statusCode, final String reason, final boolean remote) {
                closeCode = statusCode;
                note("closed code=" + statusCode + " reason=" + reason + " remote=" + remote);
                DONE.countDown();
            }

            @Override
            public void onError(final Throwable error) {
                note("error: " + error);
            }
        }, Map.of("X-McAiBridge-Token", ""), 5000);

        // 先保存引用再 start()：onOpen 回调里要用 client 发 hello
        client.start();
        note("connected=" + client.isOpen() + " uri=" + client.getUri());

        // 等到双方交互完成：至少处理过 2 个 action，或者最多等 12 秒
        final long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            if (ACTIONS_HANDLED.get() >= 2 && gotHelloAck) {
                break;
            }
            Thread.sleep(50);
        }
        // 再给对端一点时间把最后一条结果处理完
        Thread.sleep(300);

        final JsonObject result = new JsonObject();
        result.addProperty("opened", opened);
        result.addProperty("helloAck", gotHelloAck);
        result.addProperty("helloAckError", helloAckError);
        result.addProperty("actionsHandled", ACTIONS_HANDLED.get());
        result.addProperty("isOpen", client.isOpen());
        result.addProperty("closeCode", closeCode);
        final JsonObject params = new JsonObject();
        ACTION_PARAMS.forEach(params::add);
        result.add("actionParams", params);

        final StringBuilder logText = new StringBuilder();
        for (final String line : LOG) {
            logText.append(line).append('\n');
            System.out.println(line);
        }
        result.addProperty("log", logText.toString());

        Files.writeString(resultFile, result.toString(), StandardCharsets.UTF_8);
        client.close(1000, "冒烟测试结束");
        System.out.println("RESULT WRITTEN: " + resultFile);
        System.exit(0);
    }

    private static void note(final String line) {
        LOG.add(line);
    }

    private static void sendHello() {
        final JsonObject mod = new JsonObject();
        mod.addProperty("version", "1.0.0");
        mod.addProperty("mc", "1.20.1");
        mod.addProperty("forge", "47.4.10");

        final JsonObject player = new JsonObject();
        player.addProperty("name", "Steve");
        player.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        player.addProperty("dimension", "minecraft:overworld");

        final JsonObject data = new JsonObject();
        data.addProperty("token", "");
        data.addProperty("protocol", 1);
        data.add("mod", mod);
        data.add("player", player);
        data.addProperty("singleplayer", true);
        data.addProperty("serverName", "冒烟测试世界");
        data.addProperty("serverAddress", "");
        data.addProperty("worldKey", "sp:smoke");
        final com.google.gson.JsonArray caps = new com.google.gson.JsonArray();
        caps.add("chat");
        caps.add("move");
        data.add("capabilities", caps);

        send("hello", data);
    }

    private static void sendStateAndEvent() {
        final JsonObject player = new JsonObject();
        player.addProperty("name", "Steve");
        player.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        player.addProperty("dimension", "minecraft:overworld");
        final JsonObject pos = new JsonObject();
        pos.addProperty("x", 10.5);
        pos.addProperty("y", 63.0);
        pos.addProperty("z", -4.25);
        player.add("pos", pos);
        final JsonObject blockPos = new JsonObject();
        blockPos.addProperty("x", 10);
        blockPos.addProperty("y", 63);
        blockPos.addProperty("z", -5);
        player.add("blockPos", blockPos);
        player.addProperty("health", 18.0);
        player.addProperty("maxHealth", 20.0);
        player.addProperty("food", 17);
        player.addProperty("gameMode", "survival");
        player.add("heldItem", null);

        final JsonObject world = new JsonObject();
        world.addProperty("timeOfDay", 13000);
        world.addProperty("isDay", false);
        world.addProperty("biome", "minecraft:plains");
        world.addProperty("raining", false);

        final JsonObject inventory = new JsonObject();
        inventory.addProperty("usedSlots", 1);
        inventory.addProperty("freeSlots", 35);
        inventory.add("slots", new com.google.gson.JsonArray());

        final JsonObject state = new JsonObject();
        state.addProperty("reason", "join");
        state.addProperty("inWorld", true);
        state.add("player", player);
        state.add("world", world);
        state.add("inventory", inventory);
        send("state", state);

        final JsonObject event = new JsonObject();
        event.addProperty("kind", "chat");
        event.addProperty("channel", "chat");
        event.addProperty("text", "麦麦你好，我是 Steve");
        event.addProperty("player", true);
        event.addProperty("sender", "Alex");
        event.addProperty("playerName", "Alex");
        event.add("pos", pos);
        event.addProperty("dimension", "minecraft:overworld");
        send("event", event);
    }

    private static void send(final String type, final JsonObject data) {
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 1);
        envelope.addProperty("type", type);
        envelope.addProperty("ts", System.currentTimeMillis());
        envelope.add("data", data);
        final boolean sent = client.sendText(envelope.toString());
        note("sent " + type + " -> " + sent);
    }

    private static void handle(final String text) {
        final JsonObject message = JsonParser.parseString(text).getAsJsonObject();
        final String type = message.has("type") ? message.get("type").getAsString() : "";
        note("recv " + type + " " + abbreviate(text));

        switch (type) {
            case "hello_ack" -> {
                final JsonObject data = message.getAsJsonObject("data");
                gotHelloAck = data.has("ok") && data.get("ok").getAsBoolean();
                helloAckError = data.has("error") && !data.get("error").isJsonNull()
                        ? data.get("error").getAsString() : "";
            }
            case "action" -> {
                final JsonObject data = message.getAsJsonObject("data");
                final String actionId = data.has("actionId") ? data.get("actionId").getAsString() : "";
                final String action = data.has("action") ? data.get("action").getAsString() : "";
                if (data.has("params") && data.get("params").isJsonObject()) {
                    ACTION_PARAMS.put(action, data.getAsJsonObject("params"));
                }
                ACTIONS_HANDLED.incrementAndGet();

                final JsonObject payload = new JsonObject();
                payload.addProperty("echo", action);
                payload.addProperty("message", "模组已收到 " + action);

                final JsonObject result = new JsonObject();
                result.addProperty("actionId", actionId);
                result.addProperty("action", action);
                result.addProperty("ok", true);
                result.add("result", payload);
                result.add("error", null);
                result.addProperty("elapsedMs", 7);

                final JsonObject envelope = new JsonObject();
                envelope.addProperty("v", 1);
                envelope.addProperty("type", "action_result");
                envelope.addProperty("id", actionId);
                envelope.add("data", result);
                note("reply action_result(" + action + ") -> " + client.sendText(envelope.toString()));
            }
            case "ping" -> {
                final JsonObject envelope = new JsonObject();
                envelope.addProperty("v", 1);
                envelope.addProperty("type", "pong");
                envelope.add("data", new JsonObject());
                client.sendText(envelope.toString());
            }
            case "shutdown" -> {
                note("server asked to shut down");
                DONE.countDown();
            }
            default -> {
                // notice / 其它，忽略
            }
        }
    }

    private static String abbreviate(final String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…(" + text.length() + ")";
    }

    private BridgeSmokeTest() {
    }
}
