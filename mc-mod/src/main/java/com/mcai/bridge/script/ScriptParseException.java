package com.mcai.bridge.script;

/** 脚本解析失败。消息里一定带上出错的位置和「正确写法」，让 LLM 能自己改。 */
public class ScriptParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScriptParseException(final String message) {
        super(message);
    }
}
