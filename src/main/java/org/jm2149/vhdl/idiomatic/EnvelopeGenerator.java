package org.jm2149.vhdl.idiomatic;

/**
 * Envelope counter, flip-flop, shape counter, and FSM for the YM-2149.
 *
 * <p>Mirrors the envelope section of the VHDL {@code ym2149_audio} entity.
 * The shape counter drives a 5-bit index into the DAC ROM (32 logarithmic
 * amplitude entries).
 *
 * <h3>Correction vs the monolithic {@code Ym2149Audio}</h3>
 * The original {@code Ym2149Audio.risingEdge()} updates {@code envFfR} before
 * testing {@code envFfR != envFfX} in Processes 7 and 8, making that condition
 * always {@code false} and preventing the shape counter from ever advancing.
 * This implementation saves the edge-detect flag <em>before</em> the register
 * update, matching the simultaneous-update semantics of the VHDL source.
 */
public final class EnvelopeGenerator {

    // 16-bit envelope period counter
    private int     cntR        = 0;
    // Envelope flip-flop
    private boolean ffR         = true;
    // 5-bit envelope shape counter
    private int     shapeCntR   = 0;
    // Envelope FSM flip-flops
    private boolean continueFfR = true;
    private boolean attackFfR   = false;
    private boolean holdFfR     = false;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Current (pre-tick) raw envelope counter value (16-bit). */
    public int getCnt()         { return cntR; }

    /** Current (pre-tick) envelope flip-flop value. */
    public boolean getFf()      { return ffR; }

    /** Current (pre-tick) shape counter value (5-bit, 0–31). */
    public int getShapeCnt()    { return shapeCntR; }

    /** Current (pre-tick) continue flip-flop. */
    public boolean isContinue() { return continueFfR; }

    /** Current (pre-tick) attack flip-flop. */
    public boolean isAttack()   { return attackFfR; }

    /** Current (pre-tick) hold flip-flop. */
    public boolean isHold()     { return holdFfR; }

    /**
     * Compute the 5-bit DAC ROM index for the current envelope state.
     *
     * <p>Uses the CURRENT (pre-tick) internal state and the R13 attack bit.
     * Must be called <em>before</em> {@link #tick} to read the pre-update value.
     *
     * @param envAttackS  R13 bit 2 (attack flag from the register file)
     * @return 5-bit index in [0, 31]
     */
    public int getEnvOut(boolean envAttackS) {
        if (!continueFfR) {
            return 0;
        }
        // env_sel_s = env_attack_s XOR attack_ff_r (VHDL)
        boolean envSelS = envAttackS ? !attackFfR : attackFfR;
        return envSelS ? (shapeCntR & 0x1F) : ((~shapeCntR) & 0x1F);
    }

    /**
     * Convenience: look up the 12-bit amplitude in the given DAC ROM.
     *
     * @param dacrom     the 32-entry DAC ROM array
     * @param envAttackS R13 bit 2 (attack flag from the register file)
     * @return 12-bit unsigned envelope amplitude
     */
    public int getLevel(int[] dacrom, boolean envAttackS) {
        return dacrom[getEnvOut(envAttackS)];
    }

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance the envelope generator by one PSG tick.
     *
     * <p>Handles both the reset condition ({@code envRst}) and the normal
     * clock-enable path ({@code enIntClkPsgS}).  The caller is responsible for
     * asserting {@code envRst} whenever {@code reset_n_i = '0'} <em>or</em>
     * a new envelope shape has just been written to R13.
     *
     * @param enCntX       look-ahead count-enable (true when clk_div8_r == 3)
     * @param enCntR       registered count-enable from the previous tick
     * @param enIntClkPsgS internal PSG clock enable for this tick
     * @param envRst       envelope reset strobe (active high)
     * @param period       16-bit envelope period (from R12:R11)
     * @param continuous   R13 bit 3
     * @param attack       R13 bit 2
     * @param alternate    R13 bit 1
     * @param hold         R13 bit 0
     */
    public void tick(boolean enCntX, boolean enCntR,
                     boolean enIntClkPsgS, boolean envRst,
                     int period,
                     boolean continuous, boolean attack,
                     boolean alternate, boolean hold) {

        // ---- Envelope counter next-value ----
        int envCntX;
        if (enCntX && cntR >= period) {
            envCntX = 0;
        } else if (enCntR) {
            envCntX = (cntR + 1) & 0xFFFF;
        } else {
            envCntX = cntR;
        }

        // ---- Envelope flip-flop next-value ----
        boolean envFfX = (enCntX && cntR >= period) ? !ffR : ffR;

        // ---- FSM next-values (computed from CURRENT / old state) ----
        boolean continueFfX = (shapeCntR == 31) ? continuous  : continueFfR;
        boolean attackFfX   = (shapeCntR == 31 && alternate)  ? !attackFfR : attackFfR;
        boolean holdFfX     = (shapeCntR == 31) ? hold        : holdFfR;
        int     shapeCntX   = holdFfX ? 31 : (shapeCntR + 1) & 0x1F;

        // ---- Detect flip-flop edge BEFORE updating ffR ----
        // Preserves VHDL simultaneous-update semantics for Process 7 and 8.
        boolean envFfEdge = ffR != envFfX;

        // ---- Process 7: envelope counter / ff / shape counter ----
        if (envRst) {
            cntR      = 0;
            ffR       = true;
            shapeCntR = 0;
        } else if (enIntClkPsgS) {
            cntR = envCntX;
            ffR  = envFfX;
            if (!holdFfR && envFfEdge) {
                shapeCntR = shapeCntX;
            }
        }

        // ---- Process 8: FSM flip-flops ----
        if (envRst) {
            continueFfR = true;
            attackFfR   = false;
            holdFfR     = false;
        } else if (enIntClkPsgS) {
            if (!holdFfR && envFfEdge) {
                continueFfR = continueFfX;
                attackFfR   = attackFfX;
                holdFfR     = holdFfX;
            }
        }
    }

    /**
     * Reset all envelope state to power-on / hard-reset values.
     */
    public void reset() {
        cntR        = 0;
        ffR         = true;
        shapeCntR   = 0;
        continueFfR = true;
        attackFfR   = false;
        holdFfR     = false;
    }
}
