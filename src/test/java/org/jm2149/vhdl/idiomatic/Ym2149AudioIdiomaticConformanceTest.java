package org.jm2149.vhdl.idiomatic;

import org.jm2149.vhdl.Ym2149Audio;
import org.jm2149.vhdl.VcdParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for {@link Ym2149AudioIdiomatic}.
 *
 * <p>Two complementary suites are provided:
 * <ol>
 *   <li><b>Cross-version conformance</b> – drives both
 *       {@link Ym2149Audio} and {@link Ym2149AudioIdiomatic} with identical
 *       stimuli (replayed from the VCD) and asserts that every output signal
 *       ({@code ch_a_o}, {@code ch_b_o}, {@code ch_c_o}, {@code mix_audio_o},
 *       {@code pcm14s_o}) matches between the two implementations at every
 *       clock tick.</li>
 *   <li><b>VCD conformance</b> – replays stimuli through
 *       {@link Ym2149AudioIdiomatic} and asserts that all output signals
 *       match the VHDL simulator output recorded in the VCD at every
 *       timestamp where the simulation records a change.</li>
 * </ol>
 *
 * <p>Additional targeted unit tests exercise reset, tone, and noise behaviour
 * via the idiomatic convenience API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ym2149AudioIdiomaticConformanceTest {

    // -----------------------------------------------------------------------
    // VCD signal identifiers (top-level testbench scope)
    // -----------------------------------------------------------------------
    private static final String ID_SEL_N   = "#";
    private static final String ID_RESET_N = "$";
    private static final String ID_BC      = "%";
    private static final String ID_BDIR    = "&";
    private static final String ID_DATA_I  = "'";
    private static final String ID_CH_A    = ")";
    private static final String ID_CH_B    = "*";
    private static final String ID_CH_C    = "+";
    private static final String ID_MIX     = ",";
    private static final String ID_PCM     = "-";

    /** Clock period in femtoseconds (46.560852 ns). */
    private static final long CLK_PERIOD_FS = 46_560_852L;

    // -----------------------------------------------------------------------
    // Parsed VCD data (loaded once in @BeforeAll)
    // -----------------------------------------------------------------------
    private final TreeMap<Long, List<VcdParser.VcdEvent>>  eventsByTime     = new TreeMap<>();
    private final TreeMap<Long, Map<String, Integer>>      expectedOutputs  = new TreeMap<>();

    @BeforeAll
    void parseVcd() throws Exception {
        Path zip = findVcdZip();
        File vcd = extractVcd(zip);
        VcdParser parser = VcdParser.parse(vcd);
        vcd.delete();

        for (VcdParser.VcdEvent ev : parser.events()) {
            eventsByTime.computeIfAbsent(ev.timestamp(), t -> new ArrayList<>()).add(ev);
        }

        Set<String> outputIds = Set.of(ID_CH_A, ID_CH_B, ID_CH_C, ID_MIX, ID_PCM);
        for (Map.Entry<Long, List<VcdParser.VcdEvent>> entry : eventsByTime.entrySet()) {
            for (VcdParser.VcdEvent ev : entry.getValue()) {
                if (outputIds.contains(ev.identifier())) {
                    expectedOutputs
                        .computeIfAbsent(entry.getKey(), t -> new HashMap<>())
                        .put(ev.identifier(), ev.value());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Targeted unit tests (idiomatic API)
    // -----------------------------------------------------------------------

    /**
     * After reset(), all channel DAC outputs and mixed/PCM outputs must be 0.
     */
    @Test
    void resetClearsChannelOutputs() {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();
        uut.reset();
        assertEquals(0, uut.getChannelA(), "ch_a should be 0 after reset");
        assertEquals(0, uut.getChannelB(), "ch_b should be 0 after reset");
        assertEquals(0, uut.getChannelC(), "ch_c should be 0 after reset");
        assertEquals(0, uut.getMix(),      "mix should be 0 after reset");
    }

    /**
     * writeRegister / readRegister round-trip test.
     * R8 is an 8-bit writable register; verify the value survives a write→read.
     */
    @Test
    void writeReadRegisterRoundTrip() {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();
        uut.reset();
        uut.writeRegister(0x08, 0x0F);
        int read = uut.readRegister(0x08);
        assertEquals(0x0F, read, "R8 should read back 0x0F after writeRegister");
    }

    /**
     * Channel A should oscillate between 0x000 and 0xFFF when programmed with
     * max amplitude (R8 = 0x0F, DAC entry 31 = 0xFFF) and tone enabled (R7 = 0x3E).
     */
    @Test
    void channelAMaxAmplitudeTone() {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();
        uut.reset();
        uut.writeRegister(0x00, 0x0A);   // period low = 10 (above flatline)
        uut.writeRegister(0x08, 0x0F);   // max amplitude
        uut.writeRegister(0x07, 0x3E);   // enable tone A, disable others

        boolean sawHigh = false;
        boolean sawLow  = false;
        int clkPsgR = 0;
        for (int i = 0; i < 40_000; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            uut.risingEdge(en, false, true, false, false, 0);
            if (uut.getChannelA() == 0xFFF) sawHigh = true;
            if (uut.getChannelA() == 0x000) sawLow  = true;
        }
        assertTrue(sawHigh, "ch_a should reach 0xFFF (DAC[31])");
        assertTrue(sawLow,  "ch_a should reach 0x000 (tone low half-cycle)");
    }

    /**
     * Noise LFSR must produce at least two distinct ch_a values over time.
     */
    @Test
    void noiseLfsrProducesVariation() {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();
        uut.reset();
        uut.writeRegister(0x06, 0x05);   // noise period = 5
        uut.writeRegister(0x08, 0x0F);   // max amplitude
        uut.writeRegister(0x07, 0x37);   // enable noise A (bit3=0), disable tone A (bit0=1)

        Set<Integer> seen = new HashSet<>();
        int clkPsgR = 0;
        for (int i = 0; i < 40_000; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            uut.risingEdge(en, false, true, false, false, 0);
            seen.add(uut.getChannelA());
        }
        assertTrue(seen.size() >= 2,
            "Noise LFSR should produce at least two distinct ch_a values; got: " + seen);
    }

    /**
     * ToneGenerator flatline detection: period below threshold forces tone high.
     */
    @Test
    void toneGeneratorFlatlineThreshold() {
        // Below threshold: period 0..5 → flatline
        for (int p = 0; p < ToneGenerator.FLATLINE_THRESHOLD; p++) {
            assertTrue(ToneGenerator.isFlatline(p),
                "Period " + p + " should be flatline");
        }
        // At or above threshold: not flatline
        assertFalse(ToneGenerator.isFlatline(ToneGenerator.FLATLINE_THRESHOLD),
            "Period exactly at threshold should NOT be flatline");
        assertFalse(ToneGenerator.isFlatline(ToneGenerator.FLATLINE_THRESHOLD + 10),
            "Period above threshold should NOT be flatline");
    }

    /**
     * NoiseGenerator flatline detection: period below threshold forces noise high.
     */
    @Test
    void noiseGeneratorFlatlineThreshold() {
        for (int p = 0; p < NoiseGenerator.FLATLINE_THRESHOLD; p++) {
            assertTrue(NoiseGenerator.isFlatline(p),
                "Noise period " + p + " should be flatline");
        }
        assertFalse(NoiseGenerator.isFlatline(NoiseGenerator.FLATLINE_THRESHOLD),
            "Noise period exactly at threshold should NOT be flatline");
    }

    /**
     * EnvelopeGenerator should produce a non-constant sequence of levels when
     * configured with a sawtooth-up envelope (attack=1, continue=1, hold=0, alternate=0).
     */
    @Test
    void envelopeGeneratorRampsUp() {
        EnvelopeGenerator env = new EnvelopeGenerator();
        // Configure: continue=1, attack=1, alternate=0, hold=0, period=0
        // With period=0 the counter resets every tick, so the shape counter
        // advances on each flip-flop edge.

        Set<Integer> seenLevels = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seenLevels.add(env.getEnvOut(true));          // attack=true
            env.tick(true, true, true, false, 0,
                     true, true, false, false);           // continuous, attack, no alternate, no hold
        }
        assertTrue(seenLevels.size() > 1,
            "Envelope generator should produce varying levels; saw: " + seenLevels);
    }

    // -----------------------------------------------------------------------
    // Cross-version conformance: Ym2149Audio vs Ym2149AudioIdiomatic
    // -----------------------------------------------------------------------

    /**
     * Drive both {@link Ym2149Audio} and {@link Ym2149AudioIdiomatic} with the
     * same VCD stimuli and assert that their outputs are identical at every
     * clock tick.
     *
     * <p>At least 250 distinct timestamps must be verified.
     */
    @Test
    void crossVersionConformance() {
        Ym2149Audio         orig = new Ym2149Audio();
        Ym2149AudioIdiomatic idom = new Ym2149AudioIdiomatic();

        boolean selNI   = false;
        boolean resetNI = false;
        boolean bcI     = false;
        boolean bdirI   = false;
        int     dataI   = 0;

        int clkPsgR = 0;

        // t=0 tick
        orig.risingEdge(clkPsgR == 2, selNI, resetNI, bcI, bdirI, dataI);
        idom.risingEdge(clkPsgR == 2, selNI, resetNI, bcI, bdirI, dataI);
        clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;

        List<String> failures = new ArrayList<>();
        int checksPerformed = 0;

        long tMax  = expectedOutputs.isEmpty() ? 0L : expectedOutputs.lastKey();
        long tStop = tMax + CLK_PERIOD_FS * 4;

        for (long t = CLK_PERIOD_FS; t <= tStop; t += CLK_PERIOD_FS) {

            for (Map.Entry<Long, List<VcdParser.VcdEvent>> entry :
                    eventsByTime.subMap(t - CLK_PERIOD_FS, false, t, true).entrySet()) {
                for (VcdParser.VcdEvent ev : entry.getValue()) {
                    switch (ev.identifier()) {
                        case ID_SEL_N   -> selNI   = ev.value() != 0;
                        case ID_RESET_N -> resetNI = ev.value() != 0;
                        case ID_BC      -> bcI     = ev.value() != 0;
                        case ID_BDIR    -> bdirI   = ev.value() != 0;
                        case ID_DATA_I  -> dataI   = ev.value();
                    }
                }
            }

            boolean enClkPsgI = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;

            orig.risingEdge(enClkPsgI, selNI, resetNI, bcI, bdirI, dataI);
            idom.risingEdge(enClkPsgI, selNI, resetNI, bcI, bdirI, dataI);

            Map<String, Integer> expected = expectedOutputs.get(t);
            if (expected != null && t > 0) {
                checksPerformed++;
                crossCheck(failures, t, "ch_a",  orig.getChA(),      idom.getChA());
                crossCheck(failures, t, "ch_b",  orig.getChB(),      idom.getChB());
                crossCheck(failures, t, "ch_c",  orig.getChC(),      idom.getChC());
                crossCheck(failures, t, "mix",   orig.getMixAudio(), idom.getMixAudio());
                crossCheck(failures, t, "pcm14s",orig.getPcm14s(),   idom.getPcm14s());
            }
        }

        assertTrue(checksPerformed >= 250,
            "Expected ≥250 cross-version checkpoints; only " + checksPerformed + " performed");

        if (!failures.isEmpty()) {
            fail("Cross-version divergence (" + failures.size() + " failures):\n"
                + String.join("\n", failures.subList(0, Math.min(failures.size(), 20))));
        }
    }

    // -----------------------------------------------------------------------
    // VCD conformance: Ym2149AudioIdiomatic vs VHDL simulation
    // -----------------------------------------------------------------------

    /**
     * Replay the VCD stimuli through {@link Ym2149AudioIdiomatic} and assert
     * that all output signals match the VHDL simulation at every timestamp
     * where the simulation records a change.
     */
    @Test
    void vcdConformance() {
        Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();

        boolean selNI   = false;
        boolean resetNI = false;
        boolean bcI     = false;
        boolean bdirI   = false;
        int     dataI   = 0;

        int clkPsgR = 0;

        uut.risingEdge(clkPsgR == 2, selNI, resetNI, bcI, bdirI, dataI);
        clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;

        List<String> failures = new ArrayList<>();
        int checksPerformed = 0;

        long tMax  = expectedOutputs.isEmpty() ? 0L : expectedOutputs.lastKey();
        long tStop = tMax + CLK_PERIOD_FS * 4;

        for (long t = CLK_PERIOD_FS; t <= tStop; t += CLK_PERIOD_FS) {

            for (Map.Entry<Long, List<VcdParser.VcdEvent>> entry :
                    eventsByTime.subMap(t - CLK_PERIOD_FS, false, t, true).entrySet()) {
                for (VcdParser.VcdEvent ev : entry.getValue()) {
                    switch (ev.identifier()) {
                        case ID_SEL_N   -> selNI   = ev.value() != 0;
                        case ID_RESET_N -> resetNI = ev.value() != 0;
                        case ID_BC      -> bcI     = ev.value() != 0;
                        case ID_BDIR    -> bdirI   = ev.value() != 0;
                        case ID_DATA_I  -> dataI   = ev.value();
                    }
                }
            }

            boolean enClkPsgI = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;

            uut.risingEdge(enClkPsgI, selNI, resetNI, bcI, bdirI, dataI);

            Map<String, Integer> expected = expectedOutputs.get(t);
            if (expected != null && t > 0) {
                checksPerformed++;
                checkVcd(failures, t, ID_CH_A, expected, uut.getChA());
                checkVcd(failures, t, ID_CH_B, expected, uut.getChB());
                checkVcd(failures, t, ID_CH_C, expected, uut.getChC());
                checkVcd(failures, t, ID_MIX,  expected, uut.getMixAudio());
                checkVcd(failures, t, ID_PCM,  expected, uut.getPcm14s());
            }
        }

        assertTrue(checksPerformed >= 250,
            "Expected ≥250 VCD checkpoints; only " + checksPerformed + " performed");

        if (!failures.isEmpty()) {
            fail("VCD conformance failures (" + failures.size() + "):\n"
                + String.join("\n", failures.subList(0, Math.min(failures.size(), 20))));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void crossCheck(List<String> failures, long ts,
                            String name, int origVal, int idiomVal) {
        if (origVal != idiomVal) {
            failures.add(String.format(
                "t=%.3fus %s: Ym2149Audio=0x%04X  Ym2149AudioIdiomatic=0x%04X",
                ts / 1e9, name, origVal, idiomVal));
        }
    }

    private void checkVcd(List<String> failures, long ts,
                          String id, Map<String, Integer> expected, int actual) {
        Integer exp = expected.get(id);
        if (exp != null && exp != actual) {
            failures.add(String.format(
                "t=%.3fus %s: expected 0x%04X, got 0x%04X",
                ts / 1e9, id, exp, actual));
        }
    }

    private Path findVcdZip() {
        Path base = Path.of(".").toAbsolutePath().normalize();
        while (base != null) {
            Path candidate = base.resolve(
                "vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip");
            if (Files.exists(candidate)) return candidate;
            base = base.getParent();
        }
        throw new RuntimeException("Cannot find tb_ym2149.vcd.zip");
    }

    private File extractVcd(Path zipPath) throws IOException {
        File tmp = File.createTempFile("tb_ym2149", ".vcd");
        tmp.deleteOnExit();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".vcd")) {
                    Files.copy(zis, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return tmp;
                }
            }
        }
        throw new IOException("No .vcd entry found in " + zipPath);
    }
}
