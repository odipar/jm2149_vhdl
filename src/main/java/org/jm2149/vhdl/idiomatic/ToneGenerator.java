package org.jm2149.vhdl.idiomatic;

/**
 * Tone counter and output flip-flop for one channel of the YM2149.
 *
 * <p>Models the {@code ch_x_cnt_r} / {@code tone_x_r} pair that appears
 * identically for channels A, B and C in the original VHDL.  The caller
 * is responsible for clock-enable gating: {@link #tick} should only be
 * invoked when the internal divided clock strobe ({@code en_int_clk_psg_s})
 * is asserted.
 *
 * <p>State correspondence (VHDL → Java):
 * <ul>
 *   <li>{@code ch_x_cnt_r} → {@code cntR}
 *   <li>{@code tone_x_r}   → {@code ffR}
 * </ul>
 */
public final class ToneGenerator {

    /** 12-bit period counter. */
    private int     cntR = 0;
    /** Output flip-flop (VHDL init {@code '1'}). */
    private boolean ffR  = true;

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance one internal-clock tick.
     *
     * @param period  12-bit tone period from the register file
     * @param enCntX  next-state of the count-enable (combinatorial, equals
     *                {@code clkDiv8R == 3})
     * @param enCntR  registered count-enable from the previous tick
     */
    public void tick(int period, boolean enCntX, boolean enCntR) {
        // --- Next-state counter ---
        int nextCnt;
        if (enCntX && cntR >= period) {
            nextCnt = 0;
        } else if (enCntR) {
            nextCnt = (cntR + 1) & 0xFFF;
        } else {
            nextCnt = cntR;
        }

        // --- Next-state flip-flop ---
        boolean nextFf;
        if (period < 6) {                        // flatline: period too short
            nextFf = true;
        } else if (enCntX && cntR >= period) {
            nextFf = !ffR;
        } else {
            nextFf = ffR;
        }

        cntR = nextCnt;
        ffR  = nextFf;
    }

    /**
     * Reset to VHDL initial state ({@code cnt=0, tone='1'}).
     */
    public void reset() {
        cntR = 0;
        ffR  = true;
    }

    // -----------------------------------------------------------------------
    // Outputs
    // -----------------------------------------------------------------------

    /**
     * Current tone output ({@code tone_x_r}).
     */
    public boolean output() {
        return ffR;
    }

    /**
     * Returns {@code true} when the period is below the flatline threshold
     * ({@code < 6}), meaning the generator is driven to a constant {@code '1'}.
     *
     * @param period  12-bit tone period
     */
    public static boolean isFlatline(int period) {
        return period < 6;
    }
}
