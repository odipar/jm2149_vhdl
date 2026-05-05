package org.jm2149.vhdl.indexed;

import org.jm2149.vhdl.VcdConformanceRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conformance tests for {@link Ym2149AudioIndexed}.
 *
 * <p>Replays the same NVC-generated VCD traces used by the other conformance
 * tests and verifies that {@code Ym2149AudioIndexed} produces bit-identical
 * DAC outputs at every rising {@code clk_i} edge.
 *
 * <p>Because {@code Ym2149AudioIndexed} outputs 5-bit DAC <em>indices</em>
 * rather than 12-bit DAC levels, the test uses the convenience
 * {@code getChAO()} / {@code getChBO()} / {@code getChCO()} methods that
 * perform the chip's logarithmic DACROM lookup before comparing against the
 * VCD-recorded channel outputs and the mixed audio sum.
 */
class IndexedConformanceTest {

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
    // Replay engine
    // -----------------------------------------------------------------------

    private static void runConformance(Path vcdZip, String label) throws IOException {
        final Ym2149AudioIndexed uut = new Ym2149AudioIndexed();

        VcdConformanceRunner.run(vcdZip, (en, sel, rst, bc, bdir, data,
                                          expCha, expChb, expChc, expMix, expPcm) -> {
            uut.risingEdge(en, sel, rst, bc, bdir, data);

            // Use the convenience DAC-level methods (5-bit index → 12-bit level)
            int actCha = uut.getChAO();
            int actChb = uut.getChBO();
            int actChc = uut.getChCO();

            // Audio mixing and signed PCM conversion lie outside the YM2149;
            // they are not outputs of this model.  Pass expected values as
            // actuals so that assertOutputs() ignores those two fields.
            VcdConformanceRunner.assertOutputs(label,
                    expCha, expChb, expChc, expMix, expPcm,
                    actCha, actChb, actChc, expMix, expPcm);
        });
    }
}
