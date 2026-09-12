package com.mcai.bridge.util;

/**
 * 卡死检测：一段时间内「既没移动、也没有实质进展」就判定卡住。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>任务卡住时原来的表现是<b>沉默地耗到超时</b>：一个 {@code mine_blocks} 或者脚本子动作
 * 可以拿着整个预算（脚本子动作甚至能拿到 10 分钟）什么都不干地杵在那里。
 * 从玩家角度看就是「执行到一半不动了」，而且没有任何提示 —— 这既难排查，也白等。</p>
 *
 * <p>做法借鉴 <a href="https://github.com/gaucho-matrero/altoclef">Altoclef</a> 的
 * {@code MovementProgressChecker} / {@code LinearProgressChecker}（MIT 许可，
 * 与本项目相同；这里是按本项目需要重写的实现，没有拷贝代码）：</p>
 *
 * <ul>
 *   <li>把时间切成固定长度的窗口；</li>
 *   <li>每个窗口结束时检查「有没有真的往前走」——位移够不够，或者进展计数涨没涨；</li>
 *   <li>连续若干个窗口都没进展才算卡死（给寻路重试、绕路留余地）；</li>
 *   <li>挖矿时玩家本来就不动，所以进展计数是必需的第二个信号。</li>
 * </ul>
 *
 * <h2>为什么不能用 {@code Task.progress()}</h2>
 *
 * <p>有些任务的 {@code progress()} 是<b>按时间</b>算的（追击、长按使用），站着不动也会涨。
 * 拿它当「有进展」的证据等于没检测。所以调用方要传的是真正只在推进时才增加的计数
 * （挖到几个、合成几个、打中几下），没有就给 -1。</p>
 *
 * <p>这个类是纯逻辑：时间由调用方传入，不读系统时钟，也不碰 Minecraft —— 因此能直接单测。</p>
 */
public final class ProgressWatchdog {

    /** 窗口长度（毫秒）。 */
    private final long windowMs;
    /** 一个窗口内至少要移动这么多格才算「有进展」。 */
    private final double minMoveBlocks;
    /** 进展计数至少要涨这么多才算「有进展」。 */
    private final double minProgressGain;
    /** 连续几个空窗口就判卡死。 */
    private final int strikesToFail;

    private boolean started;
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private double anchorProgress;
    private long windowStart;
    private long lastFeedAt;
    private int strikes;
    private boolean stuck;
    private long stuckForMs;

    /**
     * @param windowMs      单个窗口长度
     * @param strikesToFail 连续几个空窗口就判卡死（所以总共容忍的时间 = windowMs × strikesToFail）
     */
    public ProgressWatchdog(final long windowMs, final int strikesToFail) {
        this(windowMs, strikesToFail, 0.5, 0.001);
    }

    public ProgressWatchdog(final long windowMs, final int strikesToFail,
                            final double minMoveBlocks, final double minProgressGain) {
        this.windowMs = Math.max(500L, windowMs);
        this.strikesToFail = Math.max(1, strikesToFail);
        this.minMoveBlocks = minMoveBlocks;
        this.minProgressGain = minProgressGain;
    }

    /**
     * 喂一次观测。
     *
     * @param x        玩家 X
     * @param y        玩家 Y
     * @param z        玩家 Z
     * @param progress 真实进展计数（挖到几个/合成几个/打中几下）；-1 表示这个任务没有这种计数
     * @param nowMs    当前时间
     */
    public void feed(final double x, final double y, final double z,
                     final double progress, final long nowMs) {
        if (!started) {
            started = true;
            anchor(x, y, z, progress, nowMs);
            return;
        }

        // 观测中断（游戏暂停、窗口失焦、卡顿）不算卡死：直接把窗口挪到当下重新开始。
        // 否则「切出去一分钟再切回来」会立刻被判成卡死。
        if (nowMs - lastFeedAt > windowMs * 2) {
            anchor(x, y, z, progress, nowMs);
            return;
        }
        lastFeedAt = nowMs;

        if (moved(x, y, z) || progressed(progress)) {
            // 有进展 → 计数清零。要的是「**连续**多久没进展」，
            // 不是「累计多少个空窗口」：像熔炼这种 10 秒才出一次东西的活儿，
            // 窗口边界总会切出几个空窗，累计计数会把正常干活判成卡死（真机上抓到过）。
            strikes = 0;
            anchor(x, y, z, progress, nowMs);
            return;
        }

        if (nowMs - windowStart < windowMs) {
            return; // 这个窗口还没走完，继续观察
        }

        // 窗口走完却什么都没发生 —— 记一次
        strikes++;
        anchor(x, y, z, progress, nowMs);
        if (strikes >= strikesToFail && !stuck) {
            stuck = true;
            stuckForMs = (long) strikes * windowMs;
        }
    }

    /** 是否已经判定卡死。 */
    public boolean stuck() {
        return stuck;
    }

    /** 已经卡了多久（毫秒）。 */
    public long stuckForMs() {
        return stuckForMs;
    }

    /** 已经累计几次「没进展」。 */
    public int strikes() {
        return strikes;
    }

    private void anchor(final double x, final double y, final double z,
                        final double progress, final long nowMs) {
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        anchorProgress = progress;
        windowStart = nowMs;
        lastFeedAt = nowMs;
    }

    private boolean moved(final double x, final double y, final double z) {
        final double dx = x - anchorX;
        final double dy = y - anchorY;
        final double dz = z - anchorZ;
        return dx * dx + dy * dy + dz * dz >= minMoveBlocks * minMoveBlocks;
    }

    private boolean progressed(final double progress) {
        if (progress < 0 || anchorProgress < 0) {
            return false; // 这个任务没有可用的进展计数，只能靠位移判断
        }
        return progress - anchorProgress >= minProgressGain;
    }

    /** 卡死时给 LLM 看的原因。 */
    public String describe(final String whatHappened) {
        return "卡住了：最近约 " + Math.max(1, stuckForMs / 1000) + " 秒既没有移动，也没有任何进展（"
                + whatHappened + "）。"
                + "常见原因：游戏窗口失去焦点（单机时世界会暂停，点回游戏窗口即可）、"
                + "目标够不着或被方块挡死、需要先准备合适的工具或方块。"
                + "可以先 scan_blocks / get_state 看看周围实际情况，再决定绕过还是先挖开。";
    }
}
