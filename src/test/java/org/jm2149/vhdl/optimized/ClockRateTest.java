package org.jm2149.vhdl.optimized;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link Ym2149AudioOptimized} can sustain the YM2149's
 * target clock rate of <b>2 MHz</b> using less than <b>10 % of a single
 * CPU thread</b>.
 *
 * <h2>Criterion</h2>
 * At 2 MHz the chip calls {@code risingEdge()} 2,000,000 times per second.
 * If the JVM can sustain {@code R} calls/second at 100 % of one thread,
 * the fraction of that thread consumed at 2 MHz is:
 * <pre>
 *   cpu% = (2,000,000 / R) × 100
 * </pre>
 * The test fails if {@code cpu% > 10}.
 *
 * <h2>Method</h2>
 * <ol>
 *   <li><b>Warm-up phase</b> ({@value #WARMUP_SECONDS} s) — ticks the chip
 *       as fast as possible to trigger JIT compilation of the hot path.</li>
 *   <li><b>Measurement phase</b> ({@value #MEASURE_SECONDS} s) — counts how
 *       many {@code risingEdge()} calls complete in a fixed wall-clock window;
 *       calls are batched in groups of {@value #BATCH_SIZE} to amortise the
 *       cost of {@code System.nanoTime()} checks.</li>
 * </ol>
 *
 * <p>The chip is driven with typical audio-playback inputs:
 * {@code enClkPsgI=true}, {@code selNI=false}, {@code resetNI=true},
 * all bus signals inactive.  No register writes occur — the common case
 * during continuous audio output — so the fast-path in
 * {@link Ym2149AudioOptimized#risingEdge} is exercised on every iteration.
 */
class ClockRateTest {

    /** Target YM2149 master-clock frequency in Hz. */
    private static final double TARGET_CLOCK_HZ = 2_000_000.0;

    /** Maximum CPU fraction (0–100) allowed at {@link #TARGET_CLOCK_HZ}. */
    private static final double MAX_CPU_PERCENT = 10.0;

    /** Warm-up duration in seconds (for JIT compilation). */
    private static final int WARMUP_SECONDS = 2;

    /** Measurement window in seconds. */
    private static final int MEASURE_SECONDS = 2;

    /**
     * Inner-loop batch size: the number of {@code risingEdge()} calls between
     * consecutive {@code System.nanoTime()} checks.  Amortises the overhead
     * of reading the high-resolution clock.
     */
    private static final int BATCH_SIZE = 10_000;

    // -----------------------------------------------------------------------

    @Test
    void optimized_handles_2MHz_within_10pct_cpu() {

        // ------------------------------------------------------------------
        // Warm-up: run until the JIT has compiled the hot path
        // ------------------------------------------------------------------
        Ym2149AudioOptimized warmupUut = new Ym2149AudioOptimized();
        int sink = 0;
        long warmupEndNs = System.nanoTime() + (long) WARMUP_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < warmupEndNs) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                warmupUut.risingEdge(true, false, true, false, false, 0);
                sink ^= warmupUut.getMixAudioO();
            }
        }

        // ------------------------------------------------------------------
        // Measurement: count ticks completed in MEASURE_SECONDS
        // ------------------------------------------------------------------
        Ym2149AudioOptimized uut = new Ym2149AudioOptimized();
        long measureEndNs = System.nanoTime() + (long) MEASURE_SECONDS * 1_000_000_000L;
        long tickCount    = 0;
        long t0           = System.nanoTime();

        while (System.nanoTime() < measureEndNs) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                uut.risingEdge(true, false, true, false, false, 0);
                sink ^= uut.getMixAudioO();
            }
            tickCount += BATCH_SIZE;
        }
        long elapsedNs = System.nanoTime() - t0;

        // Prevent the JIT from treating the risingEdge() loop as dead code.
        // Integer.MIN_VALUE is chosen because XOR of any set of int values
        // cannot equal it if the mix output is always non-negative (it never
        // will in practice), so the branch is never taken but the compiler
        // cannot prove it and must keep the accumulation live.
        if (sink == Integer.MIN_VALUE) throw new AssertionError("sink");

        // ------------------------------------------------------------------
        // Derive metrics
        // ------------------------------------------------------------------
        double ticksPerSecond = (double) tickCount / elapsedNs * 1_000_000_000.0;
        double cpuPct         = (TARGET_CLOCK_HZ / ticksPerSecond) * 100.0;

        System.out.printf("%n=== Clock Rate Test (Ym2149AudioOptimized) ===%n");
        System.out.printf("  Measured rate : %,.0f ticks/sec%n",    ticksPerSecond);
        System.out.printf("  Target clock  : %,.0f Hz (2 MHz)%n",   TARGET_CLOCK_HZ);
        System.out.printf("  CPU at 2 MHz  : %.2f%%%n",              cpuPct);
        System.out.printf("  Limit         : %.0f%%%n",              MAX_CPU_PERCENT);
        System.out.printf("  Result        : %s%n",
                cpuPct <= MAX_CPU_PERCENT ? "PASS" : "FAIL");

        assertTrue(cpuPct <= MAX_CPU_PERCENT,
                String.format(
                        "Ym2149AudioOptimized requires %.2f%% CPU at 2 MHz" +
                        " (limit %.0f%%; measured %.0f ticks/sec)",
                        cpuPct, MAX_CPU_PERCENT, ticksPerSecond));
    }
}
