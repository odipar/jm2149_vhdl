package org.jm2149.vhdl.idiomatic;

/**
 * Envelope generator for the YM2149: period counter, output flip-flop,
 * shape counter, and envelope-FSM flip-flops.
 *
 * <p>The caller must:
 * <ol>
 *   <li>Call {@link #reset()} when {@code env_rst_s} is asserted
 *       ({@code !reset_n_i || env_shape_wr_r}).
 *   <li>Call {@link #tick} when the internal divided clock strobe
 *       ({@code en_int_clk_psg_s}) is asserted (and reset is not active).
 *   <li>Call {@link #dacLevel(int[], boolean)} to retrieve the current
 *       DAC level <em>before</em> the next tick.
 * </ol>
 *
 * <p>State correspondence (VHDL → Java):
 * <ul>
 *   <li>{@code env_cnt_r}     → {@code cntR}
 *   <li>{@code env_ff_r}      → {@code ffR}
 *   <li>{@code shape_cnt_r}   → {@code shapeCntR}
 *   <li>{@code continue_ff_r} → {@code continueFfR}
 *   <li>{@code attack_ff_r}   → {@code attackFfR}
 *   <li>{@code hold_ff_r}     → {@code holdFfR}
 * </ul>
 */
public final class EnvelopeGenerator {

    /** 16-bit period counter. */
    private int     cntR        = 0;
    /** Envelope period flip-flop (VHDL init {@code '1'}). */
    private boolean ffR         = true;
    /** 5-bit shape counter. */
    private int     shapeCntR   = 0;
    /** Continue flag (VHDL init {@code '1'}). */
    private boolean continueFfR = true;
    /** Attack flag (VHDL init {@code '0'}). */
    private boolean attackFfR   = false;
    /** Hold flag (VHDL init {@code '0'}). */
    private boolean holdFfR     = false;

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance one internal-clock tick.
     *
     * <p>Must NOT be called when {@code env_rst_s} is asserted; call
     * {@link #reset()} instead.
     *
     * @param period       16-bit envelope period (registers 11–12)
     * @param enCntX       next-state count-enable ({@code clkDiv8R == 3})
     * @param enCntR       registered count-enable from the previous tick
     * @param envContinue  bit 3 of register 13
     * @param envAttack    bit 2 of register 13
     * @param envAlternate bit 1 of register 13
     * @param envHold      bit 0 of register 13
     */
    public void tick(int period, boolean enCntX, boolean enCntR,
                     boolean envContinue, boolean envAttack,
                     boolean envAlternate, boolean envHold) {

        // --- Next-state counter ---
        int nextCnt;
        if (enCntX && cntR >= period) {
            nextCnt = 0;
        } else if (enCntR) {
            nextCnt = (cntR + 1) & 0xFFFF;
        } else {
            nextCnt = cntR;
        }

        // --- Next-state period flip-flop ---
        boolean nextFf;
        if (enCntX && cntR >= period) {
            nextFf = !ffR;
        } else {
            nextFf = ffR;
        }

        // --- Shape counter and FSM next-states (from current state) ---
        boolean holdFfX     = (shapeCntR == 31) ? envHold      : holdFfR;
        int     shapeCntX   = holdFfX ? 0x1F : ((shapeCntR + 1) & 0x1F);
        boolean continueFfX = (shapeCntR == 31) ? envContinue   : continueFfR;
        boolean attackFfX   = (shapeCntR == 31 && envAlternate) ? !attackFfR : attackFfR;

        // --- Save pre-edge values for edge-detect, then update counter/ff ---
        boolean oldFf   = ffR;
        boolean oldHold = holdFfR;
        cntR = nextCnt;
        ffR  = nextFf;

        // Shape counter advances on any edge of env_ff, gated by hold
        if (!oldHold && (oldFf != nextFf)) {
            shapeCntR   = shapeCntX;
            continueFfR = continueFfX;
            attackFfR   = attackFfX;
            holdFfR     = holdFfX;
        }
    }

    /**
     * Reset to VHDL initial / envelope-reset state.
     *
     * <p>Must be called when {@code env_rst_s} is asserted
     * ({@code !reset_n_i || env_shape_wr_r}).
     */
    public void reset() {
        cntR        = 0;
        ffR         = true;
        shapeCntR   = 0;
        continueFfR = true;
        attackFfR   = false;
        holdFfR     = false;
    }

    // -----------------------------------------------------------------------
    // Outputs
    // -----------------------------------------------------------------------

    /**
     * Look up the current DAC output level using the chip's logarithmic
     * DAC ROM.
     *
     * <p>This method reads the current FSM state, so it must be called
     * <em>before</em> the next {@link #tick}.
     *
     * @param dacRom     32-entry DAC ROM shared with the parent chip
     * @param envAttack  bit 2 of register 13 (envelope attack flag)
     * @return           12-bit DAC level
     */
    public int dacLevel(int[] dacRom, boolean envAttack) {
        return dacRom[dacIndex(envAttack)];
    }

    /**
     * Return the current 5-bit DAC index (0–31) without performing the
     * DACROM lookup.
     *
     * <p>This method reads the current FSM state, so it must be called
     * <em>before</em> the next {@link #tick}.
     *
     * @param envAttack  bit 2 of register 13 (envelope attack flag)
     * @return           5-bit DAC index into the logarithmic ROM
     */
    public int dacIndex(boolean envAttack) {
        // env_sel_s = not attack_ff_r when env_attack_s='1' else attack_ff_r
        boolean envSelS = envAttack ? !attackFfR : attackFfR;

        if (!continueFfR) {
            return 0;
        } else if (!envSelS) {
            return (~shapeCntR) & 0x1F;  // bit-invert 5-bit value
        } else {
            return shapeCntR & 0x1F;
        }
    }
}
