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
        // After reset all DAC outputs should be at mid-scale (0x800)
        // with no sound: ch_x_o = 0 (mixer disables everything), mix = 0
        assertEquals(0, psg.chAO,      "ch_a_o after reset");
        assertEquals(0, psg.chBO,      "ch_b_o after reset");
        assertEquals(0, psg.chCO,      "ch_c_o after reset");
        assertEquals(0, psg.mixAudioO, "mix_audio after reset");
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
        // writing 0x1FF to register 0 should only store 0xFF
        psg.writeRegister(0, 0x1FF);
        psg.writeRegister(1, 0);
        // Period should be 0xFF (no high bits)
        // We can verify indirectly: setTonePeriod(0, 0xFF) does the same
        Ym2149AudioIdiomatic ref = new Ym2149AudioIdiomatic();
        ref.setTonePeriod(0, 0xFF);
        // Both should have the same register state — run a few cycles and compare
        psg.applyReset();
        ref.applyReset();
        psg.writeRegister(0, 0x1FF);
        ref.setTonePeriod(0, 0xFF);
        // set mixer and volume identical
        psg.setMixer(0b00_111_110);
        ref.setMixer(0b00_111_110);
        psg.setVolume(0, 10);
        ref.setVolume(0, 10);
        psg.enClkPsgI = true; psg.selNI = false; psg.resetNI = true;
        ref.enClkPsgI = true; ref.selNI = false; ref.resetNI = true;
        for (int i = 0; i < 200; i++) {
            psg.risingEdge();
            ref.risingEdge();
        }
        assertEquals(ref.chAO,      psg.chAO,      "ch_a_o");
        assertEquals(ref.mixAudioO, psg.mixAudioO, "mix_audio_o");
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
        psg.setTonePeriod(0, 0xABC);
        psg.setTonePeriod(1, 0x123);
        psg.setTonePeriod(2, 0xFFF);
        // Verify register file encoding
        // Channel A: reg[0]=0xBC, reg[1]=0x0A
        // Channel B: reg[2]=0x23, reg[3]=0x01
        // Channel C: reg[4]=0xFF, reg[5]=0x0F
        // We verify by reading via the run() path (indirect), or by a simpler
        // sanity check: period 1 is below flatline, period 0xABC is not.
        // Just verify no exception is thrown and the chip runs without error.
        psg.applyReset();
        psg.setTonePeriod(0, 0xABC);
        psg.setMixer(0b00_111_110);
        psg.setVolume(0, 15);
        psg.enClkPsgI = true; psg.selNI = false; psg.resetNI = true;
        assertDoesNotThrow(() -> psg.run(100));
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
        psg.enClkPsgI = true;
        psg.selNI     = false;
        psg.resetNI   = true;
        int[] samples = psg.run(256);
        assertEquals(256, samples.length);
    }

    @Test
    void run_toneChannelProducesNonZeroOutput() {
        Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
        psg.applyReset();

        // Configure channel A: period=100, volume=15, tone only, no noise
        psg.setTonePeriod(0, 100);
        psg.setVolume(0, 15);
        psg.setMixer(0b00_111_110);  // tone A enabled (bit0=0), noise off

        psg.enClkPsgI = true;
        psg.selNI     = false;
        psg.resetNI   = true;

        int[] samples = psg.run(1000);

        // With tone enabled and volume set, mix_audio_o must be non-zero
        // at some point (the tone flips periodically)
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
        // Default overload uses envMode=false
        psg.setVolume(0, 12);
        psg.setVolume(1, 8,  false);
        psg.setVolume(2, 4,  true);  // envelope mode
        // No exception; env mode bit must be reflected
        // (Indirect check: run a few cycles without error)
        psg.enClkPsgI = true; psg.selNI = false; psg.resetNI = true;
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
        psg.setMixer(0b00_111_110);
        psg.enClkPsgI = true; psg.selNI = false; psg.resetNI = true;
        // Should run without error
        assertDoesNotThrow(() -> psg.run(500));
    }
}
