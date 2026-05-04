package org.jm2149.vhdl.optimized;

import org.jm2149.vhdl.VcdParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Conformance tests for {@link Ym2149AudioOptimized}.
 *
 * <p>Replays the same NVC-generated VCD traces used by the original
 * {@code Ym2149AudioConformanceTest} and verifies that
 * {@code Ym2149AudioOptimized} produces bit-identical outputs at every
 * rising {@code clk_i} edge.
 */
class OptimizedConformanceTest {

    private static final Path VCD_CE6654E = Path.of(
            "vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip");

    private static final Path VCD_84BB268 = Path.of(
            "vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip");

    @Test
    void conformance_tb_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_CE6654E),
                "VCD file not found – skipping: " + VCD_CE6654E);
        runConformance(VCD_CE6654E, "tb_ym2149");
    }

    @Test
    void conformance_tb_feat_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_84BB268),
                "VCD file not found – skipping: " + VCD_84BB268);
        runConformance(VCD_84BB268, "tb_feat_ym2149");
    }

    // -----------------------------------------------------------------------
    // Shared replay engine
    // -----------------------------------------------------------------------

    /**
     * Parse the given VCD zip, replay every rising clock edge through a fresh
     * {@link Ym2149AudioOptimized} instance, and fail immediately on any
     * output mismatch.
     *
     * @param vcdZip path to the (possibly zipped) VCD file
     * @param label  label for diagnostic messages
     */
    static void runConformance(Path vcdZip, String label) throws IOException {
        final Ym2149AudioOptimized uut = new Ym2149AudioOptimized();

        // VCD symbol constants (NVC single-char convention)
        final String S_CLK  = "!";
        final String S_EN   = "\"";
        final String S_SEL  = "#";
        final String S_RST  = "$";
        final String S_BC   = "%";
        final String S_BDIR = "&";
        final String S_DATA = "'";

        final String S_CHA  = "0";
        final String S_CHB  = "1";
        final String S_CHC  = "2";
        final String S_MIX  = "3";
        final String S_PCM  = "4";

        final int[] curClk  = {1};
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

        final long[]               groupTime   = {-1};
        final Map<String, Integer> groupEvents = new LinkedHashMap<>();

        Runnable checkOutputs = () -> {
            if (uut.getChAO()      != expCha[0])
                fail(String.format("[%s] ch_a_o: expected 0x%03X, got 0x%03X",
                        label, expCha[0], uut.getChAO()));
            if (uut.getChBO()      != expChb[0])
                fail(String.format("[%s] ch_b_o: expected 0x%03X, got 0x%03X",
                        label, expChb[0], uut.getChBO()));
            if (uut.getChCO()      != expChc[0])
                fail(String.format("[%s] ch_c_o: expected 0x%03X, got 0x%03X",
                        label, expChc[0], uut.getChCO()));
            if (uut.getMixAudioO() != expMix[0])
                fail(String.format("[%s] mix_audio_o: expected 0x%04X, got 0x%04X",
                        label, expMix[0], uut.getMixAudioO()));
            if (uut.getPcm14sO()   != expPcm[0])
                fail(String.format("[%s] pcm14s_o: expected 0x%04X, got 0x%04X",
                        label, expPcm[0], uut.getPcm14sO()));
        };

        Runnable applyGroup = () -> {
            for (Map.Entry<String, Integer> e : groupEvents.entrySet()) {
                String sym = e.getKey();
                int    val = e.getValue();
                if      (sym.equals(S_CLK))  curClk[0]  = val;
                else if (sym.equals(S_EN))   curEn[0]   = val;
                else if (sym.equals(S_SEL))  curSel[0]  = val;
                else if (sym.equals(S_RST))  curRst[0]  = val;
                else if (sym.equals(S_BC))   curBc[0]   = val;
                else if (sym.equals(S_BDIR)) curBdir[0] = val;
                else if (sym.equals(S_DATA)) curData[0] = val;
                else if (sym.equals(S_CHA))  expCha[0]  = val;
                else if (sym.equals(S_CHB))  expChb[0]  = val;
                else if (sym.equals(S_CHC))  expChc[0]  = val;
                else if (sym.equals(S_MIX))  expMix[0]  = val;
                else if (sym.equals(S_PCM))  expPcm[0]  = val;
                // other internal signals are silently ignored
            }
            groupEvents.clear();
        };

        final int[] prevClk = {1};
        Runnable processGroup = () -> {
            boolean hasClkRise = groupEvents.containsKey(S_CLK)
                               && groupEvents.get(S_CLK) == 1
                               && prevClk[0] == 0;

            if (hasClkRise) {
                uut.risingEdge(curEn[0] != 0, curSel[0] != 0, curRst[0] != 0,
                               curBc[0] != 0, curBdir[0] != 0, curData[0]);

                applyGroup.run();
                checkOutputs.run();
            } else {
                applyGroup.run();
            }
            prevClk[0] = curClk[0];
        };

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

        try (FileInputStream fis = new FileInputStream(vcdZip.toFile())) {
            if (vcdZip.toString().endsWith(".zip")) {
                VcdParser.parseZip(fis, listener);
            } else {
                VcdParser.parse(fis, listener);
            }
        }
    }
}
