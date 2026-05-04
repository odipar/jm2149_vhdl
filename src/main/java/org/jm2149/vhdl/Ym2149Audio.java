package org.jm2149.vhdl;

/**
 * Faithful 1:1 Java implementation of the ym2149_audio VHDL entity.
 *
 * <p>Models the YM-2149 / AY-3-8910 Complex Sound Generator at the
 * register-transfer level (RTL), following the ym2149_audio.vhd specification
 * by Matthew Hagerty (https://dnotq.io).
 *
 * <p>Each call to {@link #risingEdge} simulates one rising edge of the system
 * clock (clk_i), driving the registered state forward exactly as the VHDL
 * synthesisable RTL does.  Combinatorial signals are computed fresh every tick;
 * registered signals are updated at the end of the tick.
 *
 * <p>Bus interface (BDIR / BC1, simplified – no BC2):
 * <pre>
 *   BDIR  BC1   State
 *     0    0    Inactive
 *     0    1    Read from PSG register
 *     1    0    Write to PSG register
 *     1    1    Latch address
 * </pre>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@link #getChA()} – 12-bit unsigned channel-A DAC level</li>
 *   <li>{@link #getChB()} – 12-bit unsigned channel-B DAC level</li>
 *   <li>{@link #getChC()} – 12-bit unsigned channel-C DAC level</li>
 *   <li>{@link #getMixAudio()} – 14-bit unsigned sum of all channels</li>
 *   <li>{@link #getPcm14s()} – 14-bit signed (stored unsigned) PCM sum</li>
 * </ul>
 */
public final class Ym2149Audio {

    // -----------------------------------------------------------------------
    // DAC lookup table (32 entries, 12-bit values, logarithmic – 1.5 dB steps)
    // From ym2149_audio.vhd lines 361-369.
    // -----------------------------------------------------------------------
    private static final int[] DACROM = {
        0x000, 0x017, 0x01B, 0x021, 0x027, 0x02E, 0x037, 0x041,
        0x04D, 0x05C, 0x06D, 0x081, 0x09A, 0x0B7, 0x0D9, 0x102,
        0x133, 0x16D, 0x1B2, 0x204, 0x265, 0x2D8, 0x361, 0x405,
        0x4C7, 0x5AD, 0x6BF, 0x804, 0x987, 0xB53, 0xD76, 0xFFF
    };

    // -----------------------------------------------------------------------
    // Registered state  (names mirror the VHDL _r signals)
    // -----------------------------------------------------------------------

    // Bus interface
    private int    regAddrR    = 0;         // unsigned(3:0)
    private int    dataIR      = 0;         // std_logic_vector(7:0)
    private int    dataOR      = 0;         // std_logic_vector(7:0)
    private int    busctlR     = 0;         // std_logic_vector(1:0)
    private boolean selNR      = true;      // sel_n registered input
    private boolean selFfR     = true;      // sel_ff flip-flop

    // Register file (16 × 8-bit)
    private final int[] regFile = new int[16];

    // Channel DAC level registers (12-bit); reset value = 0x800
    private int chALevelR  = 0x800;
    private int chBLevelR  = 0x800;
    private int chCLevelR  = 0x800;

    // Clock conditioning
    private int     clkDiv8R  = 0;          // unsigned(2:0)
    private boolean enCntR    = false;

    // Channel tone counters and flip-flops
    private int     chACntR   = 0;          // unsigned(11:0)
    private boolean toneAR    = true;
    private int     chBCntR   = 0;
    private boolean toneBR    = true;
    private int     chCCntR   = 0;
    private boolean toneCR    = true;

    // Noise
    private int     noiseCntR  = 0;         // unsigned(4:0)
    private boolean noiseFfR   = true;
    private int     noiseLfsrR = 0x10000;   // 17-bit; reset value b"1_0000…0000"

    // Envelope
    private boolean envShapeWrR = false;
    private int     envCntR     = 0;        // unsigned(15:0)
    private boolean envFfR      = true;
    private int     shapeCntR   = 0;        // unsigned(4:0)
    private boolean continueFfR = true;
    private boolean attackFfR   = false;
    private boolean holdFfR     = false;

    // DAC output registers
    private int dacAR      = 0;             // unsigned(11:0)
    private int dacBR      = 0;
    private int dacCR      = 0;
    private int sumAudioR  = 0;             // unsigned(13:0)

    // PCM signed-14 registers (stored as unsigned 14-bit)
    private int signAR     = 0;             // unsigned(11:0) – twos-complement
    private int signBR     = 0;
    private int signCR     = 0;
    private int pcm14sR    = 0;             // unsigned(13:0)

    // -----------------------------------------------------------------------
    // Outputs (connected directly to registered signals in VHDL)
    // -----------------------------------------------------------------------

    /** 12-bit unsigned channel-A DAC output (ch_a_o = dac_a_r). */
    public int getChA()      { return dacAR; }

    /** 12-bit unsigned channel-B DAC output (ch_b_o = dac_b_r). */
    public int getChB()      { return dacBR; }

    /** 12-bit unsigned channel-C DAC output (ch_c_o = dac_c_r). */
    public int getChC()      { return dacCR; }

    /** 14-bit unsigned mixed audio output (mix_audio_o = sum_audio_r). */
    public int getMixAudio() { return sumAudioR; }

    /** 14-bit unsigned PCM output (pcm14s_o = pcm14s_r). */
    public int getPcm14s()   { return pcm14sR; }

    // -----------------------------------------------------------------------
    // Rising-edge simulation
    // -----------------------------------------------------------------------

    /**
     * Simulate one rising edge of the system clock (clk_i).
     *
     * @param enClkPsgI  PSG clock enable strobe (single-cycle high pulse)
     * @param selNI      clock divide select (0 = divide by 2, 1 = pass-through)
     * @param resetNI    active-low global reset
     * @param bcI        bus control (BC1)
     * @param bdirI      bus direction (BDIR)
     * @param dataI      8-bit data bus input
     */
    public void risingEdge(boolean enClkPsgI, boolean selNI,
                           boolean resetNI,
                           boolean bcI,   boolean bdirI, int dataI) {

        // ===================================================================
        // Phase 1 – compute all combinatorial (_x) signals from CURRENT state
        // ===================================================================

        // ----- Bus interface -----------------------------------------------
        // Combinatorial decode of the previously-registered busctl_r and data.
        // busctl_r encodes {bdir, bc1} from the previous clock edge.
        boolean enDataRdS  = false;
        boolean enDataWrS  = false;
        int     regAddrX   = regAddrR;

        switch (busctlR) {
            case 0b00 -> { /* inactive */ }
            case 0b01 -> enDataRdS = true;
            case 0b10 -> enDataWrS = true;
            default   -> {  // 0b11 – latch address
                if ((dataIR >>> 4) == 0) {       // ADDRESS_G = 0x0
                    regAddrX = dataIR & 0xF;
                }
            }
        }

        // Envelope shape write strobe (active during write cycle to R13).
        boolean envShapeWrX = enDataWrS && (regAddrR == 0xD);

        // ----- DAC register-level conversion --------------------------------
        // dac_reg_bit0_s: '0' when low nibble is 0, else '1'.
        int dacLevel4 = dataIR & 0xF;
        int dacRegIdx = (dacLevel4 == 0) ? 0 : (dacLevel4 * 2 + 1);
        int dacRegLevelS = DACROM[dacRegIdx];

        int chALevelX = (regAddrR == 0x8) ? dacRegLevelS : chALevelR;
        int chBLevelX = (regAddrR == 0x9) ? dacRegLevelS : chBLevelR;
        int chCLevelX = (regAddrR == 0xA) ? dacRegLevelS : chCLevelR;

        // ----- Register-file name mappings ----------------------------------
        int  chAPeriodS    = ((regFile[1] & 0xF) << 8) | (regFile[0] & 0xFF);
        boolean chAToneEnNS  = (regFile[7] & 0x01) != 0;
        boolean chANoiseEnNS = (regFile[7] & 0x08) != 0;
        boolean chAModeS     = (regFile[8] & 0x10) != 0;

        int  chBPeriodS    = ((regFile[3] & 0xF) << 8) | (regFile[2] & 0xFF);
        boolean chBToneEnNS  = (regFile[7] & 0x02) != 0;
        boolean chBNoiseEnNS = (regFile[7] & 0x10) != 0;
        boolean chBModeS     = (regFile[9] & 0x10) != 0;

        int  chCPeriodS    = ((regFile[5] & 0xF) << 8) | (regFile[4] & 0xFF);
        boolean chCToneEnNS  = (regFile[7] & 0x04) != 0;
        boolean chCNoiseEnNS = (regFile[7] & 0x20) != 0;
        boolean chCModeS     = (regFile[10] & 0x10) != 0;

        int  noisePeriodS  = regFile[6] & 0x1F;

        int  envPeriodS    = ((regFile[12] & 0xFF) << 8) | (regFile[11] & 0xFF);
        boolean envContinueS = (regFile[13] & 0x08) != 0;
        boolean envAttackS   = (regFile[13] & 0x04) != 0;
        boolean envAlternateS= (regFile[13] & 0x02) != 0;
        boolean envHoldS     = (regFile[13] & 0x01) != 0;

        // ----- Sel / clock conditioning -------------------------------------
        boolean selFfX = !selFfR;

        // en_int_clk_psg_s: pass-through or divided by 2
        boolean enIntClkPsgS = selNR ? enClkPsgI : (enClkPsgI && selFfR);

        // clk_div8 and count enable (look-ahead)
        int     clkDiv8X  = (clkDiv8R + 1) & 0x7;
        boolean enCntX    = (clkDiv8R == 3);

        // ----- Channel A tone counter and flip-flop -------------------------
        boolean flatlineAS = chAPeriodS < 6;

        int chACntX;
        if (enCntX && (chACntR >= chAPeriodS)) {
            chACntX = 0;
        } else if (enCntR) {
            chACntX = (chACntR + 1) & 0xFFF;
        } else {
            chACntX = chACntR;
        }

        boolean toneAX;
        if (flatlineAS) {
            toneAX = true;
        } else if (enCntX && (chACntR >= chAPeriodS)) {
            toneAX = !toneAR;
        } else {
            toneAX = toneAR;
        }

        // ----- Channel B tone counter and flip-flop -------------------------
        boolean flatlineBS = chBPeriodS < 6;

        int chBCntX;
        if (enCntX && (chBCntR >= chBPeriodS)) {
            chBCntX = 0;
        } else if (enCntR) {
            chBCntX = (chBCntR + 1) & 0xFFF;
        } else {
            chBCntX = chBCntR;
        }

        boolean toneBX;
        if (flatlineBS) {
            toneBX = true;
        } else if (enCntX && (chBCntR >= chBPeriodS)) {
            toneBX = !toneBR;
        } else {
            toneBX = toneBR;
        }

        // ----- Channel C tone counter and flip-flop -------------------------
        boolean flatlineCS = chCPeriodS < 6;

        int chCCntX;
        if (enCntX && (chCCntR >= chCPeriodS)) {
            chCCntX = 0;
        } else if (enCntR) {
            chCCntX = (chCCntR + 1) & 0xFFF;
        } else {
            chCCntX = chCCntR;
        }

        boolean toneCX;
        if (flatlineCS) {
            toneCX = true;
        } else if (enCntX && (chCCntR >= chCPeriodS)) {
            toneCX = !toneCR;
        } else {
            toneCX = toneCR;
        }

        // ----- Noise counter and flip-flop ----------------------------------
        boolean flatlineNS = noisePeriodS < 5;

        int noiseCntX;
        if (enCntX && (noiseCntR >= noisePeriodS)) {
            noiseCntX = 0;
        } else if (enCntR) {
            noiseCntX = (noiseCntR + 1) & 0x1F;
        } else {
            noiseCntX = noiseCntR;
        }

        boolean noiseFfX;
        if (flatlineNS) {
            noiseFfX = true;
        } else if (enCntX && (noiseCntR >= noisePeriodS)) {
            noiseFfX = !noiseFfR;
        } else {
            noiseFfX = noiseFfR;
        }

        // 17-bit right-shift LFSR, taps at 0 and 3.
        int  noiseFbS    = ((noiseLfsrR >>> 3) ^ noiseLfsrR) & 1;
        int  noiseLfsrX  = ((noiseFbS << 16) | (noiseLfsrR >>> 1)) & 0x1FFFF;
        boolean noiseS   = (noiseLfsrR & 1) != 0;

        // ----- Tone/noise mixer ---------------------------------------------
        boolean mixAS = (chAToneEnNS || toneAR) && (chANoiseEnNS || noiseS);
        boolean mixBS = (chBToneEnNS || toneBR) && (chBNoiseEnNS || noiseS);
        boolean mixCS = (chCToneEnNS || toneCR) && (chCNoiseEnNS || noiseS);

        // ----- Envelope counter and flip-flop --------------------------------
        boolean envRstS = !resetNI || envShapeWrR;

        int envCntX;
        if (enCntX && (envCntR >= envPeriodS)) {
            envCntX = 0;
        } else if (enCntR) {
            envCntX = (envCntR + 1) & 0xFFFF;
        } else {
            envCntX = envCntR;
        }

        boolean envFfX = (enCntX && (envCntR >= envPeriodS)) ? !envFfR : envFfR;

        // ----- Envelope FSM -------------------------------------------------
        boolean continueFfX = (shapeCntR == 31) ? envContinueS  : continueFfR;
        boolean envSelS     = envAttackS ? !attackFfR : attackFfR;
        boolean attackFfX   = (shapeCntR == 31 && envAlternateS) ? !attackFfR : attackFfR;
        boolean holdFfX     = (shapeCntR == 31) ? envHoldS : holdFfR;

        // shape_cnt_x: hold forces all 1s; otherwise increment
        int shapeCntX = holdFfX ? 31 : (shapeCntR + 1) & 0x1F;

        // Envelope output (5-bit index into DACROM)
        int envOutS;
        if (!continueFfR) {
            envOutS = 0;
        } else if (!envSelS) {
            envOutS = (~shapeCntR) & 0x1F;
        } else {
            envOutS = shapeCntR & 0x1F;
        }
        int dacEnvLevelS = DACROM[envOutS];

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
        // Phase 2 – update all registered signals (_r ← _x)
        // All updates happen "simultaneously" (as in VHDL).
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

        // Process 5: tone counters (reset / en_int_clk_psg_s)
        if (!resetNI) {
            chACntR = 0;  toneAR = true;
            chBCntR = 0;  toneBR = true;
            chCCntR = 0;  toneCR = true;
        } else if (enIntClkPsgS) {
            chACntR = chACntX;  toneAR = toneAX;
            chBCntR = chBCntX;  toneBR = toneBX;
            chCCntR = chCCntX;  toneCR = toneCX;
        }

        // Process 6: noise counter and LFSR (reset / en_int_clk_psg_s)
        if (!resetNI) {
            noiseCntR  = 0;
            noiseFfR   = true;
            noiseLfsrR = 0x10000;
        } else if (enIntClkPsgS) {
            noiseCntR = noiseCntX;
            // LFSR clocked on rising edge of noise_ff (look-ahead detect)
            if (!noiseFfR && noiseFfX) {
                noiseLfsrR = noiseLfsrX;
            }
            noiseFfR = noiseFfX;
        }

        // Process 7: envelope counter / ff / shape counter (env_rst_s or en_int_clk_psg_s)
        if (envRstS) {
            envCntR   = 0;
            envFfR    = true;
            shapeCntR = 0;
        } else if (enIntClkPsgS) {
            envCntR = envCntX;
            envFfR  = envFfX;
            // Shape counter clocked on ANY edge of env_ff when not held
            if (!holdFfR && (envFfR != envFfX)) {
                shapeCntR = shapeCntX;
            }
        }

        // Process 8: envelope FSM flip-flops (env_rst_s or en_int_clk_psg_s)
        if (envRstS) {
            continueFfR = true;
            attackFfR   = false;
            holdFfR     = false;
        } else if (enIntClkPsgS) {
            if (!holdFfR && (envFfR != envFfX)) {
                continueFfR = continueFfX;
                attackFfR   = attackFfX;
                holdFfR     = holdFfX;
            }
        }

        // Process 9: DAC output registers (en_clk_psg_i only)
        if (enClkPsgI) {
            // sum_audio_r uses the OLD dac values (before update this cycle)
            sumAudioR = (dacAR + dacBR + dacCR) & 0x3FFF;
            dacAR = levelAS & 0xFFF;
            dacBR = levelBS & 0xFFF;
            dacCR = levelCS & 0xFFF;
        }

        // Process 10: PCM signed registers (en_clk_psg_i only)
        if (enClkPsgI) {
            // pcm14s_r sums sign-extended OLD sign values
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
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Compute the signed-PCM contribution for one channel.
     *
     * <p>Matches the sign_a_x / sign_b_x / sign_c_x combinatorial VHDL logic.
     *
     * @param levelEnv      12-bit amplitude (direct or envelope)
     * @param toneEnN       tone-enable active-low bit
     * @param noiseEnN      noise-enable active-low bit
     * @param flatlineTone  tone counter flatline flag
     * @param flatlineNoise noise counter flatline flag
     * @param mix           combined tone/noise mixer output
     * @return 12-bit unsigned representation of the signed value
     */
    private static int computeSign(int levelEnv,
                                   boolean toneEnN, boolean noiseEnN,
                                   boolean flatlineTone, boolean flatlineNoise,
                                   boolean mix) {
        // Flat conditions: channel disabled, tone flatlined, or noise flatlined
        boolean flat = (toneEnN && noiseEnN)
                || flatlineTone
                || (!noiseEnN && flatlineNoise);

        if (flat) {
            // level - 0x800, 12-bit two's complement wrap
            return (levelEnv - 0x800) & 0xFFF;
        } else if (!mix) {
            // tone is low: -(level / 2) in two's complement 12-bit
            // = ("1" & ~level[11:1]) + 1  in VHDL
            int half = (levelEnv >> 1) & 0x7FF;
            return (0x800 | (~half & 0x7FF)) + 1 & 0xFFF;
        } else {
            // tone is high: +(level / 2)
            return (levelEnv >> 1) & 0x7FF;
        }
    }

    /**
     * Sign-extend a 12-bit value stored in the lower 12 bits to 14 bits.
     * Matches VHDL: {@code (sign(11) & sign(11) & sign)}.
     */
    private static int signExtend12to14(int val12) {
        // Sign bit is bit 11
        if ((val12 & 0x800) != 0) {
            return val12 | 0x3000;   // extend with two 1-bits → negative
        }
        return val12 & 0xFFF;        // already positive, high bits are 0
    }
}
