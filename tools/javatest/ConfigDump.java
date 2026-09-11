package com.mcai.bridge;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.StringWriter;

/**
 * 打印模组配置文件的**真实默认内容**。
 *
 * <p>这件事光看着 {@code BridgeConfig.java} 是推不准的：Forge 的配置分节由
 * {@code Builder.push()/pop()} 决定，而键名里带点号也会被拆成嵌套表。
 * 与其猜，不如让 Forge 自己把默认配置渲染出来 —— 这正是玩家打开
 * {@code config/mcai_bridge-client.toml} 会看到的东西。</p>
 *
 * <p>放在 com.mcai.bridge 包里是为了能直接访问包私有的 {@code BridgeConfig.SPEC}。
 * 这个类不参与模组打包，只用于开发期核对文档与实现是否一致。</p>
 *
 * <p>用法：{@code java -cp <classpath> com.mcai.bridge.ConfigDump}</p>
 */
public final class ConfigDump {

    public static void main(final String[] args) throws Exception {
        final CommentedConfig config = CommentedConfig.inMemory();
        // correct() 会用 spec 的默认值补齐空配置，等于生成全新配置文件
        final int corrected = BridgeConfig.SPEC.correct(config);

        final StringWriter out = new StringWriter();
        new TomlWriter().write(config, out);
        final String text = out.toString();

        // 直接由 Java 写文件，避免经过 shell 重定向时把编码搞乱
        if (args.length > 0) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), text,
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("已写入: " + args[0]);
        }

        System.out.println("===== config/mcai_bridge-client.toml（默认生成内容）=====");
        System.out.println(text);
        System.out.println("===== 补齐了 " + corrected + " 个键 =====");

        // 顺手把分节结构断言一遍，防止以后再出现「文档写的节名和实际不符」
        final String[] expectedSections = {"[connection]", "[reporting]", "[permissions]", "[limits]", "[debug]"};
        boolean allPresent = true;
        for (final String section : expectedSections) {
            final boolean present = text.contains(section);
            System.out.println((present ? "  [OK]   " : "  [MISS] ") + section);
            allPresent &= present;
        }
        final boolean noStrayAllowTable = !text.contains("[allow]");
        System.out.println((noStrayAllowTable ? "  [OK]   " : "  [MISS] ") + "没有遗留的 [allow] 平铺表");
        if (!allPresent || !noStrayAllowTable) {
            System.out.println("配置分节结构不符合预期！");
            System.exit(1);
        }
        System.out.println("配置分节结构符合预期。");
    }

    private ConfigDump() {
    }
}
