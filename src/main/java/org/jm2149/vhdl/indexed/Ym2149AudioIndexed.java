package org.jm2149.vhdl.indexed;

import org.jm2149.vhdl.idiomatic.EnvelopeGenerator;
import org.jm2149.vhdl.idiomatic.NoiseGenerator;
import org.jm2149.vhdl.idiomatic.ToneGenerator;
import org.jm2149.vhdl.optimized.RegisterFile;

/**
 * Indexed Java model of the ym2149_audio VHDL core.
 *
 * <p>Functionally derived from
 * {@link org.jm2149.vhdl.refactored.Ym2149AudioRefactored}.
 * The audio mixing, and signed PCM conversion are intentionally omitted —
 * they are considerations that
 * lie outside the YM2149 chip model and can be performed by the consumer
 * of these outputs if needed.
 *
 * <h2>Output signals</h2>
 * <ul>
 *   <li>{@link #getChAIndexO()} — 5-bit DAC index for channel A (0–31)</li>
 *   <li>{@link #getChBIndexO()} — 5-bit DAC index for channel B (0–31)</li>
 *   <li>{@link #getChCIndexO()} — 5-bit DAC index for channel C (0–31)</li>
 *   <li>{@link #getChAO()} — 12-bit DAC level for channel A (convenience)</li>
 *   <li>{@link #getChBO()} — 12-bit DAC level for channel B (convenience)</li>
 *   <li>{@link #getChCO()} — 12-bit DAC level for channel C (convenience)</li>
 *   <li>{@link #getDataRO()} — registered data output (data_r_o)</li>
 * </ul>
 *
 * <h2>Low-level (cycle-accurate) API</h2>
 * <pre>
 *   Ym2149AudioIndexed psg = new Ym2149AudioIndexed();
 *   for (int i = 0; i &lt; N; i++) {
 *       psg.risingEdge(enClkPsg, selN, resetN, bc, bdir, data);
 *       int idxA = psg.getChAIndexO();
 *   }
 * </pre>
 *
 * <h2>High-level convenience API</h2>
 * <pre>
 *   Ym2149AudioIndexed psg = new Ym2149AudioIndexed();
 *   psg.applyReset();
 *   psg.setTonePeriod(0, 500);
 *   psg.setVolume(0, 12);
 *   psg.setMixer(true, false, false, false, false, false);
 *   psg.run(44100);
 * </pre>
 */
public final class Ym2149AudioIndexed {

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
    private int     chAIndexR   = 0;
    private int     chBIndexR   = 0;
    private int     chCIndexR   = 0;

    // Clock conditioning
    private boolean selFfR   = true;
    private int     clkDiv8R = 0;
    private boolean enCntR   = false;

    // DAC index output registers (5-bit, 0–31)
    private int idxAR = 0;
    private int idxBR = 0;
    private int idxCR = 0;

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
    public Ym2149AudioIndexed() {
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
    public Ym2149AudioIndexed(int toneFlatlineThreshold, int noiseFlatlineThreshold) {
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
    public int getDataRO()      { return dataOR; }

    /** Channel A 5-bit DAC index output (0–31). */
    public int getChAIndexO()   { return idxAR; }

    /** Channel B 5-bit DAC index output (0–31). */
    public int getChBIndexO()   { return idxBR; }

    /** Channel C 5-bit DAC index output (0–31). */
    public int getChCIndexO()   { return idxCR; }

    /**
     * Channel A 12-bit DAC level output (convenience conversion of
     * {@link #getChAIndexO()} via the chip's logarithmic ROM).
     *
     * @return unsigned 12-bit DAC level (0–4095)
     */
    public int getChAO()        { return DACROM[idxAR]; }

    /**
     * Channel B 12-bit DAC level output (convenience conversion of
     * {@link #getChBIndexO()} via the chip's logarithmic ROM).
     *
     * @return unsigned 12-bit DAC level (0–4095)
     */
    public int getChBO()        { return DACROM[idxBR]; }

    /**
     * Channel C 12-bit DAC level output (convenience conversion of
     * {@link #getChCIndexO()} via the chip's logarithmic ROM).
     *
     * @return unsigned 12-bit DAC level (0–4095)
     */
    public int getChCO()        { return DACROM[idxCR]; }

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
        boolean envAttackS   = rf.isEnvAttack();

        // --- Mixer ---
        boolean mixAS = (chAToneEnNS || toneAS) && (chANoiseEnNS || noiseS);
        boolean mixBS = (chBToneEnNS || toneBS) && (chBNoiseEnNS || noiseS);
        boolean mixCS = (chCToneEnNS || toneCS) && (chCNoiseEnNS || noiseS);

        // --- Envelope DAC index (from current envelope state) ---
        int dacEnvIndexS = env.dacIndex(envAttackS);

        // --- Channel envelope-or-fixed indices ---
        int idxAEnvS = chAModeS ? dacEnvIndexS : chAIndexR;
        int idxBEnvS = chBModeS ? dacEnvIndexS : chBIndexR;
        int idxCEnvS = chCModeS ? dacEnvIndexS : chCIndexR;

        // --- Mixer-gated DAC index outputs ---
        int idxAS = mixAS ? idxAEnvS : 0;
        int idxBS = mixBS ? idxBEnvS : 0;
        int idxCS = mixCS ? idxCEnvS : 0;

        // ==================================================================
        // REGISTER UPDATE
        // ==================================================================

        // Input flip-flops: always at every rising clk_i
        selNR   = newSelNR;
        busctlR = newBusctl;
        dataIR  = newDataIR;

        // --- Reset vs. normal operation ---
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

        // selFf + DAC index outputs — all gated by enClkPsgI (independent of reset)
        if (enClkPsgI) {
            selFfR = selFfX;
            idxAR  = idxAS & 0x1F;
            idxBR  = idxBS & 0x1F;
            idxCR  = idxCS & 0x1F;
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
        chAIndexR   = 0;
        chBIndexR   = 0;
        chCIndexR   = 0;
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
            updateDacIndexCache(regAddrR, dataIR);
        }
        regAddrR    = regAddrX;
        envShapeWrR = envShapeWrX;
    }

    /**
     * Recompute the fixed DAC index cache for volume registers 8–10.
     *
     * <p>Called whenever a volume register is written so that
     * {@link #risingEdge} can read the pre-decoded index in O(1).
     *
     * @param reg   register index (only 8–10 are acted upon)
     * @param value raw byte value written to the register
     */
    private void updateDacIndexCache(int reg, int value) {
        if (reg < 8 || reg > 10) return;
        int bits = value & 0x0F;
        // Volume bits 1–15 map to odd DAC indices: 1→3, 2→5, …, 15→31
        // (each 4-bit level occupies two consecutive ROM entries; the upper of
        // the pair is always used for non-zero levels).  Volume 0 → index 0.
        int idx  = (bits == 0) ? 0 : ((bits << 1) | 1);
        switch (reg) {
            case 8  -> chAIndexR = idx;
            case 9  -> chBIndexR = idx;
            case 10 -> chCIndexR = idx;
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
        updateDacIndexCache(reg, value);
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
     * Advance the simulation by {@code cycles} rising-clock-edges.
     *
     * <p>Each cycle uses standard operating inputs:
     * {@code en_clk_psg_i = true, sel_n_i = false, reset_n_i = true} and
     * an inactive bus.
     *
     * @param cycles number of clock cycles to simulate (must be &gt; 0)
     * @throws IllegalArgumentException if {@code cycles} is not positive
     */
    public void run(int cycles) {
        if (cycles <= 0) {
            throw new IllegalArgumentException("cycles must be positive; got: " + cycles);
        }
        for (int i = 0; i < cycles; i++) {
            risingEdge(true, false, true, false, false, 0);
        }
    }
}
