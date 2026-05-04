package org.jm2149.vhdl;

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
 * Conformance tests for {@link Ym2149Audio} against the VCD simulation output
 * from {@code vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip}.
 *
 * <p>The test replays all testbench stimuli (captured in the VCD) and verifies
 * that the Java model produces identical output signals at every timestamp where
 * the VHDL simulation records a change.
 *
 * <p>Signals verified:
 * <ul>
 *   <li>ch_a_o   – 12-bit channel-A DAC level</li>
 *   <li>ch_b_o   – 12-bit channel-B DAC level</li>
 *   <li>ch_c_o   – 12-bit channel-C DAC level</li>
 *   <li>mix_audio_o – 14-bit summed audio</li>
 *   <li>pcm14s_o – 14-bit signed PCM</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ym2149AudioTest {

    // -----------------------------------------------------------------------
    // VCD identifiers (top-level testbench scope)
    // -----------------------------------------------------------------------
    private static final String ID_SEL_N   = "#";
    private static final String ID_RESET_N = "$";
    private static final String ID_BC      = "%";
    private static final String ID_BDIR    = "&";
    private static final String ID_DATA_I  = "'";
    private static final String ID_CH_A    = ")";   // ch_a_o (tb scope)
    private static final String ID_CH_B    = "*";   // ch_b_o
    private static final String ID_CH_C    = "+";   // ch_c_o
    private static final String ID_MIX     = ",";   // mix_audio_o
    private static final String ID_PCM     = "-";   // pcm14s_o

    /** Clock period in femtoseconds (46.560852 ns). */
    private static final long CLK_PERIOD_FS = 46_560_852L;

    // -----------------------------------------------------------------------
    // Parsed simulation data (loaded once in @BeforeAll)
    // -----------------------------------------------------------------------

    /** All events keyed by timestamp (femtoseconds). */
    private final TreeMap<Long, List<VcdParser.VcdEvent>> eventsByTime = new TreeMap<>();

    /** Expected output values at every timestamp that has output changes. */
    private final TreeMap<Long, Map<String, Integer>> expectedOutputs = new TreeMap<>();

    // -----------------------------------------------------------------------
    // Test setup
    // -----------------------------------------------------------------------

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
    // Targeted unit tests
    // -----------------------------------------------------------------------

    /**
     * After a global reset, all channel DAC outputs must be 0 and the PCM
     * and mix outputs must also be 0 (the pipeline initially holds zeros).
     */
    @Test
    void resetClearsChannelOutputs() {
        Ym2149Audio uut = new Ym2149Audio();
        for (int i = 0; i < 30; i++) {
            uut.risingEdge(i % 6 == 2, false, false /*reset_n=0*/, false, false, 0);
        }
        assertEquals(0, uut.getChA(),     "ch_a should be 0 during reset");
        assertEquals(0, uut.getChB(),     "ch_b should be 0 during reset");
        assertEquals(0, uut.getChC(),     "ch_c should be 0 during reset");
        assertEquals(0, uut.getMixAudio(),"mix should be 0 during reset");
    }

    /**
     * Verify that channel A oscillates between 0x000 and 0xFFF when
     * programmed with max amplitude (R8=0x0F) and the mixer enables the
     * tone (R7=0x3E).  DAC entry 31 = 0xFFF for level 15.
     */
    @Test
    void channelAMaxAmplitudeTone() {
        Ym2149Audio uut = new Ym2149Audio();

        // Reset for a few PSG clocks
        for (int i = 0; i < 18; i++) {
            uut.risingEdge(i % 6 == 2, false, false, false, false, 0);
        }

        // R0=0x0A (period low=10, above flatline threshold of 6), R8=0x0F (max amplitude), R7=0x3E (enable A tone)
        writeReg(uut, 0x00, 0x0A);
        writeReg(uut, 0x08, 0x0F);
        writeReg(uut, 0x07, 0x3E);

        boolean sawHigh = false;
        boolean sawLow  = false;
        int clkPsgR = 0;
        for (int i = 0; i < 40000; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            uut.risingEdge(en, false, true, false, false, 0);
            if (uut.getChA() == 0xFFF) sawHigh = true;
            if (uut.getChA() == 0x000) sawLow  = true;
        }
        assertTrue(sawHigh, "ch_a should reach 0xFFF (DAC[31]=0xFFF for level 15)");
        assertTrue(sawLow,  "ch_a should reach 0x000 (tone low half-cycle)");
    }

    /**
     * Verify that the noise LFSR generates non-constant output over time.
     * With noise enabled on channel A (noise period > 4) and amplitude max,
     * ch_a_o should toggle between 0 and 0xFFF as the LFSR shifts.
     */
    @Test
    void noiseLfsrProducesVariation() {
        Ym2149Audio uut = new Ym2149Audio();

        for (int i = 0; i < 18; i++) {
            uut.risingEdge(i % 6 == 2, false, false, false, false, 0);
        }

        // R6=0x05 (noise period=5), R8=0x0F (max amplitude),
        // R7=0x37 (enable noise A: bit3=0, disable tone A: bit0=1)
        writeReg(uut, 0x06, 0x05);
        writeReg(uut, 0x08, 0x0F);
        writeReg(uut, 0x07, 0x37);

        Set<Integer> seenValues = new HashSet<>();
        int clkPsgR = 0;
        for (int i = 0; i < 40000; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            uut.risingEdge(en, false, true, false, false, 0);
            seenValues.add(uut.getChA());
        }
        assertTrue(seenValues.size() >= 2,
            "Noise LFSR should produce at least two distinct ch_a values; got: " + seenValues);
    }

    // -----------------------------------------------------------------------
    // Main conformance test
    // -----------------------------------------------------------------------

    /**
     * Drive the Java {@link Ym2149Audio} model with the same stimuli as the
     * VHDL testbench and assert that all output signals match the VCD at every
     * timestamp where the simulation records a change.
     *
     * <p>At least 250 distinct output-change timestamps must be verified to
     * ensure the test exercises meaningful behavior.
     */
    @Test
    void conformanceTest() {
        Ym2149Audio uut = new Ym2149Audio();

        boolean selNI   = false;
        boolean resetNI = false;
        boolean bcI     = false;
        boolean bdirI   = false;
        int     dataI   = 0;

        int clkPsgR = 0;

        // t=0 "delta-cycle" tick: VHDL clock process raises clk_i at t=0
        // delta-1, producing a rising edge with reset_n_i='0'.
        uut.risingEdge(clkPsgR == 2, selNI, resetNI, bcI, bdirI, dataI);
        clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;

        List<String> failures = new ArrayList<>();
        int checksPerformed = 0;

        long tMax  = expectedOutputs.isEmpty() ? 0L : expectedOutputs.lastKey();
        long tStop = tMax + CLK_PERIOD_FS * 4;

        for (long t = CLK_PERIOD_FS; t <= tStop; t += CLK_PERIOD_FS) {

            // Apply input changes that arrived in (prevEdge, currentEdge].
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
                checkOutput(failures, t, ID_CH_A, expected, uut.getChA());
                checkOutput(failures, t, ID_CH_B, expected, uut.getChB());
                checkOutput(failures, t, ID_CH_C, expected, uut.getChC());
                checkOutput(failures, t, ID_MIX,  expected, uut.getMixAudio());
                checkOutput(failures, t, ID_PCM,  expected, uut.getPcm14s());
            }
        }

        assertTrue(checksPerformed >= 250,
            "Expected ≥250 output checkpoints but only " + checksPerformed + " were checked");

        if (!failures.isEmpty()) {
            fail("Conformance failures (" + failures.size() + "):\n"
                    + String.join("\n", failures.subList(0, Math.min(failures.size(), 20))));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void checkOutput(List<String> failures, long ts,
                             String id, Map<String, Integer> expected, int actual) {
        Integer exp = expected.get(id);
        if (exp != null && exp != actual) {
            failures.add(String.format(
                "t=%.3fus %s: expected 0x%04X, got 0x%04X",
                ts / 1e9, id, exp, actual));
        }
    }

    /**
     * Helper: write one register via the BDIR/BC bus protocol (address-latch
     * then data-write) with reset_n_i=1 held throughout.
     */
    private void writeReg(Ym2149Audio uut, int regAddr, int regData) {
        int clkPsgR = 0;
        for (int phase = 0; phase < 3; phase++) {
            boolean bdir = (phase == 0 || phase == 1);
            boolean bc   = (phase == 0);
            int     data = (phase == 0) ? regAddr : (phase == 1 ? regData : 0);
            for (int i = 0; i < 12; i++) {
                boolean en = (clkPsgR == 2);
                clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
                uut.risingEdge(en, false, true, bc, bdir, data);
            }
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
