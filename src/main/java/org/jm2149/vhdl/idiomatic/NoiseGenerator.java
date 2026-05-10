package org.jm2149.vhdl.idiomatic;

/**
 * Noise counter, output flip-flop and LFSR for the YM2149.
 *
 * <p>Models the {@code noise_cnt_r} / {@code noise_ff_r} / {@code noise_lfsr_r}
 * signals from the VHDL core.  Like {@link ToneGenerator}, the caller must
 * gate invocations of {@link #tick} by the internal divided clock strobe
 * ({@code en_int_clk_psg_s}).
 *
 * <p>LFSR update rule: advance on every <em>rising</em> edge of
 * {@code noise_ff_r} (right-shift 17-bit register, feedback =
 * {@code lfsr[3] XOR lfsr[0]} into MSB).
 *
 * <p>State correspondence (VHDL → Java):
 * <ul>
 *   <li>{@code noise_cnt_r}  → {@code cntR}
 *   <li>{@code noise_ff_r}   → {@code ffR}
 *   <li>{@code noise_lfsr_r} → {@code lfsrR}
 * </ul>
 */
public final class NoiseGenerator {

    /** Default flatline threshold matching the VHDL specification. */
    public static final int DEFAULT_FLATLINE_THRESHOLD = 5;

    /** 5-bit period counter. */
    private int     cntR  = 0;
    /** Noise flip-flop (VHDL init {@code '1'}). */
    private boolean ffR   = true;
    /** 17-bit LFSR (VHDL init {@code 1_0000000000000000}). */
    private int     lfsrR = 0x1_0000;

    /** Noise periods strictly below this value are driven to a constant {@code '1'}. */
    private final int flatlineThreshold;

    /**
     * Construct a noise generator using the default VHDL flatline threshold
     * ({@value #DEFAULT_FLATLINE_THRESHOLD}).
     */
    public NoiseGenerator() {
        this(DEFAULT_FLATLINE_THRESHOLD);
    }

    /**
     * Construct a noise generator with a configurable flatline threshold.
     *
     * @param flatlineThreshold  noise periods strictly below this value are
     *                           driven to a constant {@code '1'}; use
     *                           {@value #DEFAULT_FLATLINE_THRESHOLD} for
     *                           VHDL-spec behaviour
     */
    public NoiseGenerator(int flatlineThreshold) {
        this.flatlineThreshold = flatlineThreshold;
    }

    // -----------------------------------------------------------------------
    // State transitions
    // -----------------------------------------------------------------------

    /**
     * Advance one internal-clock tick.
     *
     * @param period  5-bit noise period from register 6
     * @param enCntX  next-state count-enable ({@code clkDiv8R == 3})
     * @param enCntR  registered count-enable from the previous tick
     */
    public void tick(int period, boolean enCntX, boolean enCntR) {
        // --- Pre-compute LFSR next-state from OLD lfsrR ---
        boolean fb      = (((lfsrR >> 3) ^ lfsrR) & 1) != 0;
        int     nextLfsr = ((fb ? 1 : 0) << 16) | ((lfsrR >>> 1) & 0xFFFF);

        // --- Next-state counter ---
        int nextCnt;
        if (enCntX && cntR >= period) {
            nextCnt = 0;
        } else if (enCntR) {
            nextCnt = (cntR + 1) & 0x1F;
        } else {
            nextCnt = cntR;
        }

        // --- Next-state flip-flop ---
        boolean nextFf;
        if (period < flatlineThreshold) {        // flatline: period too short
            nextFf = true;
        } else if (enCntX && cntR >= period) {
            nextFf = !ffR;
        } else {
            nextFf = ffR;
        }

        // --- Save old flip-flop for rising-edge detect, then update ---
        boolean oldFf = ffR;
        cntR = nextCnt;
        ffR  = nextFf;

        // Rising-edge detect: advance LFSR only when noise_ff transitions 0→1
        if (!oldFf && nextFf) {
            lfsrR = nextLfsr;
        }
    }

    /**
     * Reset to VHDL initial state
     * ({@code cnt=0, ff='1', lfsr=0x10000}).
     */
    public void reset() {
        cntR  = 0;
        ffR   = true;
        lfsrR = 0x1_0000;
    }

    // -----------------------------------------------------------------------
    // Outputs
    // -----------------------------------------------------------------------

    /**
     * Current noise output ({@code noise_s = noise_lfsr_r(0)}).
     */
    public boolean output() {
        return (lfsrR & 1) != 0;
    }

    /**
     * Returns {@code true} when {@code period} is below this generator's
     * flatline threshold, meaning it would be driven to a constant {@code '1'}.
     *
     * @param period  5-bit noise period
     */
    public boolean isFlatline(int period) {
        return period < flatlineThreshold;
    }
}
