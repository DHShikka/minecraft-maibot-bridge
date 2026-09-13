package com.mcai.bridge.script;

/**
 * 脚本条件求值所需的世界状态。
 *
 * <p>把「条件判断要问世界的那些问题」抽成一个窄接口，好处是脚本的解析、控制流与条件求值
 * 全都能在纯 JVM 里测试 —— 真实实现读 Minecraft，测试实现读内存里的假数据。</p>
 */
public interface ScriptContext {

    boolean inWorld();

    /** 背包里某种物品的总数（支持模糊匹配，例如 {@code cobblestone}、{@code pickaxe}）。 */
    int countItem(String spec);

    /** 主手是否拿着某种物品。 */
    boolean holding(String spec);

    /** 当前血量。 */
    double health();

    double maxHealth();

    /** 饥饿值（0-20）。 */
    int food();

    /** 是否在某个坐标附近。 */
    boolean atPos(int x, int y, int z, int radius);

    /** 附近某种实体的数量。{@code typeSpec} 可以是类型名（zombie）、分类（hostile/animal/player）。 */
    int nearbyCount(String typeSpec, int radius);

    /** 当前是否还有别的动作在跑（用于「等它做完」这类条件）。 */
    boolean busy();

    /**
     * Baritone 是不是闲着（没在干活 / 已经停下来）。
     *
     * <p>为什么需要：Baritone 的活**不经过模组的动作队列**，{@link #busy()} 永远是 false，
     * 所以「等它把这段墙砌完再干下一步」这种脚本根本没法写。有了这个条件，
     * {@code {"waitUntil": {"condition": {"baritoneIdle": true}}}} 才是可靠的。</p>
     */
    boolean baritoneIdle();
}
