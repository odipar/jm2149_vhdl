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
 * <h2>Scenarios tested</h2>
 * <ol>
 *   <li><b>Idle playback</b> ({@code optimized_handles_2MHz_within_10pct_cpu}):
 *       No register writes — the fast-path is exercised on every cycle.</li>
 *   <li><b>PCM replay at 20 kHz</b>
 *       ({@code optimized_handles_2MHz_with_20kHz_writes_within_10pct_cpu}):
 *       Channel-A volume register is updated once every
 *       {@value #WRITE_INTERVAL} clock edges, matching the cadence of a
 *       20 kHz digital-to-analog replay algorithm that cycles through volume
 *       levels as fast as possible.  Each write calls
 *       {@link Ym2149AudioOptimized#writeRegister(int, int)}, which updates
 *       only the derived fields affected by that register — the worst-case
 *       workload for the register-caching hot path.</li>
 * </ol>
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
     * <p>Must be a multiple of {@link #WRITE_INTERVAL} so that the register-write
     * scenario always performs an integer number of complete write cycles per batch.
     */
    private static final int BATCH_SIZE = 10_000;

    /**
     * Number of clock edges between consecutive volume-register writes in the
     * PCM-replay scenario.  At 2 MHz this corresponds to a 20 kHz write rate:
     * 2,000,000 / {@value} = 20,000 writes per second.
     */
    private static final int WRITE_INTERVAL = 100;

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

    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link Ym2149AudioOptimized} stays within
     * {@value #MAX_CPU_PERCENT}% CPU at 2 MHz even when channel-A volume
     * is rewritten at <b>20 kHz</b> — the rate used by PCM-replay algorithms
     * that cycle the volume register as fast as possible to approximate a DAC.
     *
     * <p>Every {@value #WRITE_INTERVAL}th clock edge is preceded by a call to
     * {@link Ym2149AudioOptimized#writeRegister(int, int)} with the channel-A
     * volume register (register 8), cycling through all 16 volume levels.
     * This is the write-heavy worst-case for the {@link RegisterFile} caching:
     * the cached DAC level must be recomputed on every write.
     */
    @Test
    void optimized_handles_2MHz_with_20kHz_writes_within_10pct_cpu() {

        // ------------------------------------------------------------------
        // Warm-up: force JIT compilation of both the risingEdge hot path
        // and the writeRegister / RegisterFile update path
        // ------------------------------------------------------------------
        Ym2149AudioOptimized warmupUut = new Ym2149AudioOptimized();
        int sink = 0;
        long warmupEndNs = System.nanoTime() + (long) WARMUP_SECONDS * 1_000_000_000L;
        int wVol = 0;
        while (System.nanoTime() < warmupEndNs) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                if (i % WRITE_INTERVAL == 0) {
                    warmupUut.writeRegister(8, wVol & 0x0F);
                    wVol++;
                }
                warmupUut.risingEdge(true, false, true, false, false, 0);
                sink ^= warmupUut.getMixAudioO();
            }
        }

        // ------------------------------------------------------------------
        // Measurement: count ticks with 20 kHz register writes
        // ------------------------------------------------------------------
        Ym2149AudioOptimized uut = new Ym2149AudioOptimized();
        long measureEndNs = System.nanoTime() + (long) MEASURE_SECONDS * 1_000_000_000L;
        long tickCount    = 0;
        long t0           = System.nanoTime();
        int vol = 0;

        while (System.nanoTime() < measureEndNs) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                if (i % WRITE_INTERVAL == 0) {
                    uut.writeRegister(8, vol & 0x0F);
                    vol++;
                }
                uut.risingEdge(true, false, true, false, false, 0);
                sink ^= uut.getMixAudioO();
            }
            tickCount += BATCH_SIZE;
        }
        long elapsedNs = System.nanoTime() - t0;

        // Prevent the JIT from treating the loops as dead code.
        if (sink == Integer.MIN_VALUE) throw new AssertionError("sink");

        // ------------------------------------------------------------------
        // Derive metrics
        // ------------------------------------------------------------------
        double ticksPerSecond = (double) tickCount / elapsedNs * 1_000_000_000.0;
        double cpuPct         = (TARGET_CLOCK_HZ / ticksPerSecond) * 100.0;
        double writesPerSec   = ticksPerSecond / WRITE_INTERVAL;

        System.out.printf("%n=== Clock Rate Test — 20 kHz register writes (Ym2149AudioOptimized) ===%n");
        System.out.printf("  Measured rate : %,.0f ticks/sec%n",    ticksPerSecond);
        System.out.printf("  Write rate    : %,.0f writes/sec%n",   writesPerSec);
        System.out.printf("  Target clock  : %,.0f Hz (2 MHz)%n",   TARGET_CLOCK_HZ);
        System.out.printf("  CPU at 2 MHz  : %.2f%%%n",              cpuPct);
        System.out.printf("  Limit         : %.0f%%%n",              MAX_CPU_PERCENT);
        System.out.printf("  Result        : %s%n",
                cpuPct <= MAX_CPU_PERCENT ? "PASS" : "FAIL");

        assertTrue(cpuPct <= MAX_CPU_PERCENT,
                String.format(
                        "Ym2149AudioOptimized with 20 kHz writes requires %.2f%% CPU at 2 MHz" +
                        " (limit %.0f%%; measured %.0f ticks/sec, %.0f writes/sec)",
                        cpuPct, MAX_CPU_PERCENT, ticksPerSecond, writesPerSec));
    }
}
