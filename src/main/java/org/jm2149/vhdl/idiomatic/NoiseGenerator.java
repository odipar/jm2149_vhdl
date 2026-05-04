package org.jm2149.vhdl.idiomatic;

/**
 * Noise counter, LFSR, and flip-flop for the YM-2149 noise generator.
 *
 * <p>Mirrors the noise section of the VHDL {@code ym2149_audio} entity.
 * The 17-bit right-shift LFSR uses taps at bits 0 and 3 (XOR feedback).
 *
 * <p>Flatline threshold: noise period values below {@value #FLATLINE_THRESHOLD}
 * force the noise flip-flop permanently {@code true}.
 */
public final class NoiseGenerator {

    /** Period values strictly below this threshold cause the noise to flatline. */
    public static final int FLATLINE_THRESHOLD = 5;

    // 5-bit noise period counter
    private int     cntR  = 0;
    // Noise flip-flop
    private boolean ffR   = true;
    // 17-bit LFSR; reset value matches VHDL: b"1_0000_0000_0000_0000"
    private int     lfsrR = 0x10000;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /**
     * Current (pre-tick) noise output – bit 0 of the LFSR.
     */
    public boolean getNoise() { return (lfsrR & 1) != 0; }

    /** Current (pre-tick) raw counter value (5-bit). */
    public int getCnt() { return cntR; }

    /** Current (pre-tick) LFSR value (17-bit). */
    public int getLfsr() { return lfsrR; }

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance the noise counter, flip-flop, and LFSR by one PSG tick.
     *
     * <p>Must only be called when {@code en_int_clk_psg_s} is {@code true}.
     *
     * @param enCntX  look-ahead count-enable (true when {@code clk_div8_r == 3})
     * @param enCntR  registered count-enable from the previous tick
     * @param period  5-bit noise period from the register file
     */
    public void tick(boolean enCntX, boolean enCntR, int period) {
        boolean flat = isFlatline(period);

        // Counter next-value
        int cntX;
        if (enCntX && cntR >= period) {
            cntX = 0;
        } else if (enCntR) {
            cntX = (cntR + 1) & 0x1F;
        } else {
            cntX = cntR;
        }

        // Flip-flop next-value
        boolean ffX;
        if (flat) {
            ffX = true;
        } else if (enCntX && cntR >= period) {
            ffX = !ffR;
        } else {
            ffX = ffR;
        }

        // 17-bit LFSR: taps at bits 0 and 3 (XOR), right-shift
        int fb    = ((lfsrR >>> 3) ^ lfsrR) & 1;
        int lfsrX = ((fb << 16) | (lfsrR >>> 1)) & 0x1FFFF;

        // LFSR is clocked on the rising edge of the noise flip-flop
        if (!ffR && ffX) {
            lfsrR = lfsrX;
        }

        // Register updates
        cntR = cntX;
        ffR  = ffX;
    }

    /**
     * Reset the noise counter, flip-flop, and LFSR to their power-on values.
     */
    public void reset() {
        cntR  = 0;
        ffR   = true;
        lfsrR = 0x10000;
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the given period is below the flatline
     * threshold, meaning the noise flip-flop is held at DC (high).
     *
     * @param period 5-bit noise period
     */
    public static boolean isFlatline(int period) {
        return period < FLATLINE_THRESHOLD;
    }
}
