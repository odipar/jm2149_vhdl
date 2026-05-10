package org.jm2149.vhdl.refactored;

import org.jm2149.vhdl.idiomatic.EnvelopeGenerator;
import org.jm2149.vhdl.idiomatic.NoiseGenerator;
import org.jm2149.vhdl.idiomatic.ToneGenerator;
import org.jm2149.vhdl.optimized.RegisterFile;

/**
 * Refactored Java model of the ym2149_audio VHDL core.
 *
 * <p>Functionally identical to
 * {@link org.jm2149.vhdl.optimized.Ym2149AudioOptimized} but further
 * restructured to reduce cyclomatic complexity and improve readability:
 *
 * <ol>
 *   <li><b>Merged conditional groups.</b>  All register-update blocks that
 *       share the same guard condition ({@code !resetNI} or
 *       {@code enIntClkPsgS}) are folded into a single
 *       {@code if (!resetNI) / else} tree, so the condition is evaluated
 *       exactly once.</li>
 *   <li><b>Factored-out helpers.</b>  {@link #resetState()},
 *       {@link #updateRegisterFile(boolean, boolean, int, boolean)},
 *       {@link #updateDacLevelCache(int, int)},
 *       {@link #tickToneGenerators(boolean, boolean)}, and
 *       {@link #tickEnvelope(boolean, boolean, boolean)} each handle
 *       one well-defined responsibility, keeping {@link #risingEdge} to a
 *       single level of nesting per concern.</li>
 *   <li><b>Combined {@code enClkPsgI} block.</b>  The {@code sel_ff_r}
 *       flip-flop update and the DAC / signed-PCM register updates share
 *       the same {@code enClkPsgI} guard and are written as one
 *       {@code if (enClkPsgI)} block at the end of {@link #risingEdge}.</li>
 *   <li><b>Eliminated dead case.</b>  {@code case 0} of the bus-control
 *       switch is a no-op; it is removed and the default branch handles
 *       the remaining unused values.</li>
 *   <li><b>Reuses proven sub-components.</b>  {@link RegisterFile},
 *       {@link ToneGenerator}, {@link NoiseGenerator}, and
 *       {@link EnvelopeGenerator} are reused unchanged from the
 *       {@code optimized} / {@code idiomatic} packages.</li>
 * </ol>
 *
 * <h2>Low-level (cycle-accurate) API</h2>
 * <pre>
 *   Ym2149AudioRefactored psg = new Ym2149AudioRefactored();
 *   for (int i = 0; i &lt; N; i++) {
 *       psg.risingEdge(enClkPsg, selN, resetN, bc, bdir, data);
 *       int sample = psg.getMixAudioO();
 *   }
 * </pre>
 *
 * <h2>High-level convenience API</h2>
 * <pre>
 *   Ym2149AudioRefactored psg = new Ym2149AudioRefactored();
 *   psg.applyReset();
 *   psg.setTonePeriod(0, 500);
 *   psg.setVolume(0, 12);
 *   psg.setMixer(true, false, false, false, false, false);
 *   int[] samples = psg.run(44100);
 * </pre>
 */
public final class Ym2149AudioRefactored {

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
    // Sub-components
    // -----------------------------------------------------------------------

    private final RegisterFile      rf;
    private final ToneGenerator     toneA;
    private final ToneGenerator     toneB;
    private final ToneGenerator     toneC;
    private final NoiseGenerator    noise;
    private final EnvelopeGenerator env = new EnvelopeGenerator();

    /**
     * Construct a model using the default VHDL flatline thresholds
     * (tone: {@value ToneGenerator#DEFAULT_FLATLINE_THRESHOLD},
     *  noise: {@value NoiseGenerator#DEFAULT_FLATLINE_THRESHOLD}).
     */
    public Ym2149AudioRefactored() {
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
    public Ym2149AudioRefactored(int toneFlatlineThreshold, int noiseFlatlineThreshold) {
        this.rf    = new RegisterFile(toneFlatlineThreshold, noiseFlatlineThreshold);
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
        // Input registration next-state — always computed
        // ==================================================================

        boolean newSelNR  = selNI;
        int     newBusctl = ((bdirI ? 1 : 0) << 1) | (bcI ? 1 : 0);
        int     newDataIR = dataI & 0xFF;

        // ==================================================================
        // Fast path: PSG clock inactive and no reset
        // Only the three input flip-flops need updating.
        // ==================================================================

        if (!enClkPsgI && resetNI) {
            selNR   = newSelNR;
            busctlR = newBusctl;
            dataIR  = newDataIR;
            return;
        }

        // ==================================================================
        // COMBINATORIAL — computed from current registered state
        // ==================================================================

        // --- Bus interface decode ---
        int     regAddrX  = regAddrR;
        boolean enDataRdS = false;
        boolean enDataWrS = false;

        switch (busctlR & 0x3) {
            case 1 -> enDataRdS = true;
            case 2 -> enDataWrS = true;
            case 3 -> { if ((dataIR >>> 4) == 0) regAddrX = dataIR & 0x0F; }
            default -> { /* case 0: no bus operation */ }
        }

        boolean envShapeWrX = enDataWrS && (regAddrR == 0xD);

        // --- Clock conditioning ---
        boolean selFfX       = !selFfR;
        boolean enIntClkPsgS = (!selNR) ? (enClkPsgI && selFfR) : enClkPsgI;
        int     clkDiv8X     = (clkDiv8R + 1) & 0x7;
        boolean enCntX       = (clkDiv8R == 3);

        // --- Generator outputs BEFORE ticking (combinatorial values) ---
        boolean toneAS = toneA.output();
        boolean toneBS = toneB.output();
        boolean toneCS = toneC.output();
        boolean noiseS = noise.output();

        // --- Cached register signals (O(1) field reads) ---
        boolean chAToneEnNS  = rf.isChAToneEnN();
        boolean chBToneEnNS  = rf.isChBToneEnN();
        boolean chCToneEnNS  = rf.isChCToneEnN();
        boolean chANoiseEnNS = rf.isChANoiseEnN();
        boolean chBNoiseEnNS = rf.isChBNoiseEnN();
        boolean chCNoiseEnNS = rf.isChCNoiseEnN();
        boolean chAModeS     = rf.isChAMode();
        boolean chBModeS     = rf.isChBMode();
        boolean chCModeS     = rf.isChCMode();
        boolean flatlineAS   = rf.isFlatlineA();
        boolean flatlineBS   = rf.isFlatlineB();
        boolean flatlineCS   = rf.isFlatlineC();
        boolean flatlineNS   = rf.isFlatlineN();
        boolean envAttackS   = rf.isEnvAttack();

        // --- Mixer ---
        boolean mixAS = (chAToneEnNS || toneAS) && (chANoiseEnNS || noiseS);
        boolean mixBS = (chBToneEnNS || toneBS) && (chBNoiseEnNS || noiseS);
        boolean mixCS = (chCToneEnNS || toneCS) && (chCNoiseEnNS || noiseS);

        // --- Envelope DAC level (from current envelope state) ---
        int dacEnvLevelS = env.dacLevel(DACROM, envAttackS);

        // --- Channel envelope-or-fixed levels ---
        int levelAEnvS = chAModeS ? dacEnvLevelS : chALevelR;
        int levelBEnvS = chBModeS ? dacEnvLevelS : chBLevelR;
        int levelCEnvS = chCModeS ? dacEnvLevelS : chCLevelR;

        // --- Mixer-gated unsigned output levels ---
        int levelAS = mixAS ? levelAEnvS : 0;
        int levelBS = mixBS ? levelBEnvS : 0;
        int levelCS = mixCS ? levelCEnvS : 0;

        // --- Signed PCM levels (not mixer-gated) ---
        int signAX = signedLevel(levelAEnvS, chAToneEnNS, chANoiseEnNS,
                                 flatlineAS, flatlineNS, mixAS);
        int signBX = signedLevel(levelBEnvS, chBToneEnNS, chBNoiseEnNS,
                                 flatlineBS, flatlineNS, mixBS);
        int signCX = signedLevel(levelCEnvS, chCToneEnNS, chCNoiseEnNS,
                                 flatlineCS, flatlineNS, mixCS);

        // ==================================================================
        // REGISTER UPDATE
        // ==================================================================

        // Input flip-flops: always at every rising clk_i
        selNR   = newSelNR;
        busctlR = newBusctl;
        dataIR  = newDataIR;

        // --- Reset vs. normal operation ---
        // All blocks that share the !resetNI / enIntClkPsgS condition are
        // merged here to evaluate each guard exactly once.
        if (!resetNI) {
            resetState();
        } else {
            // Register file and address — gated by enClkPsgI
            if (enClkPsgI) {
                updateRegisterFile(enDataRdS, enDataWrS, regAddrX, envShapeWrX);
            }
            // Clock divider + all generators — gated by enIntClkPsgS
            if (enIntClkPsgS) {
                boolean oldEnCntR = enCntR;
                clkDiv8R = clkDiv8X;
                enCntR   = enCntX;
                tickToneGenerators(enCntX, oldEnCntR);
                noise.tick(rf.getNoisePeriod(), enCntX, oldEnCntR);
                tickEnvelope(enCntX, oldEnCntR, envAttackS);
            } else if (envShapeWrR) {
                // Envelope reset can fire independently of enIntClkPsgS
                env.reset();
            }
        }

        // selFf + DAC outputs + PCM — all gated by enClkPsgI (independent of reset)
        if (enClkPsgI) {
            selFfR    = selFfX;
            sumAudioR = (dacAR + dacBR + dacCR) & 0x3FFF;
            dacAR     = levelAS & 0xFFF;
            dacBR     = levelBS & 0xFFF;
            dacCR     = levelCS & 0xFFF;
            pcm14sR   = (signExt12to14(signAR)
                       + signExt12to14(signBR)
                       + signExt12to14(signCR)) & 0x3FFF;
            signAR    = signAX & 0xFFF;
            signBR    = signBX & 0xFFF;
            signCR    = signCX & 0xFFF;
        }
    }

    // -----------------------------------------------------------------------
    // Factored-out register-update helpers
    // -----------------------------------------------------------------------

    /**
     * Reset all chip state: register file, clock divider, and all generators.
     *
     * <p>Called when {@code reset_n_i} is asserted (active low).
     */
    private void resetState() {
        regAddrR    = 0;
        dataOR      = 0;
        envShapeWrR = false;
        chALevelR   = 0x800;
        chBLevelR   = 0x800;
        chCLevelR   = 0x800;
        rf.reset();
        clkDiv8R    = 0;
        enCntR      = false;
        toneA.reset();
        toneB.reset();
        toneC.reset();
        noise.reset();
        env.reset();
    }

    /**
     * Handle one bus transaction and update the register-address pointer.
     *
     * @param enDataRdS  true when a register read is requested
     * @param enDataWrS  true when a register write is requested
     * @param regAddrX   next register address (may have changed via latch-address cycle)
     * @param envShapeWrX next value of the envelope-shape write flag
     */
    private void updateRegisterFile(boolean enDataRdS, boolean enDataWrS,
                                    int regAddrX, boolean envShapeWrX) {
        if (enDataRdS) {
            dataOR = rf.read(regAddrR) & 0xFF;
        } else if (enDataWrS) {
            rf.write(regAddrR, dataIR);
            updateDacLevelCache(regAddrR, dataIR);
        }
        regAddrR    = regAddrX;
        envShapeWrR = envShapeWrX;
    }

    /**
     * Recompute the fixed DAC level cache for volume registers 8–10.
     *
     * <p>Called whenever a volume register is written so that
     * {@link #risingEdge} can read the pre-decoded level in O(1).
     *
     * @param reg   register index (only 8–10 are acted upon)
     * @param value raw byte value written to the register
     */
    private void updateDacLevelCache(int reg, int value) {
        if (reg < 8 || reg > 10) return;
        int bits  = value & 0x0F;
        int level = DACROM[(bits << 1) | (bits == 0 ? 0 : 1)];
        switch (reg) {
            case 8  -> chALevelR = level;
            case 9  -> chBLevelR = level;
            case 10 -> chCLevelR = level;
        }
    }

    /**
     * Advance all three tone generators by one PSG-clock step.
     *
     * @param enCntX    next count-enable value (pre-computed combinatorial)
     * @param oldEnCntR count-enable registered value from the previous cycle
     */
    private void tickToneGenerators(boolean enCntX, boolean oldEnCntR) {
        toneA.tick(rf.getChAPeriod(), enCntX, oldEnCntR);
        toneB.tick(rf.getChBPeriod(), enCntX, oldEnCntR);
        toneC.tick(rf.getChCPeriod(), enCntX, oldEnCntR);
    }

    /**
     * Advance (or reset) the envelope generator by one PSG-clock step.
     *
     * <p>If {@code envShapeWrR} is set the generator is reset instead of
     * ticked, matching the VHDL priority: {@code env_rst_s = envShapeWrR}.
     *
     * @param enCntX     next count-enable value
     * @param oldEnCntR  registered count-enable from the previous cycle
     * @param envAttackS current envelope ATTACK bit (cached from register file)
     */
    private void tickEnvelope(boolean enCntX, boolean oldEnCntR, boolean envAttackS) {
        if (envShapeWrR) {
            env.reset();
        } else {
            env.tick(rf.getEnvPeriod(), enCntX, oldEnCntR,
                     rf.isEnvContinue(), envAttackS,
                     rf.isEnvAlternate(), rf.isEnvHold());
        }
    }

    // -----------------------------------------------------------------------
    // Private static helpers
    // -----------------------------------------------------------------------

    /**
     * Signed 12-bit PCM level for one channel (not gated by the mixer).
     *
     * <p>Logic mirrors the VHDL {@code sign_x_r} computation:
     * <ul>
     *   <li>Flat condition → {@code levelEnv − 0x800} (mid-scale offset)
     *   <li>Mixer low → negative half-amplitude
     *   <li>Mixer high → positive half-amplitude
     * </ul>
     */
    private static int signedLevel(int levelEnv, boolean toneEnN, boolean noiseEnN,
                                   boolean flatlineTone, boolean flatlineNoise,
                                   boolean mix) {
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

    /** Sign-extend a 12-bit value to 14 bits. */
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
     * @param reg   register index (0–15)
     * @param value 8-bit value to write
     * @throws IllegalArgumentException if {@code reg} is outside [0, 15]
     */
    public void writeRegister(int reg, int value) {
        if (reg < 0 || reg > 15) {
            throw new IllegalArgumentException("register index out of range: " + reg);
        }
        rf.write(reg, value & 0xFF);
        updateDacLevelCache(reg, value);
        if (reg == 13) {
            env.reset();
            envShapeWrR = false;
        }
    }

    /**
     * Set the tone period for a channel.
     *
     * @param channel 0 = A, 1 = B, 2 = C
     * @param period  12-bit tone period (1–4095; values {@code < 6} flatline the output)
     * @throws IllegalArgumentException if {@code channel} is not 0, 1 or 2
     */
    public void setTonePeriod(int channel, int period) {
        if (channel < 0 || channel > 2) {
            throw new IllegalArgumentException("channel must be 0, 1 or 2; got: " + channel);
        }
        int base = channel * 2;
        rf.write(base,      period & 0xFF);
        rf.write(base + 1, (period >> 8) & 0x0F);
    }

    /**
     * Set the noise period (register 6, bits 4-0).
     *
     * @param period 5-bit noise period (values {@code < 5} flatline the output)
     */
    public void setNoisePeriod(int period) {
        rf.write(6, period & 0x1F);
    }

    /**
     * Configure the mixer by specifying which tone and noise sources are
     * enabled for each channel (active-high convention).
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
        rf.write(7, reg7);
    }

    /**
     * Set the volume / mode for a channel (registers 8–10).
     *
     * @param channel  0 = A, 1 = B, 2 = C
     * @param volume   4-bit volume level (0–15)
     * @param envMode  {@code true} to use the envelope generator instead of fixed volume
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
        rf.write(11,  period & 0xFF);
        rf.write(12, (period >> 8) & 0xFF);
    }

    /**
     * Set the envelope shape (register 13: CONT/ATT/ALT/HOLD bits).
     *
     * <p>Writing this register also resets the envelope generator, as in hardware.
     *
     * @param shape 4-bit shape value
     */
    public void setEnvelopeShape(int shape) {
        writeRegister(13, shape & 0x0F);
    }

    /**
     * Drive the chip through an active-low reset sequence.
     *
     * <p>Asserts {@code reset_n_i = false} for 8 rising-clock edges and
     * then releases reset.
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
     * <p>Each cycle uses standard operating inputs:
     * {@code en_clk_psg_i = true, sel_n_i = false, reset_n_i = true} and
     * an inactive bus.
     *
     * @param cycles number of clock cycles to simulate (must be &gt; 0)
     * @return array of length {@code cycles} with {@link #getMixAudioO()}
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
