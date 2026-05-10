package org.jm2149.vhdl.idiomatic;

/**
 * Structured, idiomatic Java model of the ym2149_audio VHDL core.
 *
 * <p>This class is functionally equivalent to
 * {@link org.jm2149.vhdl.Ym2149Audio} but factors out the three repeated
 * generator patterns into dedicated helper classes:
 * <ul>
 *   <li>{@link ToneGenerator}     — one per channel (A, B, C)
 *   <li>{@link NoiseGenerator}    — shared noise source
 *   <li>{@link EnvelopeGenerator} — envelope ramp / FSM
 * </ul>
 *
 * <h2>Low-level (cycle-accurate) API</h2>
 * <p>All chip inputs are passed as parameters to {@link #risingEdge} and all
 * outputs are read back via typed getter methods.  This avoids mutable public
 * state and makes the data-flow explicit:
 *
 * <pre>
 *   Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
 *   for (int i = 0; i &lt; N; i++) {
 *       psg.risingEdge(enClkPsg, selN, resetN, bc, bdir, data);
 *       int sample = psg.getMixAudioO();
 *   }
 * </pre>
 *
 * <h2>High-level convenience API</h2>
 * <p>For quick experimentation the chip can be configured via the {@code set*()}
 * helpers and then advanced with {@link #run}:
 *
 * <pre>
 *   Ym2149AudioIdiomatic psg = new Ym2149AudioIdiomatic();
 *   psg.applyReset();
 *   psg.setTonePeriod(0, 500);                                 // channel A period
 *   psg.setVolume(0, 12);                                      // channel A volume
 *   psg.setMixer(true, false, false, false, false, false);     // tone A on, rest off
 *   int[] samples = psg.run(44100);                            // 44 100 clock cycles
 * </pre>
 */
public final class Ym2149AudioIdiomatic {

    // -----------------------------------------------------------------------
    // DAC ROM  (32 entries, 12-bit logarithmic amplitude table)
    // -----------------------------------------------------------------------

    private static final int[] DACROM = {
        0x000, 0x017, 0x01B, 0x021, 0x027, 0x02E, 0x037, 0x041,
        0x04D, 0x05C, 0x06D, 0x081, 0x09A, 0x0B7, 0x0D9, 0x102,
        0x133, 0x16D, 0x1B2, 0x204, 0x265, 0x2D8, 0x361, 0x405,
        0x4C7, 0x5AD, 0x6BF, 0x804, 0x987, 0xB53, 0xD76, 0xFFF
    };

    // -----------------------------------------------------------------------
    // Registered signals (_r) — VHDL flip-flop state
    // -----------------------------------------------------------------------

    // Input registration
    private boolean selNR   = false;
    private int     busctlR = 0;
    private int     dataIR  = 0;

    // Bus interface / register file
    private int     regAddrR    = 0;
    private int     dataOR      = 0;
    private boolean envShapeWrR = false;
    private int     chALevelR   = 0;
    private int     chBLevelR   = 0;
    private int     chCLevelR   = 0;
    private final int[] regFileAr = new int[16];

    // Clock conditioning
    private boolean selFfR   = true;
    private int     clkDiv8R = 0;
    private boolean enCntR   = false;

    // DAC output registers
    private int dacAR     = 0;
    private int dacBR     = 0;
    private int dacCR     = 0;
    private int sumAudioR = 0;

    // Signed PCM registers
    private int signAR  = 0;
    private int signBR  = 0;
    private int signCR  = 0;
    private int pcm14sR = 0;

    // -----------------------------------------------------------------------
    // Generators
    // -----------------------------------------------------------------------

    private final ToneGenerator     toneA;
    private final ToneGenerator     toneB;
    private final ToneGenerator     toneC;
    private final NoiseGenerator    noise;
    private final EnvelopeGenerator env = new EnvelopeGenerator();

    // -----------------------------------------------------------------------
    // Flatline thresholds (stored for use in risingEdge())
    // -----------------------------------------------------------------------

    private final int toneFlatlineThreshold;
    private final int noiseFlatlineThreshold;

    /**
     * Construct a model using the default VHDL flatline thresholds
     * (tone: {@value ToneGenerator#DEFAULT_FLATLINE_THRESHOLD},
     *  noise: {@value NoiseGenerator#DEFAULT_FLATLINE_THRESHOLD}).
     */
    public Ym2149AudioIdiomatic() {
        this(ToneGenerator.DEFAULT_FLATLINE_THRESHOLD,
             NoiseGenerator.DEFAULT_FLATLINE_THRESHOLD);
    }

    /**
     * Construct a model with configurable flatline thresholds.
     *
     * @param toneFlatlineThreshold   tone periods strictly below this value are
     *                                driven to a constant {@code '1'}; use
     *                                {@value ToneGenerator#DEFAULT_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     * @param noiseFlatlineThreshold  noise periods strictly below this value are
     *                                driven to a constant {@code '1'}; use
     *                                {@value NoiseGenerator#DEFAULT_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     */
    public Ym2149AudioIdiomatic(int toneFlatlineThreshold, int noiseFlatlineThreshold) {
        this.toneFlatlineThreshold  = toneFlatlineThreshold;
        this.noiseFlatlineThreshold = noiseFlatlineThreshold;
        this.toneA = new ToneGenerator(toneFlatlineThreshold);
        this.toneB = new ToneGenerator(toneFlatlineThreshold);
        this.toneC = new ToneGenerator(toneFlatlineThreshold);
        this.noise = new NoiseGenerator(noiseFlatlineThreshold);
    }

    // -----------------------------------------------------------------------
    // Outputs — typed getters (valid after each risingEdge() call)
    // -----------------------------------------------------------------------

    /** Registered data output (data_r_o). */
    public int getDataRO()    { return dataOR; }

    /** Channel A unsigned 12-bit DAC output (ch_a_o). */
    public int getChAO()      { return dacAR; }

    /** Channel B unsigned 12-bit DAC output (ch_b_o). */
    public int getChBO()      { return dacBR; }

    /** Channel C unsigned 12-bit DAC output (ch_c_o). */
    public int getChCO()      { return dacCR; }

    /** Unsigned 14-bit mixed audio output (mix_audio_o). */
    public int getMixAudioO() { return sumAudioR; }

    /** Signed 14-bit PCM output (pcm14s_o). */
    public int getPcm14sO()   { return pcm14sR; }

    // -----------------------------------------------------------------------
    // Single rising-edge simulation
    // -----------------------------------------------------------------------

    /**
     * Simulate one rising edge of {@code clk_i}.
     *
     * <p>All chip inputs are supplied as parameters.  Outputs are read back
     * via the {@code get*()} methods after this call returns.
     *
     * @param enClkPsgI  PSG clock-enable strobe (en_clk_psg_i)
     * @param selNI      divide select — {@code false} = clock-enable / 2 (sel_n_i)
     * @param resetNI    active-low reset (reset_n_i)
     * @param bcI        bus control (bc_i)
     * @param bdirI      bus direction (bdir_i)
     * @param dataI      8-bit data bus input (data_i)
     */
    public void risingEdge(boolean enClkPsgI, boolean selNI, boolean resetNI,
                           boolean bcI, boolean bdirI, int dataI) {

        // ==================================================================
        // COMBINATORIAL — computed from current registered state
        // ==================================================================

        // --- Input-registration next-state ---
        boolean newSelNR  = selNI;
        int     newBusctl = ((bdirI ? 1 : 0) << 1) | (bcI ? 1 : 0);
        int     newDataIR = dataI & 0xFF;

        // --- Bus interface ---
        int     regAddrX  = regAddrR;
        boolean enDataRdS = false;
        boolean enDataWrS = false;

        switch (busctlR & 0x3) {
            case 0: break;
            case 1: enDataRdS = true; break;
            case 2: enDataWrS = true; break;
            default:
                if ((dataIR >>> 4) == 0x0) {
                    regAddrX = dataIR & 0x0F;
                }
                break;
        }

        boolean envShapeWrX = enDataWrS && (regAddrR == 0xD);

        // DAC level for register writes
        int regBits      = dataIR & 0x0F;
        int dacBit0      = (regBits == 0) ? 0 : 1;
        int dacRegIdx    = (regBits << 1) | dacBit0;
        int dacRegLevelS = DACROM[dacRegIdx];

        int chALevelX = (regAddrR == 8)   ? dacRegLevelS : chALevelR;
        int chBLevelX = (regAddrR == 9)   ? dacRegLevelS : chBLevelR;
        int chCLevelX = (regAddrR == 0xA) ? dacRegLevelS : chCLevelR;

        // --- Register-file signal mapping ---
        int     chAPeriodS   = ((regFileAr[1] & 0x0F) << 8) | (regFileAr[0] & 0xFF);
        boolean chAToneEnNS  = (regFileAr[7] & 0x01) != 0;
        boolean chANoiseEnNS = (regFileAr[7] & 0x08) != 0;
        boolean chAModeS     = (regFileAr[8]  & 0x10) != 0;

        int     chBPeriodS   = ((regFileAr[3] & 0x0F) << 8) | (regFileAr[2] & 0xFF);
        boolean chBToneEnNS  = (regFileAr[7] & 0x02) != 0;
        boolean chBNoiseEnNS = (regFileAr[7] & 0x10) != 0;
        boolean chBModeS     = (regFileAr[9]  & 0x10) != 0;

        int     chCPeriodS   = ((regFileAr[5] & 0x0F) << 8) | (regFileAr[4] & 0xFF);
        boolean chCToneEnNS  = (regFileAr[7] & 0x04) != 0;
        boolean chCNoiseEnNS = (regFileAr[7] & 0x20) != 0;
        boolean chCModeS     = (regFileAr[10] & 0x10) != 0;

        int     noisePeriodS  = regFileAr[6] & 0x1F;

        int     envPeriodS    = ((regFileAr[12] & 0xFF) << 8) | (regFileAr[11] & 0xFF);
        boolean envContinueS  = (regFileAr[13] & 0x08) != 0;
        boolean envAttackS    = (regFileAr[13] & 0x04) != 0;
        boolean envAlternateS = (regFileAr[13] & 0x02) != 0;
        boolean envHoldS      = (regFileAr[13] & 0x01) != 0;

        // --- Clock conditioning ---
        boolean selFfX       = !selFfR;
        boolean enIntClkPsgS = (!selNR) ? (enClkPsgI && selFfR) : enClkPsgI;

        int     clkDiv8X = (clkDiv8R + 1) & 0x7;
        boolean enCntX   = (clkDiv8R == 3);

        // --- Read generator outputs BEFORE ticking (combinatorial values) ---
        boolean toneAS = toneA.output();
        boolean toneBS = toneB.output();
        boolean toneCS = toneC.output();
        boolean noiseS = noise.output();

        boolean flatlineAS = chAPeriodS < toneFlatlineThreshold;
        boolean flatlineBS = chBPeriodS < toneFlatlineThreshold;
        boolean flatlineCS = chCPeriodS < toneFlatlineThreshold;
        boolean flatlineNS = noisePeriodS < noiseFlatlineThreshold;

        // --- Mixer ---
        boolean mixAS = (chAToneEnNS || toneAS) && (chANoiseEnNS || noiseS);
        boolean mixBS = (chBToneEnNS || toneBS) && (chBNoiseEnNS || noiseS);
        boolean mixCS = (chCToneEnNS || toneCS) && (chCNoiseEnNS || noiseS);

        // --- Envelope DAC level (from current envelope state) ---
        int dacEnvLevelS = env.dacLevel(DACROM, envAttackS);

        // --- Channel amplitude (gated by mixer) and signed PCM level ---
        int levelAS = mixedLevel(mixAS, chAModeS, chALevelR, dacEnvLevelS);
        int levelBS = mixedLevel(mixBS, chBModeS, chBLevelR, dacEnvLevelS);
        int levelCS = mixedLevel(mixCS, chCModeS, chCLevelR, dacEnvLevelS);

        int levelAEnvS = chAModeS ? dacEnvLevelS : chALevelR;
        int levelBEnvS = chBModeS ? dacEnvLevelS : chBLevelR;
        int levelCEnvS = chCModeS ? dacEnvLevelS : chCLevelR;

        int signAX = signedLevel(levelAEnvS, chAToneEnNS, chANoiseEnNS,
                                 flatlineAS, flatlineNS, mixAS);
        int signBX = signedLevel(levelBEnvS, chBToneEnNS, chBNoiseEnNS,
                                 flatlineBS, flatlineNS, mixBS);
        int signCX = signedLevel(levelCEnvS, chCToneEnNS, chCNoiseEnNS,
                                 flatlineCS, flatlineNS, mixCS);

        // ==================================================================
        // REGISTER UPDATE
        // ==================================================================

        // Input registration: always at every rising clk_i
        selNR   = newSelNR;
        busctlR = newBusctl;
        dataIR  = newDataIR;

        // Register file (reset_n_i or en_clk_psg_i)
        if (!resetNI) {
            regAddrR    = 0;
            dataOR      = 0;
            envShapeWrR = false;
            chALevelR   = 0x800;
            chBLevelR   = 0x800;
            chCLevelR   = 0x800;
            for (int i = 0; i < 16; i++) regFileAr[i] = 0;
        } else if (enClkPsgI) {
            if (enDataRdS) {
                dataOR = regFileAr[regAddrR] & 0xFF;
            } else if (enDataWrS) {
                regFileAr[regAddrR] = dataIR;
                chALevelR = chALevelX;
                chBLevelR = chBLevelX;
                chCLevelR = chCLevelX;
            }
            regAddrR    = regAddrX;
            envShapeWrR = envShapeWrX;
        }

        // Clock conditioning
        if (enClkPsgI) {
            selFfR = selFfX;
        }

        // Save the pre-edge enCntR so generators receive the OLD registered value
        // (enCntR is updated just below, before the generator ticks.)
        boolean oldEnCntR = enCntR;

        // Clock divider + count enable
        if (!resetNI) {
            clkDiv8R = 0;
            enCntR   = false;
        } else if (enIntClkPsgS) {
            clkDiv8R = clkDiv8X;
            enCntR   = enCntX;
        }

        // Tone generators — pass the OLD enCntR, not the newly-written value
        if (!resetNI) {
            toneA.reset();
            toneB.reset();
            toneC.reset();
        } else if (enIntClkPsgS) {
            toneA.tick(chAPeriodS, enCntX, oldEnCntR);
            toneB.tick(chBPeriodS, enCntX, oldEnCntR);
            toneC.tick(chCPeriodS, enCntX, oldEnCntR);
        }

        // Noise generator
        if (!resetNI) {
            noise.reset();
        } else if (enIntClkPsgS) {
            noise.tick(noisePeriodS, enCntX, oldEnCntR);
        }

        // Envelope generator (env_rst_s = !resetNI || envShapeWrR)
        boolean envRstS = !resetNI || envShapeWrR;
        if (envRstS) {
            env.reset();
        } else if (enIntClkPsgS) {
            env.tick(envPeriodS, enCntX, oldEnCntR,
                     envContinueS, envAttackS, envAlternateS, envHoldS);
        }

        // DAC registers (en_clk_psg_i)
        if (enClkPsgI) {
            sumAudioR = (dacAR + dacBR + dacCR) & 0x3FFF;
            dacAR = levelAS & 0xFFF;
            dacBR = levelBS & 0xFFF;
            dacCR = levelCS & 0xFFF;
        }

        // Signed PCM (en_clk_psg_i)
        if (enClkPsgI) {
            pcm14sR = (signExt12to14(signAR)
                     + signExt12to14(signBR)
                     + signExt12to14(signCR)) & 0x3FFF;
            signAR = signAX & 0xFFF;
            signBR = signBX & 0xFFF;
            signCR = signCX & 0xFFF;
        }
    }

    // -----------------------------------------------------------------------
    // Private static helpers — factored-out channel computations
    // -----------------------------------------------------------------------

    /**
     * Unsigned DAC level gated by the mixer output.
     *
     * @param mix       mixer output for this channel
     * @param envMode   {@code true} when the channel uses the envelope generator
     * @param fixedLevel pre-computed fixed DAC level from the volume register
     * @param envLevel  envelope generator DAC level
     * @return 12-bit unsigned level (0 when mixer is low)
     */
    private static int mixedLevel(boolean mix, boolean envMode,
                                  int fixedLevel, int envLevel) {
        return !mix ? 0 : (envMode ? envLevel : fixedLevel);
    }

    /**
     * Signed 12-bit PCM level for one channel (not gated by the mixer).
     *
     * <p>Logic mirrors the VHDL {@code sign_x_r} computation:
     * <ul>
     *   <li>Flat condition → {@code levelEnv − 0x800} (mid-scale offset)
     *   <li>Mixer low → negative half-amplitude
     *   <li>Mixer high → positive half-amplitude
     * </ul>
     *
     * @param levelEnv      envelope-or-fixed level for the channel
     * @param toneEnN       tone-enable bit from register 7 (active-low)
     * @param noiseEnN      noise-enable bit from register 7 (active-low)
     * @param flatlineTone  {@code true} when the tone period is below flatline threshold
     * @param flatlineNoise {@code true} when the noise period is below flatline threshold
     * @param mix           mixer output for this channel
     * @return 12-bit signed level
     */
    private static int signedLevel(int levelEnv, boolean toneEnN, boolean noiseEnN,
                                   boolean flatlineTone, boolean flatlineNoise, boolean mix) {
        boolean flat = (toneEnN && noiseEnN)
                     || flatlineTone
                     || (!noiseEnN && flatlineNoise);
        if (flat) {
            return (levelEnv - 0x800) & 0xFFF;
        } else if (!mix) {
            int half = (levelEnv >> 1) & 0x7FF;
            return ((0x800 | ((~half) & 0x7FF)) + 1) & 0xFFF;
        } else {
            return (levelEnv >> 1) & 0x7FF;
        }
    }

    /**
     * Sign-extend a 12-bit value to 14 bits.
     */
    private static int signExt12to14(int v12) {
        return ((v12 & 0x800) != 0) ? (v12 | 0x3000) : (v12 & 0xFFF);
    }

    // -----------------------------------------------------------------------
    // High-level convenience API
    // -----------------------------------------------------------------------

    /**
     * Write a byte directly to the YM2149 internal register file, bypassing
     * the bus interface.  Useful for quick configuration without simulating
     * the address/data bus protocol.
     *
     * <p>Registers 0–15 correspond to the standard YM2149 register map:
     * <pre>
     *  0-1  : Channel A tone period (low byte / high nibble)
     *  2-3  : Channel B tone period
     *  4-5  : Channel C tone period
     *  6    : Noise period (5-bit)
     *  7    : Mixer control (bits 5-0: noise C/B/A, tone C/B/A; active-low)
     *  8-10 : Volume A/B/C (bits 0-3 = level; bit 4 = envelope mode)
     *  11-12: Envelope period (low / high byte)
     *  13   : Envelope shape (CONT/ATT/ALT/HOLD)
     * </pre>
     *
     * @param reg   register index (0–15)
     * @param value 8-bit value to write
     * @throws IllegalArgumentException if {@code reg} is outside [0, 15]
     */
    public void writeRegister(int reg, int value) {
        if (reg < 0 || reg > 15) {
            throw new IllegalArgumentException("register index out of range: " + reg);
        }
        regFileAr[reg] = value & 0xFF;

        // Keep the pre-computed DAC level cache in sync for volume registers
        if (reg >= 8 && reg <= 10) {
            int bits  = value & 0x0F;
            int idx   = (bits << 1) | ((bits == 0) ? 0 : 1);
            int level = DACROM[idx];
            if (reg == 8)  chALevelR = level;
            if (reg == 9)  chBLevelR = level;
            if (reg == 10) chCLevelR = level;
        }

        // Writing register 13 resets the envelope generator (hardware behaviour)
        if (reg == 13) {
            env.reset();
            envShapeWrR = false;
        }
    }

    /**
     * Set the tone period for a channel.
     *
     * <p>The 12-bit {@code period} value is split across two consecutive
     * registers:
     * <pre>
     *   reg[2*channel]     = period &amp; 0xFF         (low byte)
     *   reg[2*channel + 1] = (period &gt;&gt; 8) &amp; 0x0F  (high nibble)
     * </pre>
     *
     * @param channel 0 = A, 1 = B, 2 = C
     * @param period  12-bit tone period (1–4095; values &lt; 6 flatline the output)
     * @throws IllegalArgumentException if {@code channel} is not 0, 1 or 2
     */
    public void setTonePeriod(int channel, int period) {
        if (channel < 0 || channel > 2) {
            throw new IllegalArgumentException("channel must be 0, 1 or 2; got: " + channel);
        }
        int base = channel * 2;
        regFileAr[base]     =  period & 0xFF;
        regFileAr[base + 1] = (period >> 8) & 0x0F;
    }

    /**
     * Set the noise period (register 6, bits 4-0).
     *
     * @param period 5-bit noise period (values &lt; 5 flatline the output)
     */
    public void setNoisePeriod(int period) {
        regFileAr[6] = period & 0x1F;
    }

    /**
     * Configure the mixer by specifying which tone and noise sources are
     * enabled for each channel.
     *
     * <p>Parameters use active-high convention (pass {@code true} to enable).
     * Internally the values are stored as the active-low register 7 encoding.
     *
     * @param toneA   enable channel A tone output
     * @param toneB   enable channel B tone output
     * @param toneC   enable channel C tone output
     * @param noiseA  enable channel A noise output
     * @param noiseB  enable channel B noise output
     * @param noiseC  enable channel C noise output
     */
    public void setMixer(boolean toneA, boolean toneB, boolean toneC,
                         boolean noiseA, boolean noiseB, boolean noiseC) {
        int reg7 = 0;
        if (!toneA)  reg7 |= 0x01;
        if (!toneB)  reg7 |= 0x02;
        if (!toneC)  reg7 |= 0x04;
        if (!noiseA) reg7 |= 0x08;
        if (!noiseB) reg7 |= 0x10;
        if (!noiseC) reg7 |= 0x20;
        regFileAr[7] = reg7;
    }

    /**
     * Set the volume / mode for a channel (registers 8–10).
     *
     * @param channel  0 = A, 1 = B, 2 = C
     * @param volume   4-bit volume level (0–15); ignored when {@code envMode} is {@code true}
     * @param envMode  {@code true} to use envelope generator output instead of fixed volume
     * @throws IllegalArgumentException if {@code channel} is not 0, 1 or 2
     */
    public void setVolume(int channel, int volume, boolean envMode) {
        if (channel < 0 || channel > 2) {
            throw new IllegalArgumentException("channel must be 0, 1 or 2; got: " + channel);
        }
        writeRegister(8 + channel, (envMode ? 0x10 : 0) | (volume & 0x0F));
    }

    /**
     * Convenience overload — sets a fixed (non-envelope) volume level.
     *
     * @param channel 0 = A, 1 = B, 2 = C
     * @param volume  4-bit volume level (0–15)
     */
    public void setVolume(int channel, int volume) {
        setVolume(channel, volume, false);
    }

    /**
     * Set the envelope period (registers 11–12).
     *
     * @param period 16-bit envelope period
     */
    public void setEnvelopePeriod(int period) {
        regFileAr[11] =  period & 0xFF;
        regFileAr[12] = (period >> 8) & 0xFF;
    }

    /**
     * Set the envelope shape (register 13: CONT/ATT/ALT/HOLD bits).
     *
     * <p>Writing this register also resets the envelope generator, as in the hardware.
     *
     * @param shape 4-bit shape value
     */
    public void setEnvelopeShape(int shape) {
        writeRegister(13, shape & 0x0F);
    }

    /**
     * Drive the chip through an active-low reset sequence.
     *
     * <p>Asserts {@code reset_n_i = false} for 8 rising-clock edges
     * ({@code en_clk_psg_i = true, sel_n_i = false}) and then releases reset.
     * The chip is left in a quiescent state, ready for normal use.
     */
    public void applyReset() {
        for (int i = 0; i < 8; i++) {
            risingEdge(true, false, false, false, false, 0);
        }
    }

    /**
     * Advance the simulation by {@code cycles} rising-clock-edges and return
     * the {@code mix_audio_o} output sampled after each edge.
     *
     * <p>Each cycle is simulated with standard operating inputs:
     * {@code en_clk_psg_i = true, sel_n_i = false, reset_n_i = true} and
     * an inactive bus.  Call this method after configuring the chip via
     * {@link #writeRegister} or the {@code set*()} helpers.
     *
     * @param cycles number of clock cycles to simulate (must be &gt; 0)
     * @return array of length {@code cycles} containing {@link #getMixAudioO()}
     *         after each cycle
     * @throws IllegalArgumentException if {@code cycles} is not positive
     */
    public int[] run(int cycles) {
        if (cycles <= 0) {
            throw new IllegalArgumentException("cycles must be positive; got: " + cycles);
        }
        int[] out = new int[cycles];
        for (int i = 0; i < cycles; i++) {
            risingEdge(true, false, true, false, false, 0);
            out[i] = sumAudioR;
        }
        return out;
    }
}
