package com.mcai.bridge.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 解析好的脚本。
 *
 * @param name     人类可读的名字（可省略）
 * @param steps    顶层步骤
 * @param maxSteps 这个脚本最多执行多少个动作（默认 200）
 * @param maxDurationMs 最长执行时间（默认 10 分钟）
 */
public record ScriptProgram(String name, List<ScriptNode> steps, int maxSteps, long maxDurationMs) {

    public static final int DEFAULT_MAX_STEPS = 200;
    public static final long DEFAULT_MAX_DURATION_MS = 10 * 60 * 1000L;

    /** 顶层步骤总数的粗略统计（含嵌套），用于日志与安全阀提示。 */
    public int nodeCount() {
        return count(steps);
    }

    private static int count(final List<ScriptNode> nodes) {
        int total = 0;
        for (final ScriptNode node : nodes) {
            total++;
            if (node instanceof ScriptNode.Repeat repeat) {
                total += count(repeat.body());
            } else if (node instanceof ScriptNode.While loop) {
                total += count(loop.body());
            } else if (node instanceof ScriptNode.If branch) {
                total += count(branch.then());
                total += count(branch.otherwise());
            }
        }
        return total;
    }

    /**
     * 解析脚本。
     *
     * <p>接受两种写法：</p>
     * <pre>
     * {"steps": [ ... ], "name": "做把石剑"}      推荐
     * [ ... ]                                     直接给步骤数组也行
     * </pre>
     *
     * @throws ScriptParseException 结构不对，消息里带位置和正确写法
     */
    public static ScriptProgram parse(final JsonObject root) {
        if (root == null) {
            throw new ScriptParseException("脚本内容为空");
        }
        final JsonElement stepsElement = root.get("steps");
        if (stepsElement == null) {
            throw new ScriptParseException("脚本缺少 \"steps\" 字段。"
                    + "正确写法：{\"steps\": [ {\"action\": \"chat\", \"params\": {\"message\": \"你好\"}} ]}");
        }
        final String name = root.has("name") && root.get("name").isJsonPrimitive()
                ? root.get("name").getAsString() : "";
        final int maxSteps = root.has("maxSteps") && root.get("maxSteps").isJsonPrimitive()
                ? Math.max(1, Math.min(root.get("maxSteps").getAsInt(), 2000)) : DEFAULT_MAX_STEPS;
        final long maxDurationMs = root.has("maxDurationMs") && root.get("maxDurationMs").isJsonPrimitive()
                ? Math.max(1000L, root.get("maxDurationMs").getAsLong()) : DEFAULT_MAX_DURATION_MS;

        final int[] budget = {ScriptNode.MAX_NODES};
        final List<ScriptNode> steps = ScriptNode.parseSteps(stepsElement, "steps", budget);
        return new ScriptProgram(name, steps, maxSteps, maxDurationMs);
    }

    /** 方便测试与内部构造。 */
    public static ScriptProgram of(final String name, final List<ScriptNode> steps) {
        return new ScriptProgram(name, steps, DEFAULT_MAX_STEPS, DEFAULT_MAX_DURATION_MS);
    }

    public static ScriptProgram parseArray(final JsonArray array) {
        final JsonObject wrapper = new JsonObject();
        wrapper.add("steps", array);
        return parse(wrapper);
    }
}
