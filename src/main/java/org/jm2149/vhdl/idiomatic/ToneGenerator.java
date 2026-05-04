package org.jm2149.vhdl.idiomatic;

/**
 * Per-channel tone counter and flip-flop for one YM-2149 tone channel (A, B, or C).
 *
 * <p>Mirrors the per-channel counter / flip-flop logic in the VHDL
 * {@code ym2149_audio} entity.  The three tone channels are identical in
 * structure; factoring them into this class eliminates the repeated code in
 * the monolithic {@code Ym2149Audio} implementation.
 *
 * <p>Flatline threshold: period values below {@value #FLATLINE_THRESHOLD}
 * force the tone output permanently {@code true} (DC level), matching the
 * VHDL behaviour.
 */
public final class ToneGenerator {

    /** Period values strictly below this threshold cause the tone to flatline. */
    public static final int FLATLINE_THRESHOLD = 6;

    // 12-bit tone period counter
    private int     cntR  = 0;
    // Tone flip-flop output
    private boolean toneR = true;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Current (pre-tick) tone flip-flop value. */
    public boolean getTone() { return toneR; }

    /** Current (pre-tick) raw counter value (12-bit). */
    public int getCnt() { return cntR; }

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance the tone counter and flip-flop by one PSG tick.
     *
     * <p>Must only be called when {@code en_int_clk_psg_s} is {@code true}.
     *
     * @param enCntX  look-ahead count-enable (true when {@code clk_div8_r == 3})
     * @param enCntR  registered count-enable from the previous tick
     * @param period  12-bit tone period from the register file
     */
    public void tick(boolean enCntX, boolean enCntR, int period) {
        boolean flat = isFlatline(period);

        // Counter next-value (combinatorial equivalent of ch_x_cnt_x)
        int cntX;
        if (enCntX && cntR >= period) {
            cntX = 0;
        } else if (enCntR) {
            cntX = (cntR + 1) & 0xFFF;
        } else {
            cntX = cntR;
        }

        // Tone flip-flop next-value (combinatorial equivalent of tone_x_x)
        boolean toneX;
        if (flat) {
            toneX = true;
        } else if (enCntX && cntR >= period) {
            toneX = !toneR;
        } else {
            toneX = toneR;
        }

        // Register update
        cntR  = cntX;
        toneR = toneX;
    }

    /**
     * Reset the tone counter and flip-flop to their power-on / hard-reset values.
     */
    public void reset() {
        cntR  = 0;
        toneR = true;
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the given period is below the flatline
     * threshold, meaning the tone output is held at DC (high).
     *
     * @param period 12-bit tone period
     */
    public static boolean isFlatline(int period) {
        return period < FLATLINE_THRESHOLD;
    }
}
