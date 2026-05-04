package org.jm2149.vhdl;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared VCD replay engine for conformance tests.
 *
 * <p>Parses a VCD (or zipped VCD) file and invokes a {@link RisingEdgeCallback}
 * at every rising {@code clk_i} edge, supplying the pre-edge input values and
 * the expected output values recorded in the VCD at the same timestamp.
 *
 * <p>Usage:
 * <pre>
 *   MyUut uut = new MyUut();
 *   VcdConformanceRunner.run(vcdPath, (en, sel, rst, bc, bdir, data,
 *                                      expCha, expChb, expChc, expMix, expPcm) -&gt; {
 *       uut.risingEdge(en, sel, rst, bc, bdir, data);
 *       VcdConformanceRunner.assertOutputs(label,
 *               expCha, expChb, expChc, expMix, expPcm,
 *               uut.getChAO(), uut.getChBO(), uut.getChCO(),
 *               uut.getMixAudioO(), uut.getPcm14sO());
 *   });
 * </pre>
 */
public final class VcdConformanceRunner {

    private VcdConformanceRunner() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Invoked once for every rising {@code clk_i} edge in the VCD trace.
     *
     * <p>The six {@code boolean}/{@code int} input parameters reflect the
     * signal state captured <em>before</em> the clock edge.  The five
     * {@code exp*} output parameters reflect the VCD-recorded output values
     * at the same timestamp (applied after the edge).
     *
     * @param en     en_clk_psg_i (pre-edge)
     * @param sel    sel_n_i      (pre-edge)
     * @param rst    reset_n_i    (pre-edge)
     * @param bc     bc_i         (pre-edge)
     * @param bdir   bdir_i       (pre-edge)
     * @param data   data_i       (pre-edge, 8-bit)
     * @param expCha expected ch_a_o      (from VCD, same timestamp)
     * @param expChb expected ch_b_o
     * @param expChc expected ch_c_o
     * @param expMix expected mix_audio_o
     * @param expPcm expected pcm14s_o
     */
    @FunctionalInterface
    public interface RisingEdgeCallback {
        void onRisingEdge(boolean en, boolean sel, boolean rst,
                          boolean bc, boolean bdir, int data,
                          int expCha, int expChb, int expChc, int expMix, int expPcm);
    }

    /**
     * Parse {@code vcdZip} and invoke {@code callback} at every rising
     * {@code clk_i} edge.
     *
     * <p>The VCD file may be a plain {@code .vcd} or a {@code .vcd.zip} /
     * {@code .zip} archive; the format is detected from the file extension.
     *
     * @param vcdZip   path to the (possibly zipped) VCD file
     * @param callback invoked once per rising edge with pre-edge inputs and
     *                 VCD-expected outputs
     * @throws IOException if the VCD file cannot be read
     */
    public static void run(Path vcdZip, RisingEdgeCallback callback) throws IOException {

        // VCD symbol constants (NVC single-char convention)
        final String S_CLK  = "!";   // clk_i
        final String S_EN   = "\"";  // en_clk_psg_i
        final String S_SEL  = "#";   // sel_n_i
        final String S_RST  = "$";   // reset_n_i
        final String S_BC   = "%";   // bc_i
        final String S_BDIR = "&";   // bdir_i
        final String S_DATA = "'";   // data_i[7:0]

        // Output symbol constants
        final String S_CHA  = "0";   // ch_a_o[11:0]
        final String S_CHB  = "1";   // ch_b_o[11:0]
        final String S_CHC  = "2";   // ch_c_o[11:0]
        final String S_MIX  = "3";   // mix_audio_o[13:0]
        final String S_PCM  = "4";   // pcm14s_o[13:0]

        // Current signal state (clk_i initialised to '1' per dumpvars)
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

        // Group accumulator: events at the current timestamp
        final long[]               groupTime   = {-1};
        final Map<String, Integer> groupEvents = new LinkedHashMap<>();

        // Apply accumulated group events to the current signal state
        Runnable applyGroup = () -> {
            for (Map.Entry<String, Integer> e : groupEvents.entrySet()) {
                switch (e.getKey()) {
                    case "!"  -> curClk[0]  = e.getValue();
                    case "\"" -> curEn[0]   = e.getValue();
                    case "#"  -> curSel[0]  = e.getValue();
                    case "$"  -> curRst[0]  = e.getValue();
                    case "%"  -> curBc[0]   = e.getValue();
                    case "&"  -> curBdir[0] = e.getValue();
                    case "'"  -> curData[0] = e.getValue();
                    case "0"  -> expCha[0]  = e.getValue();
                    case "1"  -> expChb[0]  = e.getValue();
                    case "2"  -> expChc[0]  = e.getValue();
                    case "3"  -> expMix[0]  = e.getValue();
                    case "4"  -> expPcm[0]  = e.getValue();
                    default   -> { /* ignore other internal signals */ }
                }
            }
            groupEvents.clear();
        };

        // Process a completed timestamp group
        final int[] prevClk = {1}; // matches dumpvars clk_i = '1'
        Runnable processGroup = () -> {
            Integer clkVal    = groupEvents.get(S_CLK);
            boolean hasClkRise = clkVal != null && clkVal == 1 && prevClk[0] == 0;

            if (hasClkRise) {
                // Capture pre-edge inputs before applyGroup updates them
                boolean en   = curEn[0]   != 0;
                boolean sel  = curSel[0]  != 0;
                boolean rst  = curRst[0]  != 0;
                boolean bc   = curBc[0]   != 0;
                boolean bdir = curBdir[0] != 0;
                int     data = curData[0];

                // Apply all events at this timestamp (updates exp* outputs too)
                applyGroup.run();

                // Deliver edge to callback with pre-edge inputs and updated exp* outputs
                callback.onRisingEdge(en, sel, rst, bc, bdir, data,
                        expCha[0], expChb[0], expChc[0], expMix[0], expPcm[0]);
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

    // -----------------------------------------------------------------------
    // Output assertion helper
    // -----------------------------------------------------------------------

    /**
     * Assert that the five UUT audio outputs match the expected values from
     * the VCD trace.  Fails immediately on the first mismatch.
     *
     * @param label   diagnostic label prepended to failure messages
     * @param expCha  expected ch_a_o
     * @param expChb  expected ch_b_o
     * @param expChc  expected ch_c_o
     * @param expMix  expected mix_audio_o
     * @param expPcm  expected pcm14s_o
     * @param actCha  actual ch_a_o
     * @param actChb  actual ch_b_o
     * @param actChc  actual ch_c_o
     * @param actMix  actual mix_audio_o
     * @param actPcm  actual pcm14s_o
     */
    public static void assertOutputs(String label,
                                     int expCha, int expChb, int expChc, int expMix, int expPcm,
                                     int actCha, int actChb, int actChc, int actMix, int actPcm) {
        if (actCha != expCha)
            fail(String.format("[%s] ch_a_o: expected 0x%03X, got 0x%03X",
                    label, expCha, actCha));
        if (actChb != expChb)
            fail(String.format("[%s] ch_b_o: expected 0x%03X, got 0x%03X",
                    label, expChb, actChb));
        if (actChc != expChc)
            fail(String.format("[%s] ch_c_o: expected 0x%03X, got 0x%03X",
                    label, expChc, actChc));
        if (actMix != expMix)
            fail(String.format("[%s] mix_audio_o: expected 0x%04X, got 0x%04X",
                    label, expMix, actMix));
        if (actPcm != expPcm)
            fail(String.format("[%s] pcm14s_o: expected 0x%04X, got 0x%04X",
                    label, expPcm, actPcm));
    }
}
