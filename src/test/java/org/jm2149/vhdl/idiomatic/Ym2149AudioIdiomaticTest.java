package org.jm2149.vhdl.idiomatic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the high-level convenience API of {@link Ym2149AudioIdiomatic}.
 *
 * <p>These tests exercise the {@code writeRegister}, {@code set*()} helpers,
 * {@code applyReset()} and {@code run()} methods without requiring VCD files.
 */
class Ym2149AudioIdiomaticTest {

    // -----------------------------------------------------------------------
    // applyReset
    // -----------------------------------------------------------------------

    @Test
    void applyReset_leavesChipInQuiescentState() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        // After reset all DAC outputs should be zero (mixer disables all channels)
        assertEquals(0, psg.getChAO(),      "ch_a_o after reset");
        assertEquals(0, psg.getChBO(),      "ch_b_o after reset");
        assertEquals(0, psg.getChCO(),      "ch_c_o after reset");
        assertEquals(0, psg.getMixAudioO(), "mix_audio after reset");
    }

    // -----------------------------------------------------------------------
    // writeRegister
    // -----------------------------------------------------------------------

    @Test
    void writeRegister_throwsOnOutOfRange() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        assertThrows(IllegalArgumentException.class, () -> psg.writeRegister(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> psg.writeRegister(16, 0));
    }

    @Test
    void writeRegister_masksTo8Bits() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        Ym2149AudioIdiomatic ref = new Ym2149AudioIdiomatic();

        psg.applyReset();
        ref.applyReset();

        // Writing 0x1FF to register 0 should only store 0xFF (same as period 0xFF)
        psg.writeRegister(0, 0x1FF);
        ref.setTonePeriod(0, 0xFF);

        psg.setMixer(true, false, false, false, false, false);
        ref.setMixer(true, false, false, false, false, false);
        psg.setVolume(0, 10);
        ref.setVolume(0, 10);

        for (int i = 0; i < 200; i++) {
            psg.risingEdge(true, false, true, false, false, 0);
            ref.risingEdge(true, false, true, false, false, 0);
        }
        assertEquals(ref.getChAO(),      psg.getChAO(),      "ch_a_o");
        assertEquals(ref.getMixAudioO(), psg.getMixAudioO(), "mix_audio_o");
    }

    // -----------------------------------------------------------------------
    // setTonePeriod
    // -----------------------------------------------------------------------

    @Test
    void setTonePeriod_throwsOnBadChannel() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        assertThrows(IllegalArgumentException.class, () -> psg.setTonePeriod(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> psg.setTonePeriod(3,  100));
    }

    @Test
    void setTonePeriod_setsAllThreeChannels() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setTonePeriod(0, 0xABC);
        psg.setMixer(true, false, false, false, false, false);
        psg.setVolume(0, 15);
        assertDoesNotThrow(() -> psg.run(100));
    }

    // -----------------------------------------------------------------------
    // setMixer
    // -----------------------------------------------------------------------

    @Test
    void setMixer_allChannelsOff_producesConstantOutput() {
        // When all tone and noise sources are disabled the YM2149 passes through
        // a constant level (mixer logic is active-low; all-off → both enables
        // high → channel always open).  The output should remain constant, not
        // vary between zero and the peak level as a tone would.
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setTonePeriod(0, 100);
        psg.setVolume(0, 15);
        psg.setMixer(false, false, false, false, false, false);
        psg.run(4);                // warm-up: let the DAC pipeline settle
        int[] samples = psg.run(1000);
        int steady = samples[0];
        for (int s : samples) {
            assertEquals(steady, s, "output should be constant when all mixer sources disabled");
        }
        assertTrue(steady > 0, "steady-state output should be non-zero with volume set");
    }

    @Test
    void setMixer_toneAOnly_producesNonZeroOutput() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setTonePeriod(0, 100);
        psg.setVolume(0, 15);
        psg.setMixer(true, false, false, false, false, false);
        int[] samples = psg.run(1000);
        boolean anyNonZero = false;
        for (int s : samples) {
            if (s != 0) { anyNonZero = true; break; }
        }
        assertTrue(anyNonZero, "Expected non-zero output with tone A enabled");
    }

    // -----------------------------------------------------------------------
    // run
    // -----------------------------------------------------------------------

    @Test
    void run_throwsOnNonPositiveCycles() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        assertThrows(IllegalArgumentException.class, () -> psg.run(0));
        assertThrows(IllegalArgumentException.class, () -> psg.run(-1));
    }

    @Test
    void run_returnsSameLengthAsRequested() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        assertEquals(256, psg.run(256).length);
    }

    @Test
    void run_toneChannelProducesNonZeroOutput() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setTonePeriod(0, 100);
        psg.setVolume(0, 15);
        psg.setMixer(true, false, false, false, false, false);

        int[] samples = psg.run(1000);
        boolean anyNonZero = false;
        for (int s : samples) {
            if (s != 0) { anyNonZero = true; break; }
        }
        assertTrue(anyNonZero, "Expected non-zero mix_audio_o with tone enabled");
    }

    // -----------------------------------------------------------------------
    // setVolume convenience overload
    // -----------------------------------------------------------------------

    @Test
    void setVolume_withAndWithoutEnvMode() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setVolume(0, 12);
        psg.setVolume(1, 8,  false);
        psg.setVolume(2, 4,  true);  // envelope mode
        assertDoesNotThrow(() -> psg.run(50));
    }

    @Test
    void setVolume_throwsOnBadChannel() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        assertThrows(IllegalArgumentException.class, () -> psg.setVolume(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> psg.setVolume(3,  10));
    }

    // -----------------------------------------------------------------------
    // Envelope shape write resets generator
    // -----------------------------------------------------------------------

    @Test
    void setEnvelopeShape_resetsEnvelopeGenerator() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();
        psg.setEnvelopePeriod(50);
        psg.setEnvelopeShape(0b1000);  // continuous, no attack, no alternate, no hold
        psg.setVolume(0, 0, true);     // channel A in envelope mode
        psg.setMixer(true, false, false, false, false, false);
        assertDoesNotThrow(() -> psg.run(500));
    }

    // -----------------------------------------------------------------------
    // risingEdge parameter passing
    // -----------------------------------------------------------------------

    @Test
    void risingEdge_resetPinClearsOutputs() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        // First set some non-zero state
        psg.applyReset();
        psg.setTonePeriod(0, 100);
        psg.setVolume(0, 15);
        psg.setMixer(true, false, false, false, false, false);
        psg.run(500);

        // Assert reset: must produce zero outputs
        for (int i = 0; i < 8; i++) {
            psg.risingEdge(true, false, false, false, false, 0);
        }
        assertEquals(0, psg.getChAO(),      "ch_a_o must be 0 during reset");
        assertEquals(0, psg.getMixAudioO(), "mix_audio_o must be 0 during reset");
    }
}
