package org.jm2149.vhdl.optimized;

/**
 * YM2149 internal register file with cached decoded signal values.
 *
 * <p>Writing a register (via {@link #write}) instantly updates all signal
 * fields derived from that register.  The owning chip class can then read
 * pre-decoded values (periods, enable flags, flatline flags, envelope shape
 * bits, …) in O(1) without repeating the bit-masking and shift arithmetic
 * on every clock cycle.
 *
 * <p>Register-to-field mapping:
 * <pre>
 *  0–1   → chAPeriod  (12-bit)   flatlineA = chAPeriod &lt; toneFlatlineThreshold
 *  2–3   → chBPeriod  (12-bit)   flatlineB = chBPeriod &lt; toneFlatlineThreshold
 *  4–5   → chCPeriod  (12-bit)   flatlineC = chCPeriod &lt; toneFlatlineThreshold
 *  6     → noisePeriod (5-bit)   flatlineN = noisePeriod &lt; noiseFlatlineThreshold
 *  7     → mixer enable flags (active-low in hardware, stored as-is)
 *  8     → chAMode (bit 4)
 *  9     → chBMode (bit 4)
 *  10    → chCMode (bit 4)
 *  11–12 → envPeriod  (16-bit)
 *  13    → envContinue / envAttack / envAlternate / envHold
 * </pre>
 */
public final class RegisterFile {

    /** Raw register bytes (indexes 0–15). */
    private final int[] regs = new int[16];

    // -----------------------------------------------------------------------
    // Cached decoded signals — updated immediately on each register write
    // -----------------------------------------------------------------------

    // Channel tone periods (12-bit)
    private int chAPeriod = 0;
    private int chBPeriod = 0;
    private int chCPeriod = 0;

    // Mixer control (register 7) — active-low flags, mirroring hardware
    private boolean chAToneEnN  = false;
    private boolean chBToneEnN  = false;
    private boolean chCToneEnN  = false;
    private boolean chANoiseEnN = false;
    private boolean chBNoiseEnN = false;
    private boolean chCNoiseEnN = false;

    // Volume mode flags (register 8-10, bit 4 = envelope mode)
    private boolean chAMode = false;
    private boolean chBMode = false;
    private boolean chCMode = false;

    // Noise period (5-bit, register 6)
    private int noisePeriod = 0;

    // Envelope period (16-bit, registers 11–12)
    private int envPeriod = 0;

    // Envelope shape flags (register 13)
    private boolean envContinue  = false;
    private boolean envAttack    = false;
    private boolean envAlternate = false;
    private boolean envHold      = false;

    // Flatline flags — pre-computed from period comparisons
    private boolean flatlineA = true;
    private boolean flatlineB = true;
    private boolean flatlineC = true;
    private boolean flatlineN = true;

    // Flatline thresholds
    private final int toneFlatlineThreshold;
    private final int noiseFlatlineThreshold;

    /** Default tone flatline threshold matching the VHDL specification. */
    public static final int DEFAULT_TONE_FLATLINE_THRESHOLD  = 6;
    /** Default noise flatline threshold matching the VHDL specification. */
    public static final int DEFAULT_NOISE_FLATLINE_THRESHOLD = 5;

    /**
     * Construct a register file using the default VHDL flatline thresholds
     * (tone: {@value #DEFAULT_TONE_FLATLINE_THRESHOLD},
     *  noise: {@value #DEFAULT_NOISE_FLATLINE_THRESHOLD}).
     */
    public RegisterFile() {
        this(DEFAULT_TONE_FLATLINE_THRESHOLD, DEFAULT_NOISE_FLATLINE_THRESHOLD);
    }

    /**
     * Construct a register file with configurable flatline thresholds.
     *
     * @param toneFlatlineThreshold   tone periods strictly below this value are
     *                                flagged as flatline; use
     *                                {@value #DEFAULT_TONE_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     * @param noiseFlatlineThreshold  noise periods strictly below this value are
     *                                flagged as flatline; use
     *                                {@value #DEFAULT_NOISE_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     */
    public RegisterFile(int toneFlatlineThreshold, int noiseFlatlineThreshold) {
        this.toneFlatlineThreshold  = toneFlatlineThreshold;
        this.noiseFlatlineThreshold = noiseFlatlineThreshold;
    }

    // -----------------------------------------------------------------------
    // Reset
    // -----------------------------------------------------------------------

    /**
     * Clear all registers to zero and recalculate every cached signal.
     */
    public void reset() {
        for (int i = 0; i < 16; i++) regs[i] = 0;
        recalcAll();
    }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Write {@code value} to register {@code reg} (0–15) and update all
     * cached signals that depend on it.
     *
     * <p>Callers are responsible for range-checking {@code reg}.
     *
     * @param reg   register index (0–15)
     * @param value raw byte value
     */
    public void write(int reg, int value) {
        regs[reg] = value & 0xFF;
        updateCache(reg);
    }

    // -----------------------------------------------------------------------
    // Raw read
    // -----------------------------------------------------------------------

    /**
     * Read the raw byte stored in register {@code reg}.
     *
     * @param reg register index (0–15)
     */
    public int read(int reg) {
        return regs[reg];
    }

    // -----------------------------------------------------------------------
    // Cached-signal getters
    // -----------------------------------------------------------------------

    /** Channel A 12-bit tone period. */
    public int getChAPeriod()    { return chAPeriod; }
    /** Channel B 12-bit tone period. */
    public int getChBPeriod()    { return chBPeriod; }
    /** Channel C 12-bit tone period. */
    public int getChCPeriod()    { return chCPeriod; }

    /** 5-bit noise period. */
    public int getNoisePeriod()  { return noisePeriod; }

    /** 16-bit envelope period. */
    public int getEnvPeriod()    { return envPeriod; }

    /** Tone-enable (active-low) for channel A — register 7 bit 0. */
    public boolean isChAToneEnN()  { return chAToneEnN; }
    /** Tone-enable (active-low) for channel B — register 7 bit 1. */
    public boolean isChBToneEnN()  { return chBToneEnN; }
    /** Tone-enable (active-low) for channel C — register 7 bit 2. */
    public boolean isChCToneEnN()  { return chCToneEnN; }
    /** Noise-enable (active-low) for channel A — register 7 bit 3. */
    public boolean isChANoiseEnN() { return chANoiseEnN; }
    /** Noise-enable (active-low) for channel B — register 7 bit 4. */
    public boolean isChBNoiseEnN() { return chBNoiseEnN; }
    /** Noise-enable (active-low) for channel C — register 7 bit 5. */
    public boolean isChCNoiseEnN() { return chCNoiseEnN; }

    /** {@code true} when channel A uses the envelope generator (register 8 bit 4). */
    public boolean isChAMode()     { return chAMode; }
    /** {@code true} when channel B uses the envelope generator (register 9 bit 4). */
    public boolean isChBMode()     { return chBMode; }
    /** {@code true} when channel C uses the envelope generator (register 10 bit 4). */
    public boolean isChCMode()     { return chCMode; }

    /** Envelope CONTINUE bit (register 13 bit 3). */
    public boolean isEnvContinue()  { return envContinue; }
    /** Envelope ATTACK bit (register 13 bit 2). */
    public boolean isEnvAttack()    { return envAttack; }
    /** Envelope ALTERNATE bit (register 13 bit 1). */
    public boolean isEnvAlternate() { return envAlternate; }
    /** Envelope HOLD bit (register 13 bit 0). */
    public boolean isEnvHold()      { return envHold; }

    /** {@code true} when channel A tone period is below the flatline threshold. */
    public boolean isFlatlineA() { return flatlineA; }
    /** {@code true} when channel B tone period is below the flatline threshold. */
    public boolean isFlatlineB() { return flatlineB; }
    /** {@code true} when channel C tone period is below the flatline threshold. */
    public boolean isFlatlineC() { return flatlineC; }
    /** {@code true} when the noise period is below the flatline threshold. */
    public boolean isFlatlineN() { return flatlineN; }

    // -----------------------------------------------------------------------
    // Private cache helpers
    // -----------------------------------------------------------------------

    /** Recalculate every cached signal from the current raw registers. */
    private void recalcAll() {
        for (int r = 0; r <= 13; r++) updateCache(r);
    }

    /**
     * Update only the cached fields that depend on {@code reg}.
     * Registers 14–15 carry no decoded signals so they are silently skipped.
     */
    private void updateCache(int reg) {
        switch (reg) {
            case 0: case 1:
                chAPeriod = ((regs[1] & 0x0F) << 8) | (regs[0] & 0xFF);
                flatlineA = chAPeriod < toneFlatlineThreshold;
                break;
            case 2: case 3:
                chBPeriod = ((regs[3] & 0x0F) << 8) | (regs[2] & 0xFF);
                flatlineB = chBPeriod < toneFlatlineThreshold;
                break;
            case 4: case 5:
                chCPeriod = ((regs[5] & 0x0F) << 8) | (regs[4] & 0xFF);
                flatlineC = chCPeriod < toneFlatlineThreshold;
                break;
            case 6:
                noisePeriod = regs[6] & 0x1F;
                flatlineN   = noisePeriod < noiseFlatlineThreshold;
                break;
            case 7:
                chAToneEnN  = (regs[7] & 0x01) != 0;
                chBToneEnN  = (regs[7] & 0x02) != 0;
                chCToneEnN  = (regs[7] & 0x04) != 0;
                chANoiseEnN = (regs[7] & 0x08) != 0;
                chBNoiseEnN = (regs[7] & 0x10) != 0;
                chCNoiseEnN = (regs[7] & 0x20) != 0;
                break;
            case 8:
                chAMode = (regs[8] & 0x10) != 0;
                break;
            case 9:
                chBMode = (regs[9] & 0x10) != 0;
                break;
            case 10:
                chCMode = (regs[10] & 0x10) != 0;
                break;
            case 11: case 12:
                envPeriod = ((regs[12] & 0xFF) << 8) | (regs[11] & 0xFF);
                break;
            case 13:
                envContinue  = (regs[13] & 0x08) != 0;
                envAttack    = (regs[13] & 0x04) != 0;
                envAlternate = (regs[13] & 0x02) != 0;
                envHold      = (regs[13] & 0x01) != 0;
                break;
            default:
                break;
        }
    }
}
