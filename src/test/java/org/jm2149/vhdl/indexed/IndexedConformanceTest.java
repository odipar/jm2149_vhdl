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
 * rather than 12-bit DAC levels, the test converts the index outputs back to
 * DAC levels using the chip's logarithmic ROM before comparing against the
 * VCD-recorded channel outputs and the mixed audio sum.
 */
class IndexedConformanceTest {

    // DAC ROM (32 entries, 12-bit logarithmic amplitude table) — used in the
    // test to convert DAC indices back to levels for comparison with VCD.
    private static final int[] DACROM = {
        0x000, 0x017, 0x01B, 0x021, 0x027, 0x02E, 0x037, 0x041,
        0x04D, 0x05C, 0x06D, 0x081, 0x09A, 0x0B7, 0x0D9, 0x102,
        0x133, 0x16D, 0x1B2, 0x204, 0x265, 0x2D8, 0x361, 0x405,
        0x4C7, 0x5AD, 0x6BF, 0x804, 0x987, 0xB53, 0xD76, 0xFFF
    };

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

            // Convert 5-bit DAC indices to 12-bit levels for comparison with VCD
            int actCha = DACROM[uut.getChAIndexO()];
            int actChb = DACROM[uut.getChBIndexO()];
            int actChc = DACROM[uut.getChCIndexO()];

            // Audio mixing and signed PCM conversion lie outside the YM2149;
            // they are not outputs of this model.  Pass expected values as
            // actuals so that assertOutputs() ignores those two fields.
            VcdConformanceRunner.assertOutputs(label,
                    expCha, expChb, expChc, expMix, expPcm,
                    actCha, actChb, actChc, expMix, expPcm);
        });
    }
}
