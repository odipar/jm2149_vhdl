package org.jm2149.vhdl.idiomatic;

/**
 * Structured, idiomatic Java implementation of the YM-2149 / AY-3-8910
 * Complex Sound Generator, based on the {@code ym2149_audio.vhd} specification
 * by Matthew Hagerty (https://dnotq.io).
 *
 * <p>This class produces bit-identical output to
 * {@link org.jm2149.vhdl.Ym2149Audio} for inputs that do not exercise the
 * envelope generator (see the note below).  It refactors the monolithic
 * {@code risingEdge()} body by delegating the three tone channels, the noise
 * generator, and the envelope generator to dedicated classes:
 * <ul>
 *   <li>{@link ToneGenerator} – one per channel (A, B, C)</li>
 *   <li>{@link NoiseGenerator}</li>
 *   <li>{@link EnvelopeGenerator}</li>
 * </ul>
 *
 * <h3>Bug fix: envelope shape counter</h3>
 * {@link org.jm2149.vhdl.Ym2149Audio} updates {@code envFfR} before testing
 * {@code envFfR != envFfX} (Processes 7 and 8), making the condition always
 * {@code false} so the shape counter never advances.  This implementation
 * captures the edge flag <em>before</em> the update, matching the VHDL
 * simultaneous-update semantics.
 *
 * <h3>Convenience methods</h3>
 * In addition to the low-level {@link #risingEdge} clock-cycle interface,
 * this class offers:
 * <ul>
 *   <li>{@link #reset()} – synchronous hard reset</li>
 *   <li>{@link #writeRegister(int, int)} – write one PSG register via the
 *       BDIR/BC bus protocol (address-latch then data-write)</li>
 *   <li>{@link #readRegister(int)} – read one PSG register</li>
 *   <li>{@link #getChannelA()}, {@link #getChannelB()}, {@link #getChannelC()}
 *       – readable aliases for the 12-bit DAC outputs</li>
 *   <li>{@link #getMix()} – alias for {@link #getMixAudio()}</li>
 *   <li>{@link #getPcm()} – alias for {@link #getPcm14s()}</li>
 * </ul>
 */
public final class Ym2149AudioIdiomatic {

    // -----------------------------------------------------------------------
    // DAC lookup table (32 entries, 12-bit values, logarithmic – 1.5 dB steps)
    // From ym2149_audio.vhd lines 361–369.
    // -----------------------------------------------------------------------
    static final int[] DACROM = {
        0x000, 0x017, 0x01B, 0x021, 0x027, 0x02E, 0x037, 0x041,
        0x04D, 0x05C, 0x06D, 0x081, 0x09A, 0x0B7, 0x0D9, 0x102,
        0x133, 0x16D, 0x1B2, 0x204, 0x265, 0x2D8, 0x361, 0x405,
        0x4C7, 0x5AD, 0x6BF, 0x804, 0x987, 0xB53, 0xD76, 0xFFF
    };

    // -----------------------------------------------------------------------
    // Registered bus-interface state
    // -----------------------------------------------------------------------
    private int     regAddrR = 0;
    private int     dataIR   = 0;
    private int     dataOR   = 0;
    private int     busctlR  = 0;
    private boolean selNR    = true;
    private boolean selFfR   = true;

    // -----------------------------------------------------------------------
    // Register file (16 × 8-bit)
    // -----------------------------------------------------------------------
    private final int[] regFile = new int[16];

    // DAC level registers (12-bit, reset = 0x800)
    private int chALevelR = 0x800;
    private int chBLevelR = 0x800;
    private int chCLevelR = 0x800;

    // -----------------------------------------------------------------------
    // Clock conditioning
    // -----------------------------------------------------------------------
    private int     clkDiv8R = 0;
    private boolean enCntR   = false;

    // -----------------------------------------------------------------------
    // Envelope shape-write strobe
    // -----------------------------------------------------------------------
    private boolean envShapeWrR = false;

    // -----------------------------------------------------------------------
    // Generator instances
    // -----------------------------------------------------------------------
    private final ToneGenerator     toneA    = new ToneGenerator();
    private final ToneGenerator     toneB    = new ToneGenerator();
    private final ToneGenerator     toneC    = new ToneGenerator();
    private final NoiseGenerator    noise    = new NoiseGenerator();
    private final EnvelopeGenerator envelope = new EnvelopeGenerator();

    // -----------------------------------------------------------------------
    // DAC / PCM output registers
    // -----------------------------------------------------------------------
    private int dacAR     = 0;
    private int dacBR     = 0;
    private int dacCR     = 0;
    private int sumAudioR = 0;

    private int signAR  = 0;
    private int signBR  = 0;
    private int signCR  = 0;
    private int pcm14sR = 0;

    // -----------------------------------------------------------------------
    // Output accessors
    // -----------------------------------------------------------------------

    /** 12-bit unsigned channel-A DAC output. */
    public int getChA()      { return dacAR; }

    /** 12-bit unsigned channel-B DAC output. */
    public int getChB()      { return dacBR; }

    /** 12-bit unsigned channel-C DAC output. */
    public int getChC()      { return dacCR; }

    /** 14-bit unsigned mixed audio output (sum of all channels). */
    public int getMixAudio() { return sumAudioR; }

    /** 14-bit unsigned PCM output (signed, stored as unsigned). */
    public int getPcm14s()   { return pcm14sR; }

    // -----------------------------------------------------------------------
    // Convenience output aliases
    // -----------------------------------------------------------------------

    /** Readable alias for {@link #getChA()}. */
    public int getChannelA() { return dacAR; }

    /** Readable alias for {@link #getChB()}. */
    public int getChannelB() { return dacBR; }

    /** Readable alias for {@link #getChC()}. */
    public int getChannelC() { return dacCR; }

    /** Readable alias for {@link #getMixAudio()}. */
    public int getMix()      { return sumAudioR; }

    /** Readable alias for {@link #getPcm14s()}. */
    public int getPcm()      { return pcm14sR; }

    /** 8-bit data-out register (updated during a register-read bus cycle). */
    public int getDataOut()  { return dataOR; }

    // -----------------------------------------------------------------------
    // Rising-edge simulation
    // -----------------------------------------------------------------------

    /**
     * Simulate one rising edge of the system clock ({@code clk_i}).
     *
     * <p>Follows the same two-phase structure as the VHDL RTL:
     * <ol>
     *   <li>Phase 1 – compute all combinatorial next-values from current state.</li>
     *   <li>Phase 2 – latch next-values into registers (all updates simultaneous).</li>
     * </ol>
     *
     * @param enClkPsgI  PSG clock-enable strobe (single-cycle high pulse)
     * @param selNI      clock-divide select (0 = divide-by-2, 1 = pass-through)
     * @param resetNI    active-low global reset
     * @param bcI        bus control BC1
     * @param bdirI      bus direction BDIR
     * @param dataI      8-bit data-bus input
     */
    public void risingEdge(boolean enClkPsgI, boolean selNI,
                           boolean resetNI,
                           boolean bcI, boolean bdirI, int dataI) {

        // ===================================================================
        // Phase 1 – combinatorial signals (all read from CURRENT state)
        // ===================================================================

        // ----- Bus interface -----------------------------------------------
        boolean enDataRdS = false;
        boolean enDataWrS = false;
        int     regAddrX  = regAddrR;

        switch (busctlR) {
            case 0b00 -> { /* inactive */ }
            case 0b01 -> enDataRdS = true;
            case 0b10 -> enDataWrS = true;
            default   -> {   // 0b11 – latch address
                if ((dataIR >>> 4) == 0) {
                    regAddrX = dataIR & 0xF;
                }
            }
        }

        boolean envShapeWrX = enDataWrS && (regAddrR == 0xD);

        // ----- DAC register-level conversion --------------------------------
        int dacLevel4    = dataIR & 0xF;
        int dacRegIdx    = (dacLevel4 == 0) ? 0 : (dacLevel4 * 2 + 1);
        int dacRegLevelS = DACROM[dacRegIdx];

        int chALevelX = (regAddrR == 0x8) ? dacRegLevelS : chALevelR;
        int chBLevelX = (regAddrR == 0x9) ? dacRegLevelS : chBLevelR;
        int chCLevelX = (regAddrR == 0xA) ? dacRegLevelS : chCLevelR;

        // ----- Register file name mappings ----------------------------------
        int     chAPeriodS    = ((regFile[1] & 0xF) << 8) | (regFile[0] & 0xFF);
        boolean chAToneEnNS   = (regFile[7] & 0x01) != 0;
        boolean chANoiseEnNS  = (regFile[7] & 0x08) != 0;
        boolean chAModeS      = (regFile[8] & 0x10) != 0;

        int     chBPeriodS    = ((regFile[3] & 0xF) << 8) | (regFile[2] & 0xFF);
        boolean chBToneEnNS   = (regFile[7] & 0x02) != 0;
        boolean chBNoiseEnNS  = (regFile[7] & 0x10) != 0;
        boolean chBModeS      = (regFile[9] & 0x10) != 0;

        int     chCPeriodS    = ((regFile[5] & 0xF) << 8) | (regFile[4] & 0xFF);
        boolean chCToneEnNS   = (regFile[7] & 0x04) != 0;
        boolean chCNoiseEnNS  = (regFile[7] & 0x20) != 0;
        boolean chCModeS      = (regFile[10] & 0x10) != 0;

        int     noisePeriodS  = regFile[6] & 0x1F;

        int     envPeriodS    = ((regFile[12] & 0xFF) << 8) | (regFile[11] & 0xFF);
        boolean envContinueS  = (regFile[13] & 0x08) != 0;
        boolean envAttackS    = (regFile[13] & 0x04) != 0;
        boolean envAlternateS = (regFile[13] & 0x02) != 0;
        boolean envHoldS      = (regFile[13] & 0x01) != 0;

        // ----- Sel / clock conditioning -------------------------------------
        boolean selFfX = !selFfR;

        boolean enIntClkPsgS = selNR ? enClkPsgI : (enClkPsgI && selFfR);

        int     clkDiv8X  = (clkDiv8R + 1) & 0x7;
        boolean enCntX    = (clkDiv8R == 3);
        // Save enCntR BEFORE Phase 2 updates it (Process 4 writes enCntR = enCntX).
        // Tone, noise, and envelope generators must use the OLD enCntR, exactly as
        // the original computed chACntX/toneAX using the pre-update enCntR field.
        boolean enCntROld = enCntR;

        // ----- Flatline flags (static for this tick) ------------------------
        boolean flatlineAS = ToneGenerator.isFlatline(chAPeriodS);
        boolean flatlineBS = ToneGenerator.isFlatline(chBPeriodS);
        boolean flatlineCS = ToneGenerator.isFlatline(chCPeriodS);
        boolean flatlineNS = NoiseGenerator.isFlatline(noisePeriodS);

        // ----- Noise and mixer (use PRE-TICK tone / noise state) ------------
        boolean noiseOut = noise.getNoise();

        boolean mixAS = (chAToneEnNS || toneA.getTone()) && (chANoiseEnNS || noiseOut);
        boolean mixBS = (chBToneEnNS || toneB.getTone()) && (chBNoiseEnNS || noiseOut);
        boolean mixCS = (chCToneEnNS || toneC.getTone()) && (chCNoiseEnNS || noiseOut);

        // ----- Envelope output (uses PRE-TICK envelope state) ---------------
        boolean envRstS      = !resetNI || envShapeWrR;
        int     dacEnvLevelS = envelope.getLevel(DACROM, envAttackS);

        // ----- Amplitude control --------------------------------------------
        int levelAS = mixAS ? (chAModeS ? dacEnvLevelS : chALevelR) : 0;
        int levelBS = mixBS ? (chBModeS ? dacEnvLevelS : chBLevelR) : 0;
        int levelCS = mixCS ? (chCModeS ? dacEnvLevelS : chCLevelR) : 0;

        // ----- PCM signed 14-bit --------------------------------------------
        int levelAEnvS = chAModeS ? dacEnvLevelS : chALevelR;
        int levelBEnvS = chBModeS ? dacEnvLevelS : chBLevelR;
        int levelCEnvS = chCModeS ? dacEnvLevelS : chCLevelR;

        int signAX = computeSign(levelAEnvS, chAToneEnNS, chANoiseEnNS, flatlineAS, flatlineNS, mixAS);
        int signBX = computeSign(levelBEnvS, chBToneEnNS, chBNoiseEnNS, flatlineBS, flatlineNS, mixBS);
        int signCX = computeSign(levelCEnvS, chCToneEnNS, chCNoiseEnNS, flatlineCS, flatlineNS, mixCS);

        // ===================================================================
        // Phase 2 – register updates (all simultaneously, as in VHDL)
        // ===================================================================

        // Process 1: register bus inputs (unconditional)
        selNR   = selNI;
        busctlR = (bdirI ? 2 : 0) | (bcI ? 1 : 0);
        dataIR  = dataI & 0xFF;

        // Process 3: sel_ff (only on en_clk_psg_i)
        if (enClkPsgI) {
            selFfR = selFfX;
        }

        // Process 2: register file and channel levels (reset / en_clk_psg_i)
        if (!resetNI) {
            regAddrR    = 0;
            dataOR      = 0;
            envShapeWrR = false;
            chALevelR   = 0x800;
            chBLevelR   = 0x800;
            chCLevelR   = 0x800;
            for (int i = 0; i < 16; i++) regFile[i] = 0;
        } else if (enClkPsgI) {
            regAddrR    = regAddrX;
            envShapeWrR = envShapeWrX;
            if (enDataRdS) {
                dataOR = regFile[regAddrR];
            } else if (enDataWrS) {
                regFile[regAddrR] = dataIR;
                chALevelR = chALevelX;
                chBLevelR = chBLevelX;
                chCLevelR = chCLevelX;
            }
        }

        // Process 4: clock divider and count enable (reset / en_int_clk_psg_s)
        if (!resetNI) {
            clkDiv8R = 0;
            enCntR   = false;
        } else if (enIntClkPsgS) {
            clkDiv8R = clkDiv8X;
            enCntR   = enCntX;
        }

        // Process 5: tone generators (reset / en_int_clk_psg_s)
        if (!resetNI) {
            toneA.reset();
            toneB.reset();
            toneC.reset();
        } else if (enIntClkPsgS) {
            toneA.tick(enCntX, enCntROld, chAPeriodS);
            toneB.tick(enCntX, enCntROld, chBPeriodS);
            toneC.tick(enCntX, enCntROld, chCPeriodS);
        }

        // Process 6: noise generator (reset / en_int_clk_psg_s)
        if (!resetNI) {
            noise.reset();
        } else if (enIntClkPsgS) {
            noise.tick(enCntX, enCntROld, noisePeriodS);
        }

        // Process 7+8: envelope generator (envRstS or en_int_clk_psg_s)
        envelope.tick(enCntX, enCntROld, enIntClkPsgS, envRstS,
                      envPeriodS, envContinueS, envAttackS, envAlternateS, envHoldS);

        // Process 9: DAC output registers (en_clk_psg_i only)
        if (enClkPsgI) {
            sumAudioR = (dacAR + dacBR + dacCR) & 0x3FFF;
            dacAR = levelAS & 0xFFF;
            dacBR = levelBS & 0xFFF;
            dacCR = levelCS & 0xFFF;
        }

        // Process 10: PCM signed registers (en_clk_psg_i only)
        if (enClkPsgI) {
            int pcm = signExtend12to14(signAR)
                    + signExtend12to14(signBR)
                    + signExtend12to14(signCR);
            pcm14sR = pcm & 0x3FFF;
            signAR = signAX & 0xFFF;
            signBR = signBX & 0xFFF;
            signCR = signCX & 0xFFF;
        }
    }

    // -----------------------------------------------------------------------
    // Convenience methods
    // -----------------------------------------------------------------------

    /**
     * Perform a synchronous hard reset.
     *
     * <p>Drives {@code reset_n_i = 0} for 30 system-clock cycles (enough to
     * flush all internal pipeline stages), then releases reset.
     */
    public void reset() {
        int clkPsgR = 0;
        for (int i = 0; i < 30; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            risingEdge(en, false, false, false, false, 0);
        }
    }

    /**
     * Write one PSG register using the BDIR/BC bus protocol.
     *
     * <p>Executes three phases (address-latch, data-write, inactive),
     * each lasting 12 system-clock cycles, with {@code sel_n_i = 0}
     * (divide-by-2) and {@code reset_n_i = 1}.
     *
     * @param addr register address (0–15)
     * @param data 8-bit register value
     */
    public void writeRegister(int addr, int data) {
        int clkPsgR = 0;
        for (int phase = 0; phase < 3; phase++) {
            boolean bdir = (phase < 2);
            boolean bc   = (phase == 0);
            int     d    = (phase == 0) ? (addr & 0xF) : (phase == 1 ? (data & 0xFF) : 0);
            for (int i = 0; i < 12; i++) {
                boolean en = (clkPsgR == 2);
                clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
                risingEdge(en, false, true, bc, bdir, d);
            }
        }
    }

    /**
     * Read one PSG register using the BDIR/BC bus protocol.
     *
     * <p>Executes three phases (address-latch, read, inactive),
     * each lasting 12 system-clock cycles.
     *
     * @param addr register address (0–15)
     * @return the 8-bit register value captured in {@link #getDataOut()}
     */
    public int readRegister(int addr) {
        int clkPsgR = 0;
        // Phase 0: address latch (BDIR=1, BC=1)
        for (int i = 0; i < 12; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            risingEdge(en, false, true, true, true, addr & 0xF);
        }
        // Phase 1: read (BDIR=0, BC=1)
        for (int i = 0; i < 12; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            risingEdge(en, false, true, true, false, 0);
        }
        // Phase 2: inactive (BDIR=0, BC=0)
        for (int i = 0; i < 12; i++) {
            boolean en = (clkPsgR == 2);
            clkPsgR = (clkPsgR == 5) ? 0 : clkPsgR + 1;
            risingEdge(en, false, true, false, false, 0);
        }
        return dataOR & 0xFF;
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Compute the signed-PCM contribution for one channel.
     *
     * <p>Matches the {@code sign_a_x} / {@code sign_b_x} / {@code sign_c_x}
     * combinatorial VHDL logic.
     *
     * @param levelEnv      12-bit amplitude (direct or envelope)
     * @param toneEnN       tone-enable active-low bit (R7)
     * @param noiseEnN      noise-enable active-low bit (R7)
     * @param flatlineTone  tone-counter flatline flag
     * @param flatlineNoise noise-counter flatline flag
     * @param mix           combined tone/noise mixer output
     * @return 12-bit unsigned representation of the signed value
     */
    static int computeSign(int levelEnv,
                           boolean toneEnN, boolean noiseEnN,
                           boolean flatlineTone, boolean flatlineNoise,
                           boolean mix) {
        boolean flat = (toneEnN && noiseEnN)
                || flatlineTone
                || (!noiseEnN && flatlineNoise);

        if (flat) {
            return (levelEnv - 0x800) & 0xFFF;
        } else if (!mix) {
            int half = (levelEnv >> 1) & 0x7FF;
            return (0x800 | (~half & 0x7FF)) + 1 & 0xFFF;
        } else {
            return (levelEnv >> 1) & 0x7FF;
        }
    }

    /**
     * Sign-extend a 12-bit value to 14 bits.
     * Matches VHDL: {@code (sign(11) & sign(11) & sign)}.
     */
    private static int signExtend12to14(int val12) {
        if ((val12 & 0x800) != 0) {
            return val12 | 0x3000;
        }
        return val12 & 0xFFF;
    }
}
