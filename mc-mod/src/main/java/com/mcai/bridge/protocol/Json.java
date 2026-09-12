package com.mcai.bridge.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/**
 * JSON 读写的小工具集。底层直接使用 Minecraft 自带的 Gson
 * （{@code com.google.gson}），因此模组不需要额外依赖。
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    /** 不带 null 的紧凑输出，用于日志。 */
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    public static String toJson(final JsonElement element) {
        return GSON.toJson(element);
    }

    public static String toCompactJson(final JsonElement element) {
        return COMPACT.toJson(element);
    }

    public static JsonObject parse(final String text) {
        final JsonElement element = com.google.gson.JsonParser.parseString(text);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("期望 JSON 对象，实际为: " + abbreviate(text, 80));
        }
        return element.getAsJsonObject();
    }

    public static String abbreviate(final String text, final int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(" + text.length() + ")";
    }

    // ------------------------------------------------------------ 安全读取

    /** 取出报文的数据段，兼容没有 {@code data} 字段的裸报文。 */
    public static JsonObject getData(final JsonObject message) {
        if (message == null) {
            return new JsonObject();
        }
        final JsonElement data = message.get("data");
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    /** 取出报文的 {@code type} 字段。 */
    public static String getType(final JsonObject message) {
        return str(message, "type", "");
    }

    /** 报文外壳：{@code {"v":1,"type":...,"ts":...,"data":{...}}}。 */
    public static JsonObject envelope(final String type, final JsonObject data) {
        final JsonObject o = new JsonObject();
        o.addProperty("v", PROTOCOL_VERSION);
        o.addProperty("type", type);
        o.addProperty("ts", System.currentTimeMillis());
        o.add("data", data == null ? new JsonObject() : data);
        return o;
    }

    /** 线协议版本，必须与 MaiBot 插件侧的 {@code protocol.PROTOCOL_VERSION} 一致。 */
    public static final int PROTOCOL_VERSION = 1;

    public static String str(final JsonObject o, final String key, final String def) {
        if (o == null) {
            return def;
        }
        final JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsString();
        } catch (final Exception ex) {
            return def;
        }
    }

    public static boolean has(final JsonObject o, final String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull();
    }

    public static double num(final JsonObject o, final String key, final double def) {
        if (o == null) {
            return def;
        }
        final JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsDouble();
        } catch (final Exception ex) {
            return def;
        }
    }

    public static int intVal(final JsonObject o, final String key, final int def) {
        return (int) Math.round(num(o, key, def));
    }

    public static long longVal(final JsonObject o, final String key, final long def) {
        return (long) Math.round(num(o, key, def));
    }

    public static boolean bool(final JsonObject o, final String key, final boolean def) {
        if (o == null) {
            return def;
        }
        final JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return def;
        }
        try {
            return e.getAsBoolean();
        } catch (final Exception ex) {
            return def;
        }
    }

    public static JsonObject objOrNull(final JsonObject o, final String key) {
        if (o == null) {
            return null;
        }
        final JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arrOrNull(final JsonObject o, final String key) {
        if (o == null) {
            return null;
        }
        final JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static List<String> stringList(final JsonObject o, final String key) {
        final JsonArray a = arrOrNull(o, key);
        if (a == null) {
            return List.of();
        }
        return a.asList().stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .toList();
    }

    // ------------------------------------------------------------ 便捷写入

    public static JsonObject put(final JsonObject o, final String key, final String value) {
        o.add(key, value == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(value));
        return o;
    }

    public static JsonObject put(final JsonObject o, final String key, final Number value) {
        o.add(key, value == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(value));
        return o;
    }

    public static JsonObject put(final JsonObject o, final String key, final Boolean value) {
        o.add(key, value == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(value));
        return o;
    }

    public static JsonObject put(final JsonObject o, final String key, final JsonElement value) {
        o.add(key, value == null ? com.google.gson.JsonNull.INSTANCE : value);
        return o;
    }

    /** 把 double 写成保留两位小数的数字，压缩报文体积。 */
    public static double round2(final double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 把浮点数安全地转成整数（用于坐标）。 */
    public static int floor(final double v) {
        return (int) Math.floor(v);
    }
}
