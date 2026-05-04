package org.jm2149.vhdl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
/**
 * Conformance test against the NVC-generated VCD for tb_feat_ym2149.
 *
 * <p>Reuses the replay engine from {@link Ym2149AudioConformanceTest}.
 *
 * <p>VCD file used:
 * {@code vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip}
 */
class FeatYm2149ConformanceTest {

    /** Path to the zipped VCD (relative to Maven project root). */
    private static final Path VCD_PATH = Path.of(
            "vhdl/simulations/ym2149_audio/commit_84bb268/tb_feat_ym2149.vcd.zip");

    @Test
    void conformance_tb_feat_ym2149() throws IOException {
        Assumptions.assumeTrue(Files.exists(VCD_PATH),
                "VCD file not found – skipping conformance test: " + VCD_PATH);

        Ym2149AudioConformanceTest.runConformance(VCD_PATH, "tb_feat_ym2149");
    }
}
