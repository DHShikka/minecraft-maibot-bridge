package com.mcai.bridge.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 脚本节点。
 *
 * <p>一整段流程被解析成这几种节点的树：</p>
 * <pre>
 * {"action": "mine_blocks", "params": {...}, "optional": false}   一个具体动作
 * {"repeat": 3, "steps": [ ... ]}                                重复
 * {"while": {"condition": {...}, "maxIterations": 20}, "steps": [...]}   条件循环
 * {"if": {"condition": {...}}, "then": [...], "else": [...]}     分支
 * {"waitUntil": {"condition": {...}, "timeoutMs": 30000}}        等条件成立
 * </pre>
 */
public sealed interface ScriptNode {

    /** 给日志与报错用的简短描述。 */
    String describe();

    /** 循环节点用：跑完一轮后问它要不要再来一轮。非循环节点返回 false。 */
    default boolean loopShouldRepeat(final int iterationsDone, final ScriptContext ctx) {
        return false;
    }

    /** 这个节点是不是循环（跑完循环体要回头问一次）。 */
    default boolean isLoop() {
        return false;
    }

    // ------------------------------------------------------------------ 实现

    /** 一个具体动作。 */
    record Action(String action, JsonObject params, boolean optional) implements ScriptNode {
        @Override
        public String describe() {
            return action + describeParams();
        }

        private String describeParams() {
            if (params == null || params.size() == 0) {
                return "";
            }
            final StringBuilder sb = new StringBuilder("(");
            boolean first = true;
            for (final String key : params.keySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(key).append('=').append(params.get(key));
            }
            return sb.append(')').toString();
        }
    }

    /** 重复固定次数。 */
    record Repeat(int times, List<ScriptNode> body) implements ScriptNode {
        @Override
        public String describe() {
            return "重复 " + times + " 次";
        }

        @Override
        public boolean isLoop() {
            return true;
        }

        @Override
        public boolean loopShouldRepeat(final int iterationsDone, final ScriptContext ctx) {
            return iterationsDone < times;
        }
    }

    /** 只要条件成立就一直重复（有次数上限兜底）。 */
    record While(Condition condition, int maxIterations, List<ScriptNode> body) implements ScriptNode {
        @Override
        public String describe() {
            return "当「" + condition.describe() + "」时循环（最多 " + maxIterations + " 轮）";
        }

        @Override
        public boolean isLoop() {
            return true;
        }

        @Override
        public boolean loopShouldRepeat(final int iterationsDone, final ScriptContext ctx) {
            return iterationsDone < maxIterations && condition.test(ctx);
        }
    }

    /** 条件分支。 */
    record If(Condition condition, List<ScriptNode> then, List<ScriptNode> otherwise) implements ScriptNode {
        @Override
        public String describe() {
            return "如果「" + condition.describe() + "」";
        }
    }

    /** 等条件成立。 */
    record WaitUntil(Condition condition, long timeoutMs) implements ScriptNode {
        @Override
        public String describe() {
            return "等待「" + condition.describe() + "」（最多 " + (timeoutMs / 1000) + " 秒）";
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 一次解析允许的最大节点数，防止 LLM 生成一个巨大脚本把游戏卡死。 */
    int MAX_NODES = 400;

    /**
     * 解析一串步骤。
     *
     * @param array 步骤数组
     * @param where 出错时用来定位的路径前缀
     */
    static List<ScriptNode> parseSteps(final JsonElement array, final String where,
                                       final int[] budget) {
        if (array == null || !array.isJsonArray()) {
            throw new ScriptParseException(where + "：必须是步骤数组，例如 \"steps\": [ {\"action\": \"chat\", ...} ]");
        }
        final JsonArray jsonArray = array.getAsJsonArray();
        if (jsonArray.isEmpty()) {
            throw new ScriptParseException(where + "：步骤数组不能为空");
        }
        final List<ScriptNode> nodes = new ArrayList<>(jsonArray.size());
        for (int i = 0; i < jsonArray.size(); i++) {
            if (--budget[0] < 0) {
                throw new ScriptParseException("脚本节点太多（超过 " + MAX_NODES
                        + " 个）。请拆成多个 mc_script 调用，或者用 repeat/while 减少重复。");
            }
            nodes.add(parseNode(jsonArray.get(i), where + "[" + i + "]", budget));
        }
        return nodes;
    }

    /** 解析单个节点。 */
    static ScriptNode parseNode(final JsonElement element, final String where, final int[] budget) {
        if (element == null || !element.isJsonObject()) {
            throw new ScriptParseException(where + "：每个步骤必须是一个 JSON 对象");
        }
        final JsonObject obj = element.getAsJsonObject();

        // ---- 具体动作 ----
        if (obj.has("action")) {
            final String action = obj.get("action").getAsString().trim();
            if (action.isEmpty()) {
                throw new ScriptParseException(where + ".action：动作名不能为空");
            }
            final JsonObject params = obj.has("params") && obj.get("params").isJsonObject()
                    ? obj.getAsJsonObject("params").deepCopy()
                    : new JsonObject();
            final boolean optional = obj.has("optional") && obj.get("optional").isJsonPrimitive()
                    && obj.getAsJsonPrimitive("optional").isBoolean()
                    && obj.get("optional").getAsBoolean();
            return new Action(action, params, optional);
        }

        // ---- 控制流 ----
        final String kind = firstPresentKey(obj, "repeat", "while", "if", "waitUntil");
        if (kind == null) {
            throw new ScriptParseException(where + "：一个步骤要么有 \"action\" 字段，"
                    + "要么是控制流（\"repeat\" / \"while\" / \"if\" / \"waitUntil\"）。"
                    + "当前对象的键是 " + obj.keySet());
        }

        return switch (kind) {
            case "repeat" -> {
                final int times = requireInt(obj.get("repeat"), where + ".repeat");
                if (times < 1) {
                    throw new ScriptParseException(where + ".repeat：重复次数必须 >= 1");
                }
                if (times > 200) {
                    throw new ScriptParseException(where + ".repeat：重复次数上限是 200（收到 " + times + "）");
                }
                yield new Repeat(times, parseSteps(obj.get("steps"), where + ".steps", budget));
            }
            case "while" -> {
                final JsonObject spec = requireObject(obj.get("while"), where + ".while");
                if (!spec.has("condition")) {
                    throw new ScriptParseException(where + ".while：缺少 condition，"
                            + "正确写法 {\"while\": {\"condition\": {\"has\": {...}}, \"maxIterations\": 20}, \"steps\": [...]}");
                }
                final Condition condition = Condition.parse(spec.get("condition"), where + ".while.condition");
                final int maxIterations = spec.has("maxIterations")
                        ? requireInt(spec.get("maxIterations"), where + ".while.maxIterations") : 20;
                if (maxIterations < 1 || maxIterations > 200) {
                    throw new ScriptParseException(where + ".while.maxIterations：必须在 1~200 之间");
                }
                yield new While(condition, maxIterations, parseSteps(obj.get("steps"), where + ".steps", budget));
            }
            case "if" -> {
                final JsonObject spec = requireObject(obj.get("if"), where + ".if");
                if (!spec.has("condition")) {
                    throw new ScriptParseException(where + ".if：缺少 condition，"
                            + "正确写法 {\"if\": {\"condition\": {...}}, \"then\": [...], \"else\": [...]}");
                }
                final Condition condition = Condition.parse(spec.get("condition"), where + ".if.condition");
                final List<ScriptNode> then = parseSteps(obj.get("then"), where + ".then", budget);
                final List<ScriptNode> otherwise = obj.has("else")
                        ? parseSteps(obj.get("else"), where + ".else", budget)
                        : List.of();
                yield new If(condition, then, otherwise);
            }
            case "waitUntil" -> {
                final JsonObject spec = requireObject(obj.get("waitUntil"), where + ".waitUntil");
                if (!spec.has("condition")) {
                    throw new ScriptParseException(where + ".waitUntil：缺少 condition，"
                            + "正确写法 {\"waitUntil\": {\"condition\": {\"has\": {...}}, \"timeoutMs\": 30000}}");
                }
                final Condition condition = Condition.parse(spec.get("condition"), where + ".waitUntil.condition");
                final long timeoutMs = spec.has("timeoutMs")
                        ? (long) requireInt(spec.get("timeoutMs"), where + ".waitUntil.timeoutMs") : 60_000L;
                if (timeoutMs < 500) {
                    throw new ScriptParseException(where + ".waitUntil.timeoutMs：至少 500 毫秒");
                }
                yield new WaitUntil(condition, timeoutMs);
            }
            default -> throw new ScriptParseException(where + "：不认识的控制流「" + kind + "」");
        };
    }

    // ------------------------------------------------------------ 取值辅助

    private static String firstPresentKey(final JsonObject obj, final String... keys) {
        for (final String key : keys) {
            if (obj.has(key)) {
                return key;
            }
        }
        return null;
    }

    private static JsonObject requireObject(final JsonElement element, final String where) {
        if (element == null || !element.isJsonObject()) {
            throw new ScriptParseException(where + "：必须是一个 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    private static int requireInt(final JsonElement element, final String where) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ScriptParseException(where + "：必须是整数");
        }
        return (int) element.getAsDouble();
    }

    /** 供日志用的短名。 */
    static String shortName(final ScriptNode node) {
        if (node instanceof Action action) {
            return action.action().toLowerCase(Locale.ROOT);
        }
        if (node instanceof Repeat) {
            return "repeat";
        }
        if (node instanceof While) {
            return "while";
        }
        if (node instanceof If) {
            return "if";
        }
        return "waitUntil";
    }
}
