package org.jm2149.vhdl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conformance tests for {@link Ym2149Audio} against NVC-generated VCD traces.
 *
 * <p>At every rising edge of {@code clk_i} in the VCD trace the Java model is
 * driven with the same inputs and its outputs are compared to the UUT output
 * signals recorded in the VCD.  Any mismatch causes the test to fail
 * immediately.
 *
 * <p>VCD files used:
 * <ul>
 *   <li>{@code vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip}</li>
 *   <li>{@code vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip}</li>
 * </ul>
 */
class Ym2149AudioConformanceTest {

    private static final Path VCD_CE6654E = Path.of(
            "vhdl/simulations/ym2149_audio/commit_ce6654e/tb_ym2149.vcd.zip");

    private static final Path VCD_84BB268 = Path.of(
            "vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip");

    @Test
    void conformance_tb_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_CE6654E),
                "VCD file not found – skipping conformance test: " + VCD_CE6654E);
        runConformance(VCD_CE6654E, "tb_ym2149");
    }

    @Test
    void conformance_tb_feat_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_84BB268),
                "VCD file not found – skipping conformance test: " + VCD_84BB268);
        runConformance(VCD_84BB268, "tb_feat_ym2149");
    }

    // -----------------------------------------------------------------------
    // Replay engine
    // -----------------------------------------------------------------------

    private static void runConformance(Path vcdZip, String label) throws IOException {
        final Ym2149Audio uut = new Ym2149Audio();

        VcdConformanceRunner.run(vcdZip, (en, sel, rst, bc, bdir, data,
                                          expCha, expChb, expChc, expMix, expPcm) -> {
            uut.enClkPsgI = en;
            uut.selNI     = sel;
            uut.resetNI   = rst;
            uut.bcI       = bc;
            uut.bdirI     = bdir;
            uut.dataI     = data;
            uut.risingEdge();

            VcdConformanceRunner.assertOutputs(label,
                    expCha, expChb, expChc, expMix, expPcm,
                    uut.chAO, uut.chBO, uut.chCO, uut.mixAudioO, uut.pcm14sO);
        });
    }
}
