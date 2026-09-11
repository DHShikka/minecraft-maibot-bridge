package com.mcai.bridge.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 脚本里的条件表达式。
 *
 * <p>设计目标是「够 LLM 用」：JSON 结构、一眼能看懂、错了能给出照做就行的提示。</p>
 *
 * <h2>支持的形式</h2>
 * <pre>
 * {"has":    {"item": "cobblestone", "count": 3}}   背包里有 3 个
 * {"has":    "cobblestone"}                         简写，等价于 count = 1
 * {"holding": "stone_sword"}                        主手拿着
 * {"healthBelow": 10}                               血量低于
 * {"foodBelow": 6}                                  饥饿值低于
 * {"atPos": {"x":1,"y":65,"z":0,"radius":2}}        在坐标附近
 * {"nearby": {"type": "zombie", "radius": 8, "min": 1}}  附近有实体
 * {"busy": false}                                   当前没有别的动作在跑
 * {"all": [ ... ]}  {"any": [ ... ]}  {"not": { ... }}   逻辑组合
 * {"always": true}                                  恒真（占位用）
 * </pre>
 */
public sealed interface Condition {

    boolean test(ScriptContext ctx);

    /** 给日志与报错用的人类可读描述。 */
    String describe();

    // ------------------------------------------------------------------ 实现

    record Always() implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return true;
        }

        @Override
        public String describe() {
            return "总是成立";
        }
    }

    record HasItem(String item, int count) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.countItem(item) >= count;
        }

        @Override
        public String describe() {
            return "背包里有 " + count + " 个 " + item;
        }
    }

    record Holding(String item) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.holding(item);
        }

        @Override
        public String describe() {
            return "主手拿着 " + item;
        }
    }

    record HealthBelow(double value) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.health() < value;
        }

        @Override
        public String describe() {
            return "血量低于 " + value;
        }
    }

    record FoodBelow(int value) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.food() < value;
        }

        @Override
        public String describe() {
            return "饥饿值低于 " + value;
        }
    }

    record AtPos(int x, int y, int z, int radius) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.atPos(x, y, z, radius);
        }

        @Override
        public String describe() {
            return "在 (" + x + "," + y + "," + z + ") 附近 " + radius + " 格内";
        }
    }

    record Nearby(String type, int radius, int min) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.nearbyCount(type, radius) >= min;
        }

        @Override
        public String describe() {
            return "半径 " + radius + " 格内有至少 " + min + " 个 " + type;
        }
    }

    record Busy(boolean expected) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return ctx.busy() == expected;
        }

        @Override
        public String describe() {
            return expected ? "当前有动作在跑" : "当前空闲";
        }
    }

    record All(List<Condition> parts) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            for (final Condition part : parts) {
                if (!part.test(ctx)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            return join(parts, " 且 ");
        }
    }

    record Any(List<Condition> parts) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            for (final Condition part : parts) {
                if (part.test(ctx)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return join(parts, " 或 ");
        }
    }

    record Not(Condition inner) implements Condition {
        @Override
        public boolean test(final ScriptContext ctx) {
            return !inner.test(ctx);
        }

        @Override
        public String describe() {
            return "非（" + inner.describe() + "）";
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 恒真条件，给 {@code if} 之类省略条件时用。 */
    Condition TRUE = new Always();

    /**
     * 从 JSON 解析一个条件。
     *
     * @param where 出错时用来定位的路径，例如 {@code steps[2].while}
     * @throws ScriptParseException 结构不认识或字段缺失
     */
    static Condition parse(final JsonElement element, final String where) {
        if (element == null || element.isJsonNull()) {
            throw new ScriptParseException(where + "：条件不能为空");
        }
        if (!element.isJsonObject()) {
            throw new ScriptParseException(where + "：条件必须是一个 JSON 对象，"
                    + "例如 {\"has\": {\"item\": \"oak_log\", \"count\": 1}}");
        }
        final JsonObject obj = element.getAsJsonObject();
        if (obj.size() != 1) {
            throw new ScriptParseException(where + "：一个条件对象必须**恰好**有一个键（当前有 "
                    + obj.size() + " 个：" + obj.keySet() + "）。"
                    + "要表达「并且」请用 {\"all\": [ ... ]}");
        }
        final String key = obj.keySet().iterator().next();
        final JsonElement value = obj.get(key);
        final String child = where + "." + key;

        return switch (key.toLowerCase(Locale.ROOT)) {
            case "always" -> TRUE;
            case "has" -> parseHas(value, child);
            case "holding" -> new Holding(requireString(value, child));
            case "healthbelow" -> new HealthBelow(requireNumber(value, child));
            case "foodbelow" -> new FoodBelow((int) requireNumber(value, child));
            case "atpos" -> parseAtPos(value, child);
            case "nearby" -> parseNearby(value, child);
            case "busy" -> new Busy(requireBoolean(value, child));
            case "all" -> new All(parseList(value, child));
            case "any" -> new Any(parseList(value, child));
            case "not" -> new Not(parse(value, child));
            default -> throw new ScriptParseException(child.replace("." + key, "") + "：不认识的条件「" + key
                    + "」。可用：has / holding / healthBelow / foodBelow / atPos / nearby / busy / all / any / not");
        };
    }

    private static Condition parseHas(final JsonElement value, final String where) {
        // 简写：{"has": "oak_log"} 表示至少 1 个
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new HasItem(value.getAsString(), 1);
        }
        final JsonObject obj = requireObject(value, where);
        if (obj.has("item") || obj.has("id") || obj.has("name")) {
            final String item = firstString(obj, where, "item", "id", "name");
            final int count = obj.has("count") ? (int) requireNumber(obj.get("count"), where + ".count") : 1;
            return new HasItem(item, Math.max(1, count));
        }
        throw new ScriptParseException(where + "：缺少 item 字段，"
                + "正确写法是 {\"has\": {\"item\": \"cobblestone\", \"count\": 3}}");
    }

    private static Condition parseAtPos(final JsonElement value, final String where) {
        final JsonObject obj = requireObject(value, where);
        final int x = (int) requireNumber(first(obj, where, "x"), where + ".x");
        final int y = (int) requireNumber(first(obj, where, "y"), where + ".y");
        final int z = (int) requireNumber(first(obj, where, "z"), where + ".z");
        final int radius = obj.has("radius") ? (int) requireNumber(obj.get("radius"), where + ".radius") : 2;
        return new AtPos(x, y, z, Math.max(0, radius));
    }

    private static Condition parseNearby(final JsonElement value, final String where) {
        final JsonObject obj = requireObject(value, where);
        final String type = firstString(obj, where, "type", "entity", "name");
        final int radius = obj.has("radius") ? (int) requireNumber(obj.get("radius"), where + ".radius") : 8;
        final int min = obj.has("min") ? (int) requireNumber(obj.get("min"), where + ".min") : 1;
        return new Nearby(type, Math.max(1, radius), Math.max(1, min));
    }

    private static List<Condition> parseList(final JsonElement value, final String where) {
        if (!value.isJsonArray()) {
            throw new ScriptParseException(where + "：必须是数组，例如 {\"all\": [ {\"has\": ...}, {\"not\": ...} ]}");
        }
        final JsonArray array = value.getAsJsonArray();
        if (array.isEmpty()) {
            throw new ScriptParseException(where + "：数组不能为空");
        }
        final List<Condition> parts = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            parts.add(parse(array.get(i), where + "[" + i + "]"));
        }
        return parts;
    }

    // ------------------------------------------------------------ 取值辅助

    private static String join(final List<Condition> parts, final String sep) {
        final StringBuilder sb = new StringBuilder();
        for (final Condition part : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(part.describe());
        }
        return sb.toString();
    }

    private static JsonElement first(final JsonObject obj, final String where, final String... keys) {
        for (final String key : keys) {
            final JsonElement value = obj.get(key);
            if (value != null && !value.isJsonNull()) {
                return value;
            }
        }
        throw new ScriptParseException(where + "：缺少字段 " + String.join(" / ", keys));
    }

    private static String firstString(final JsonObject obj, final String where, final String... keys) {
        return requireString(first(obj, where, keys), where);
    }

    private static JsonObject requireObject(final JsonElement element, final String where) {
        if (element == null || !element.isJsonObject()) {
            throw new ScriptParseException(where + "：必须是一个 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(final JsonElement element, final String where) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ScriptParseException(where + "：必须是字符串");
        }
        return element.getAsString();
    }

    private static double requireNumber(final JsonElement element, final String where) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(where + "：必须是数字");
        }
        return element.getAsDouble();
    }

    private static boolean requireBoolean(final JsonElement element, final String where) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ScriptParseException(where + "：必须是 true 或 false");
        }
        return element.getAsBoolean();
    }
}
