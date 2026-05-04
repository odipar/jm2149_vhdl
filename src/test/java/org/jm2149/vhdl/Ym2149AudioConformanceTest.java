package org.jm2149.vhdl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Conformance test against the NVC-generated VCD for tb_ym2149.
 *
 * <p>At every rising edge of {@code clk_i} in the VCD trace, the Java model
 * is driven with the same inputs and its outputs are compared to the UUT
 * output signals recorded in the VCD.  Any mismatch causes the test to fail
 * immediately.
 *
 * <p>VCD file used:
 * {@code vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip}
 */
class Ym2149AudioConformanceTest {

    /** Path to the zipped VCD (relative to Maven project root). */
    private static final Path VCD_PATH = Path.of(
            "vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip");

    @Test
    void conformance_tb_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_PATH),
                "VCD file not found – skipping conformance test: " + VCD_PATH);

        Ym2149AudioConformanceTest.runConformance(VCD_PATH, "tb_ym2149");
    }

    // -----------------------------------------------------------------------
    // Shared replay engine (also used by FeatYm2149ConformanceTest)
    // -----------------------------------------------------------------------

    /**
     * Parse the given VCD zip, replay every rising clock edge through a
     * fresh {@link Ym2149Audio} instance, and return the total mismatch count.
     *
     * @param vcdZip   path to the (possibly zipped) VCD file
     * @param label    label for diagnostic messages
     */
    static void runConformance(Path vcdZip, String label) throws IOException {
        final Ym2149Audio uut = new Ym2149Audio();

        // ---- VCD symbol constants (single-char, NVC convention) -----------
        // Input symbols (shared TB + UUT scope)
        final String S_CLK  = "!";   // clk_i
        final String S_EN   = "\"";  // en_clk_psg_i
        final String S_SEL  = "#";   // sel_n_i
        final String S_RST  = "$";   // reset_n_i
        final String S_BC   = "%";   // bc_i
        final String S_BDIR = "&";   // bdir_i
        final String S_DATA = "'";   // data_i[7:0]

        // Output symbols – UUT scope only
        final String S_CHA  = "0";   // ch_a_o[11:0]
        final String S_CHB  = "1";   // ch_b_o[11:0]
        final String S_CHC  = "2";   // ch_c_o[11:0]
        final String S_MIX  = "3";   // mix_audio_o[13:0]
        final String S_PCM  = "4";   // pcm14s_o[13:0]

        // ---- Mutable state shared between grouping logic and checker ------

        // Current signal state (initialized below from dumpvars)
        final int[] curClk  = {1};   // dumpvars: clk_i = '1'
        final int[] curEn   = {0};
        final int[] curSel  = {0};
        final int[] curRst  = {0};
        final int[] curBc   = {0};
        final int[] curBdir = {0};
        final int[] curData = {0};
        final int[] expCha  = {0};
        final int[] expChb  = {0};
        final int[] expChc  = {0};
        final int[] expMix  = {0};
        final int[] expPcm  = {0};

        // ---- Group accumulator: events at the current timestamp -----------
        final long[]              groupTime    = {-1};
        final Map<String, Integer> groupEvents = new LinkedHashMap<>();

        // ---- Helper: check outputs after risingEdge() --------------------
        // Called AFTER applyGroup(), so expected values already updated.
        // Fails immediately on the first mismatch.
        Runnable checkOutputs = () -> {
            if (uut.chAO      != expCha[0])
                fail(String.format("[%s] ch_a_o: expected 0x%03X, got 0x%03X",
                        label, expCha[0], uut.chAO));
            if (uut.chBO      != expChb[0])
                fail(String.format("[%s] ch_b_o: expected 0x%03X, got 0x%03X",
                        label, expChb[0], uut.chBO));
            if (uut.chCO      != expChc[0])
                fail(String.format("[%s] ch_c_o: expected 0x%03X, got 0x%03X",
                        label, expChc[0], uut.chCO));
            if (uut.mixAudioO != expMix[0])
                fail(String.format("[%s] mix_audio_o: expected 0x%04X, got 0x%04X",
                        label, expMix[0], uut.mixAudioO));
            if (uut.pcm14sO   != expPcm[0])
                fail(String.format("[%s] pcm14s_o: expected 0x%04X, got 0x%04X",
                        label, expPcm[0], uut.pcm14sO));
        };

        // ---- Helper: apply accumulated group events to current state ------
        Runnable applyGroup = () -> {
            for (Map.Entry<String, Integer> e : groupEvents.entrySet()) {
                String sym = e.getKey();
                int    val = e.getValue();
                switch (sym) {
                    case "!" -> curClk[0]  = val;
                    case "\"" -> curEn[0]  = val;
                    case "#" -> curSel[0]  = val;
                    case "$" -> curRst[0]  = val;
                    case "%" -> curBc[0]   = val;
                    case "&" -> curBdir[0] = val;
                    case "'" -> curData[0] = val;
                    case "0" -> expCha[0]  = val;
                    case "1" -> expChb[0]  = val;
                    case "2" -> expChc[0]  = val;
                    case "3" -> expMix[0]  = val;
                    case "4" -> expPcm[0]  = val;
                    default -> { /* ignore other internal signals */ }
                }
            }
            groupEvents.clear();
        };

        // ---- Helper: process a completed timestamp group -----------------
        final int[] prevClk = {1}; // matches dumpvars clk_i = '1'
        Runnable processGroup = () -> {
            boolean hasClkRise = groupEvents.containsKey(S_CLK)
                               && groupEvents.get(S_CLK) == 1
                               && prevClk[0] == 0;

            if (hasClkRise) {
                // Drive UUT with pre-edge inputs, simulate the rising edge.
                uut.enClkPsgI = curEn[0]   != 0;
                uut.selNI     = curSel[0]  != 0;
                uut.resetNI   = curRst[0]  != 0;
                uut.bcI       = curBc[0]   != 0;
                uut.bdirI     = curBdir[0] != 0;
                uut.dataI     = curData[0];
                uut.risingEdge();

                // Apply all events at this timestamp (including new outputs).
                applyGroup.run();

                // Compare UUT outputs to VCD-expected values.
                checkOutputs.run();
            } else {
                applyGroup.run();
            }
            prevClk[0] = curClk[0];
        };

        // ---- VCD listener -------------------------------------------------
        VcdParser.VcdListener listener = new VcdParser.VcdListener() {
            @Override
            public void onChange(long timeFs, String symbol, int value) {
                if (timeFs != groupTime[0]) {
                    if (groupTime[0] >= 0 && !groupEvents.isEmpty()) {
                        processGroup.run();
                    }
                    groupTime[0] = timeFs;
                }
                groupEvents.put(symbol, value);
            }

            @Override
            public void onEnd() {
                if (!groupEvents.isEmpty()) {
                    processGroup.run();
                }
            }
        };

        // ---- Parse --------------------------------------------------------
        try (FileInputStream fis = new FileInputStream(vcdZip.toFile())) {
            if (vcdZip.toString().endsWith(".zip")) {
                VcdParser.parseZip(fis, listener);
            } else {
                VcdParser.parse(fis, listener);
            }
        }
    }
}
