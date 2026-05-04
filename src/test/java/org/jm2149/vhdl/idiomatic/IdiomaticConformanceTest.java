package org.jm2149.vhdl.idiomatic;

import org.jm2149.vhdl.VcdConformanceRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Conformance tests for {@link Ym2149AudioIdiomatic}.
 *
 * <p>Replays the same NVC-generated VCD traces used by the original
 * {@code Ym2149AudioConformanceTest} and verifies that
 * {@code Ym2149AudioIdiomatic} produces bit-identical outputs at every
 * rising {@code clk_i} edge.
 */
class IdiomaticConformanceTest {

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
        final Ym2149AudioIdiomatic uut = new Ym2149AudioIdiomatic();

        VcdConformanceRunner.run(vcdZip, (en, sel, rst, bc, bdir, data,
                                          expCha, expChb, expChc, expMix, expPcm) -> {
            uut.risingEdge(en, sel, rst, bc, bdir, data);

            VcdConformanceRunner.assertOutputs(label,
                    expCha, expChb, expChc, expMix, expPcm,
                    uut.getChAO(), uut.getChBO(), uut.getChCO(),
                    uut.getMixAudioO(), uut.getPcm14sO());
        });
    }
}
