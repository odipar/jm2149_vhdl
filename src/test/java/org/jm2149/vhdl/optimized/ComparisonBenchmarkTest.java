package org.jm2149.vhdl.optimized;

import org.jm2149.vhdl.VcdConformanceRunner;
import org.jm2149.vhdl.idiomatic.Ym2149AudioIdiomatic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Head-to-head throughput comparison: {@link Ym2149AudioOptimized} vs
 * {@link Ym2149AudioIdiomatic}.
 *
 * <p>The test pre-loads every rising {@code clk_i} edge from the NVC VCD
 * traces, runs both models through the resulting input sequence with a
 * JIT warm-up phase, then times them and reports:
 * <ul>
 *   <li>total rising edges replayed</li>
 *   <li>nanoseconds per edge (idiomatic)</li>
 *   <li>nanoseconds per edge (optimized)</li>
 *   <li>speedup factor (idiomatic / optimized)</li>
 * </ul>
 *
 * <p>The test <em>only</em> measures {@code risingEdge()} throughput;
 * VCD I/O is pre-processed before any timing starts so it does not
 * influence the results.
 *
 * <h2>Potential further improvements</h2>
 * <ol>
 *   <li><b>Skip generator ticks when enIntClkPsgS is false</b> —
 *       The fast path already exits early when {@code !enClkPsgI}, but even
 *       when {@code enClkPsgI=true} the internal strobe {@code enIntClkPsgS}
 *       can be low (when {@code selNR=false} and {@code selFfR=false}).
 *       Skipping generator tick calls in that case would eliminate another
 *       branch of work on every other clock cycle.</li>
 *   <li><b>Inline generator logic</b> — The {@code ToneGenerator},
 *       {@code NoiseGenerator}, and {@code EnvelopeGenerator} tick methods
 *       involve JVM virtual-dispatch and field accesses across object
 *       boundaries.  Merging their logic directly into
 *       {@code Ym2149AudioOptimized.risingEdge()} lets the JIT inline and
 *       fold them, often eliminating the object-boundary overhead entirely.</li>
 *   <li><b>Lazy PCM calculation</b> — The signed-PCM path
 *       ({@code signAX/signBX/signCX/pcm14sR}) is computed every cycle
 *       but only consumed when the caller reads {@code getPcm14sO()}.
 *       Deferring this work to the getter would save it on all cycles where
 *       only {@code getMixAudioO()} is needed.</li>
 *   <li><b>Pack booleans into a single {@code int} flags word</b> —
 *       Several per-cycle boolean locals (flatline flags, mixer outputs,
 *       mode flags) cause repeated boolean-to-int coercions and branch
 *       chains.  A pre-computed flags bitmask in {@code RegisterFile} could
 *       replace multiple comparisons with a single masked integer read.</li>
 *   <li><b>Replace {@code LinkedHashMap} in the VCD replay loop</b> —
 *       The conformance replay uses {@code LinkedHashMap<String, Integer>}
 *       for grouping events.  For single-character symbols a plain
 *       {@code int[128]} array indexed by character code eliminates
 *       hashing and boxing overhead entirely.</li>
 * </ol>
 */
class ComparisonBenchmarkTest {

    // -----------------------------------------------------------------------
    // VCD file paths (same as the conformance tests)
    // -----------------------------------------------------------------------

    private static final Path VCD_CE6654E = Path.of(
            "vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip");

    private static final Path VCD_84BB268 = Path.of(
            "vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip");

    // -----------------------------------------------------------------------
    // Benchmark parameters
    // -----------------------------------------------------------------------

    /** Number of replay rounds used exclusively for JIT warm-up (not timed). */
    private static final int WARMUP_ROUNDS = 5;

    /** Number of replay rounds to time. */
    private static final int MEASURE_ROUNDS = 10;

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void benchmark_tb_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_CE6654E),
                "VCD file not found – skipping: " + VCD_CE6654E);
        List<EdgeInput> edges = loadEdges(VCD_CE6654E);
        runBenchmark(edges, "tb_ym2149");
    }

    @Test
    void benchmark_tb_feat_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_84BB268),
                "VCD file not found – skipping: " + VCD_84BB268);
        List<EdgeInput> edges = loadEdges(VCD_84BB268);
        runBenchmark(edges, "tb_feat_ym2149");
    }

    // -----------------------------------------------------------------------
    // Benchmark engine
    // -----------------------------------------------------------------------

    /**
     * Run the head-to-head benchmark for a given pre-loaded edge sequence.
     *
     * <p>Executes {@link #WARMUP_ROUNDS} warm-up rounds (not timed) followed
     * by {@link #MEASURE_ROUNDS} measured rounds for each implementation and
     * prints the results to stdout.
     *
     * @param edges pre-loaded rising-edge input sequence
     * @param label trace label for display
     */
    private static void runBenchmark(List<EdgeInput> edges, String label) {
        // Convert to array for tighter inner-loop access
        EdgeInput[] arr = edges.toArray(new EdgeInput[0]);
        int total = arr.length;

        System.out.printf("%n=== Benchmark [%s] — %,d rising edges ===%n", label, total);

        // ------------------------------------------------------------------
        // JIT warm-up: run both models to let the JIT compile the hot path
        // ------------------------------------------------------------------

        System.out.printf("  Warming up (%d rounds each) …%n", WARMUP_ROUNDS);
        for (int r = 0; r < WARMUP_ROUNDS; r++) {
            replayIdiomatic(arr);
            replayOptimized(arr);
        }

        // ------------------------------------------------------------------
        // Timed measurement
        // ------------------------------------------------------------------

        System.out.printf("  Measuring  (%d rounds each) …%n", MEASURE_ROUNDS);

        long idiomaticNs = 0;
        long optimizedNs = 0;

        for (int r = 0; r < MEASURE_ROUNDS; r++) {
            idiomaticNs += replayIdiomatic(arr);
            optimizedNs += replayOptimized(arr);
        }

        long totalEdges = (long) total * MEASURE_ROUNDS;
        double nsPerEdgeIdiomatic  = (double) idiomaticNs  / totalEdges;
        double nsPerEdgeOptimized  = (double) optimizedNs  / totalEdges;
        double speedup             = nsPerEdgeIdiomatic  / nsPerEdgeOptimized;

        System.out.printf("  Idiomatic  : %,.2f ns/edge   (total %d ms)%n",
                nsPerEdgeIdiomatic,  idiomaticNs  / 1_000_000);
        System.out.printf("  Optimized  : %,.2f ns/edge   (total %d ms)%n",
                nsPerEdgeOptimized,  optimizedNs  / 1_000_000);
        System.out.printf("  Speedup    : %.2fx%n", speedup);
    }

    /**
     * Replay {@code edges} once through a fresh {@link Ym2149AudioIdiomatic}
     * instance and return the wall-clock nanoseconds consumed.
     *
     * <p>The return value of each {@code risingEdge()} is read via
     * {@link Ym2149AudioIdiomatic#getMixAudioO()} and accumulated into a sink
     * variable to prevent the JIT from eliminating the loop as dead code.
     */
    private static long replayIdiomatic(EdgeInput[] edges) {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();
        int sink = 0;
        long t0 = System.nanoTime();
        for (EdgeInput e : edges) {
            uut.risingEdge(e.en(), e.sel(), e.rst(), e.bc(), e.bdir(), e.data());
            sink ^= uut.getMixAudioO();
        }
        long elapsed = System.nanoTime() - t0;
        // Use sink so the JIT cannot eliminate the loop
        if (sink == Integer.MIN_VALUE) throw new AssertionError("sink");
        return elapsed;
    }

    /**
     * Replay {@code edges} once through a fresh {@link Ym2149AudioOptimized}
     * instance and return the wall-clock nanoseconds consumed.
     */
    private static long replayOptimized(EdgeInput[] edges) {
        Ym2149AudioOptimized uut = new Ym2149AudioOptimized();
        int sink = 0;
        long t0 = System.nanoTime();
        for (EdgeInput e : edges) {
            uut.risingEdge(e.en(), e.sel(), e.rst(), e.bc(), e.bdir(), e.data());
            sink ^= uut.getMixAudioO();
        }
        long elapsed = System.nanoTime() - t0;
        if (sink == Integer.MIN_VALUE) throw new AssertionError("sink");
        return elapsed;
    }

    // -----------------------------------------------------------------------
    // VCD pre-loader
    // -----------------------------------------------------------------------

    /**
     * Parse {@code vcdZip} and extract every rising {@code clk_i} edge as an
     * {@link EdgeInput}, in the order they occur in the simulation.
     *
     * @param vcdZip path to the (possibly zipped) VCD file
     * @return immutable-order list of pre-edge input tuples
     */
    static List<EdgeInput> loadEdges(Path vcdZip) throws IOException {
        final List<EdgeInput> result = new ArrayList<>();

        VcdConformanceRunner.run(vcdZip,
                (en, sel, rst, bc, bdir, data, expCha, expChb, expChc, expMix, expPcm) ->
                        result.add(new EdgeInput(en, sel, rst, bc, bdir, data)));

        return result;
    }

    // -----------------------------------------------------------------------
    // Edge input value type
    // -----------------------------------------------------------------------

    /**
     * All six chip inputs captured at a single rising {@code clk_i} edge.
     *
     * @param en    en_clk_psg_i
     * @param sel   sel_n_i
     * @param rst   reset_n_i
     * @param bc    bc_i
     * @param bdir  bdir_i
     * @param data  data_i (8-bit)
     */
    record EdgeInput(boolean en, boolean sel, boolean rst,
                     boolean bc, boolean bdir, int data) {}
}
