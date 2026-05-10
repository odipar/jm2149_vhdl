package org.jm2149.vhdl;

/**
 * Cycle-accurate Java model of the ym2149_audio VHDL core (commit ce6654e).
 *
 * <p>Every field corresponds to a VHDL signal.  Signals whose names end with
 * {@code _r} are registered (flip-flop) state; they are the only mutable
 * state between calls.  All {@code _x} (next-state) and {@code _s}
 * (combinatorial) values are local variables recomputed inside
 * {@link #risingEdge()}.
 *
 * <p>Usage:
 * <pre>
 *   Ym2149Audio psg = new Ym2149Audio();
 *   psg.resetNI  = false;  // active-low reset
 *   psg.selNI    = false;
 *   psg.enClkPsgI = false;
 *   for (int i = 0; i &lt; N; i++) {
 *       // set other inputs ...
 *       psg.risingEdge();
 *   }
 * </pre>
 */
public class Ym2149Audio {

    // -----------------------------------------------------------------------
    // Inputs  (set by the caller before each risingEdge() call)
    // -----------------------------------------------------------------------

    /** PSG clock-enable strobe (en_clk_psg_i). */
    public boolean enClkPsgI = false;

    /** Divide select, 0 = clock-enable / 2 (sel_n_i). */
    public boolean selNI = false;

    /** Active-low reset (reset_n_i). */
    public boolean resetNI = false;

    /** Bus control (bc_i). */
    public boolean bcI = false;

    /** Bus direction (bdir_i). */
    public boolean bdirI = false;

    /** 8-bit data bus input (data_i). */
    public int dataI = 0;

    // -----------------------------------------------------------------------
    // Outputs  (valid after risingEdge() returns)
    // -----------------------------------------------------------------------

    /** Registered data output (data_r_o). */
    public int dataRO = 0;

    /** Channel A unsigned 12-bit DAC output (ch_a_o). */
    public int chAO = 0;

    /** Channel B unsigned 12-bit DAC output (ch_b_o). */
    public int chBO = 0;

    /** Channel C unsigned 12-bit DAC output (ch_c_o). */
    public int chCO = 0;

    /** Unsigned 14-bit mix audio (mix_audio_o). */
    public int mixAudioO = 0;

    /** Unsigned 14-bit signed PCM output (pcm14s_o). */
    public int pcm14sO = 0;

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
    // Flatline thresholds
    // -----------------------------------------------------------------------

    /** Default tone flatline threshold matching the VHDL specification. */
    public static final int DEFAULT_TONE_FLATLINE_THRESHOLD  = 6;
    /** Default noise flatline threshold matching the VHDL specification. */
    public static final int DEFAULT_NOISE_FLATLINE_THRESHOLD = 5;

    /**
     * Tone periods strictly below this value are driven to a constant {@code '1'}.
     */
    private final int toneFlatlineThreshold;

    /**
     * Noise periods strictly below this value are driven to a constant {@code '1'}.
     */
    private final int noiseFlatlineThreshold;

    // -----------------------------------------------------------------------
    // Registered signals (_r) — VHDL flip-flop state
    // -----------------------------------------------------------------------

    // Input registration (always latched at every rising clk_i)
    private boolean selNR    = false;   // sel_n_r  (VHDL init '1', but '0' after first edge with sel_n_i=0)
    private int     busctlR  = 0;       // busctl_r (2-bit)
    private int     dataIR   = 0;       // data_i_r (8-bit)

    // Bus interface / register file
    private int     regAddrR      = 0;       // reg_addr_r (4-bit)
    private int     dataOR        = 0;       // data_o_r   (8-bit)
    private boolean envShapeWrR   = false;   // env_shape_wr_r
    private int     chALevelR     = 0;       // ch_a_level_r (12-bit; reset → 0x800)
    private int     chBLevelR     = 0;       // ch_b_level_r
    private int     chCLevelR     = 0;       // ch_c_level_r
    private final int[] regFileAr = new int[16]; // 16 × 8-bit register file

    // Clock conditioning
    private boolean selFfR   = true;    // sel_ff_r (VHDL init '1'; no reset)
    private int     clkDiv8R = 0;       // clk_div8_r (3-bit)
    private boolean enCntR   = false;   // en_cnt_r

    // Tone counters and flip-flops
    private int     chACntR = 0;        // ch_a_cnt_r (12-bit)
    private boolean toneAR  = true;     // tone_a_r   (VHDL init '1')
    private int     chBCntR = 0;
    private boolean toneBR  = true;
    private int     chCCntR = 0;
    private boolean toneCR  = true;

    // Noise
    private int     noiseCntR  = 0;          // noise_cnt_r  (5-bit)
    private boolean noiseFfR   = true;        // noise_ff_r   (VHDL init '1')
    private int     noiseLfsrR = 0x1_0000;   // noise_lfsr_r (17-bit, VHDL init 1_0000000000000000)

    // Envelope period counter and flip-flop
    private int     envCntR   = 0;    // env_cnt_r   (16-bit)
    private boolean envFfR    = true;  // env_ff_r    (VHDL init '1')
    private int     shapeCntR = 0;    // shape_cnt_r (5-bit)

    // Envelope FSM flip-flops
    private boolean continueFfR = true;   // continue_ff_r (VHDL init '1')
    private boolean attackFfR   = false;  // attack_ff_r   (VHDL init '0')
    private boolean holdFfR     = false;  // hold_ff_r     (VHDL init '0')

    // DAC output registers
    private int dacAR     = 0;  // dac_a_r (12-bit)
    private int dacBR     = 0;  // dac_b_r
    private int dacCR     = 0;  // dac_c_r
    private int sumAudioR = 0;  // sum_audio_r (14-bit)

    // Signed PCM registers
    private int signAR  = 0;  // sign_a_r (12-bit)
    private int signBR  = 0;
    private int signCR  = 0;
    private int pcm14sR = 0;  // pcm14s_r (14-bit)

    /**
     * Construct a model using the default VHDL flatline thresholds
     * (tone: {@value #DEFAULT_TONE_FLATLINE_THRESHOLD},
     *  noise: {@value #DEFAULT_NOISE_FLATLINE_THRESHOLD}).
     */
    public Ym2149Audio() {
        this(DEFAULT_TONE_FLATLINE_THRESHOLD, DEFAULT_NOISE_FLATLINE_THRESHOLD);
    }

    /**
     * Construct a model with configurable flatline thresholds.
     *
     * @param toneFlatlineThreshold   tone periods strictly below this value are
     *                                driven to a constant {@code '1'}; use
     *                                {@value #DEFAULT_TONE_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     * @param noiseFlatlineThreshold  noise periods strictly below this value are
     *                                driven to a constant {@code '1'}; use
     *                                {@value #DEFAULT_NOISE_FLATLINE_THRESHOLD}
     *                                for VHDL-spec behaviour
     */
    public Ym2149Audio(int toneFlatlineThreshold, int noiseFlatlineThreshold) {
        this.toneFlatlineThreshold  = toneFlatlineThreshold;
        this.noiseFlatlineThreshold = noiseFlatlineThreshold;
    }

    // -----------------------------------------------------------------------
    // Single rising-edge simulation
    // -----------------------------------------------------------------------

    /**
     * Simulate one rising edge of {@code clk_i}.
     *
     * <p>All inputs must be set before calling; all outputs are valid
     * (and may have changed) after returning.
     */
    public void risingEdge() {

        // ==================================================================
        // COMBINATORIAL (_x / _s) — computed from current registered state
        // ==================================================================

        // --- Input-registration next-state (always latches) ---------------
        boolean newSelNR  = selNI;
        int     newBusctl = ((bdirI ? 1 : 0) << 1) | (bcI ? 1 : 0);
        int     newDataIR = dataI & 0xFF;

        // --- Bus interface (combinatorial from busctlR, dataIR, regAddrR) --
        int     regAddrX     = regAddrR;
        boolean enDataRdS    = false;
        boolean enDataWrS    = false;

        switch (busctlR & 0x3) {
            case 0: break;                  // inactive
            case 1: enDataRdS = true; break; // read
            case 2: enDataWrS = true; break; // write
            default:                         // latch address (case 3)
                // ADDRESS_G = 0x0 (factory default)
                if ((dataIR >>> 4) == 0x0) {
                    regAddrX = dataIR & 0x0F;
                }
                break;
        }

        // env_shape_wr_x = en_data_wr_s when reg_addr_r = 0xD else '0'
        boolean envShapeWrX = enDataWrS && (regAddrR == 0xD);

        // DAC level for register writes (combinatorial from dataIR)
        int regBits       = dataIR & 0x0F;
        int dacBit0       = (regBits == 0) ? 0 : 1;
        int dacRegIdx     = (regBits << 1) | dacBit0;
        int dacRegLevelS  = DACROM[dacRegIdx];

        // ch_x_level_x — next value of level register
        int chALevelX = (regAddrR == 8)  ? dacRegLevelS : chALevelR;
        int chBLevelX = (regAddrR == 9)  ? dacRegLevelS : chBLevelR;
        int chCLevelX = (regAddrR == 0xA) ? dacRegLevelS : chCLevelR;

        // --- Register-file name mapping (combinatorial) --------------------
        int     chAPeriodS    = ((regFileAr[1] & 0x0F) << 8) | (regFileAr[0] & 0xFF);
        boolean chAToneEnNS   = (regFileAr[7] & 0x01) != 0;
        boolean chANoiseEnNS  = (regFileAr[7] & 0x08) != 0;
        boolean chAModeS      = (regFileAr[8]  & 0x10) != 0;

        int     chBPeriodS    = ((regFileAr[3] & 0x0F) << 8) | (regFileAr[2] & 0xFF);
        boolean chBToneEnNS   = (regFileAr[7] & 0x02) != 0;
        boolean chBNoiseEnNS  = (regFileAr[7] & 0x10) != 0;
        boolean chBModeS      = (regFileAr[9]  & 0x10) != 0;

        int     chCPeriodS    = ((regFileAr[5] & 0x0F) << 8) | (regFileAr[4] & 0xFF);
        boolean chCToneEnNS   = (regFileAr[7] & 0x04) != 0;
        boolean chCNoiseEnNS  = (regFileAr[7] & 0x20) != 0;
        boolean chCModeS      = (regFileAr[10] & 0x10) != 0;

        int     noisePeriodS  = regFileAr[6] & 0x1F;

        int     envPeriodS    = ((regFileAr[12] & 0xFF) << 8) | (regFileAr[11] & 0xFF);
        boolean envContinueS  = (regFileAr[13] & 0x08) != 0;
        boolean envAttackS    = (regFileAr[13] & 0x04) != 0;
        boolean envAlternateS = (regFileAr[13] & 0x02) != 0;
        boolean envHoldS      = (regFileAr[13] & 0x01) != 0;

        // --- Clock conditioning --------------------------------------------
        // sel_ff_x = not sel_ff_r
        boolean selFfX = !selFfR;

        // en_int_clk_psg_s = en_clk_psg_i and sel_ff_r when sel_n_r='0' else en_clk_psg_i
        boolean enIntClkPsgS = (!selNR) ? (enClkPsgI && selFfR) : enClkPsgI;

        // Clock divider (3-bit counter, wraps at 8)
        int     clkDiv8X = (clkDiv8R + 1) & 0x7;
        // en_cnt_x = '1' when clk_div8_r = 3
        boolean enCntX   = (clkDiv8R == 3);

        // --- Tone counter A ------------------------------------------------
        int chACntX;
        if (enCntX && chACntR >= chAPeriodS) {
            chACntX = 0;
        } else if (enCntR) {
            chACntX = (chACntR + 1) & 0xFFF;
        } else {
            chACntX = chACntR;
        }
        boolean flatlineAS = chAPeriodS < toneFlatlineThreshold;
        boolean toneAX;
        if (flatlineAS) {
            toneAX = true;
        } else if (enCntX && chACntR >= chAPeriodS) {
            toneAX = !toneAR;
        } else {
            toneAX = toneAR;
        }

        // --- Tone counter B ------------------------------------------------
        int chBCntX;
        if (enCntX && chBCntR >= chBPeriodS) {
            chBCntX = 0;
        } else if (enCntR) {
            chBCntX = (chBCntR + 1) & 0xFFF;
        } else {
            chBCntX = chBCntR;
        }
        boolean flatlineBS = chBPeriodS < toneFlatlineThreshold;
        boolean toneBX;
        if (flatlineBS) {
            toneBX = true;
        } else if (enCntX && chBCntR >= chBPeriodS) {
            toneBX = !toneBR;
        } else {
            toneBX = toneBR;
        }

        // --- Tone counter C ------------------------------------------------
        int chCCntX;
        if (enCntX && chCCntR >= chCPeriodS) {
            chCCntX = 0;
        } else if (enCntR) {
            chCCntX = (chCCntR + 1) & 0xFFF;
        } else {
            chCCntX = chCCntR;
        }
        boolean flatlineCS = chCPeriodS < toneFlatlineThreshold;
        boolean toneCX;
        if (flatlineCS) {
            toneCX = true;
        } else if (enCntX && chCCntR >= chCPeriodS) {
            toneCX = !toneCR;
        } else {
            toneCX = toneCR;
        }

        // --- Noise counter -------------------------------------------------
        int noiseCntX;
        if (enCntX && noiseCntR >= noisePeriodS) {
            noiseCntX = 0;
        } else if (enCntR) {
            noiseCntX = (noiseCntR + 1) & 0x1F;
        } else {
            noiseCntX = noiseCntR;
        }
        boolean flatlineNS = noisePeriodS < noiseFlatlineThreshold;
        boolean noiseFfX;
        if (flatlineNS) {
            noiseFfX = true;
        } else if (enCntX && noiseCntR >= noisePeriodS) {
            noiseFfX = !noiseFfR;
        } else {
            noiseFfX = noiseFfR;
        }

        // 17-bit LFSR: taps at bit 0 and bit 3 (right-shift, feedback into MSB)
        // noise_fb_s   = noise_lfsr_r(3) xor noise_lfsr_r(0)
        // noise_lfsr_x = noise_fb_s & noise_lfsr_r(16 downto 1)
        boolean noiseFbS   = (((noiseLfsrR >> 3) ^ noiseLfsrR) & 1) != 0;
        int     noiseLfsrX = ((noiseFbS ? 1 : 0) << 16) | ((noiseLfsrR >>> 1) & 0xFFFF);
        boolean noiseS     = (noiseLfsrR & 1) != 0;

        // --- Mixer ---------------------------------------------------------
        boolean mixAS = (chAToneEnNS || toneAR) && (chANoiseEnNS || noiseS);
        boolean mixBS = (chBToneEnNS || toneBR) && (chBNoiseEnNS || noiseS);
        boolean mixCS = (chCToneEnNS || toneCR) && (chCNoiseEnNS || noiseS);

        // --- Envelope period counter ---------------------------------------
        int envCntX;
        if (enCntX && envCntR >= envPeriodS) {
            envCntX = 0;
        } else if (enCntR) {
            envCntX = (envCntR + 1) & 0xFFFF;
        } else {
            envCntX = envCntR;
        }
        boolean envFfX;
        if (enCntX && envCntR >= envPeriodS) {
            envFfX = !envFfR;
        } else {
            envFfX = envFfR;
        }

        // env_rst_s = (not reset_n_i) or env_shape_wr_r   [active HIGH]
        boolean envRstS = !resetNI || envShapeWrR;

        // --- Envelope shape counter and FSM --------------------------------
        // hold_ff_x = env_hold_s when shape_cnt_r = 31 else hold_ff_r
        boolean holdFfX = (shapeCntR == 31) ? envHoldS : holdFfR;

        // shape_cnt_x = (others=>'1') when hold_ff_x='1' else shape_cnt_r+1
        int shapeCntX = holdFfX ? 0x1F : ((shapeCntR + 1) & 0x1F);

        // continue_ff_x = env_continue_s when shape_cnt_r = 31 else continue_ff_r
        boolean continueFfX = (shapeCntR == 31) ? envContinueS : continueFfR;

        // env_sel_s = not attack_ff_r when env_attack_s='1' else attack_ff_r
        boolean envSelS = envAttackS ? !attackFfR : attackFfR;

        // attack_ff_x = not attack_ff_r when (shape_cnt_r=31 and env_alternate_s='1')
        boolean attackFfX = (shapeCntR == 31 && envAlternateS) ? !attackFfR : attackFfR;

        // env_out_s
        int envOutS;
        if (!continueFfR) {
            envOutS = 0;
        } else if (!envSelS) {
            envOutS = (~shapeCntR) & 0x1F;  // not shape_cnt_r (5-bit invert)
        } else {
            envOutS = shapeCntR & 0x1F;
        }
        int dacEnvLevelS = DACROM[envOutS];

        // --- Amplitude (gated by mixer output) -----------------------------
        int levelAS = !mixAS ? 0 : (!chAModeS ? chALevelR : dacEnvLevelS);
        int levelBS = !mixBS ? 0 : (!chBModeS ? chBLevelR : dacEnvLevelS);
        int levelCS = !mixCS ? 0 : (!chCModeS ? chCLevelR : dacEnvLevelS);

        // --- Signed PCM level (not gated by mixer) -------------------------
        int levelAEnvS = chAModeS ? dacEnvLevelS : chALevelR;
        int levelBEnvS = chBModeS ? dacEnvLevelS : chBLevelR;
        int levelCEnvS = chCModeS ? dacEnvLevelS : chCLevelR;

        // sign_a_x : flat / negative / positive
        // Flat condition: both enables OFF, or tone flat-lined,
        //                 or noise enabled+flat-lined
        boolean signAFlat = (chAToneEnNS && chANoiseEnNS)
                          || flatlineAS
                          || (!chANoiseEnNS && flatlineNS);
        int signAX;
        if (signAFlat) {
            signAX = (levelAEnvS - 0x800) & 0xFFF;
        } else if (!mixAS) {
            // ("1" & (not level_a_env_s(11 downto 1))) + 1  → two's-complement −(level/2)
            // Parenthesise carefully: concatenate FIRST, then add 1.
            int half = (levelAEnvS >> 1) & 0x7FF;
            signAX = ((0x800 | ((~half) & 0x7FF)) + 1) & 0xFFF;
        } else {
            // "0" & level_a_env_s(11 downto 1)  → +(level/2)
            signAX = (levelAEnvS >> 1) & 0x7FF;
        }

        boolean signBFlat = (chBToneEnNS && chBNoiseEnNS)
                          || flatlineBS
                          || (!chBNoiseEnNS && flatlineNS);
        int signBX;
        if (signBFlat) {
            signBX = (levelBEnvS - 0x800) & 0xFFF;
        } else if (!mixBS) {
            int half = (levelBEnvS >> 1) & 0x7FF;
            signBX = ((0x800 | ((~half) & 0x7FF)) + 1) & 0xFFF;
        } else {
            signBX = (levelBEnvS >> 1) & 0x7FF;
        }

        boolean signCFlat = (chCToneEnNS && chCNoiseEnNS)
                          || flatlineCS
                          || (!chCNoiseEnNS && flatlineNS);
        int signCX;
        if (signCFlat) {
            signCX = (levelCEnvS - 0x800) & 0xFFF;
        } else if (!mixCS) {
            int half = (levelCEnvS >> 1) & 0x7FF;
            signCX = ((0x800 | ((~half) & 0x7FF)) + 1) & 0xFFF;
        } else {
            signCX = (levelCEnvS >> 1) & 0x7FF;
        }

        // ==================================================================
        // REGISTER UPDATE — apply next-state to all _r fields
        // ==================================================================

        // Input registration: always at every rising clk_i
        selNR   = newSelNR;
        busctlR = newBusctl;
        dataIR  = newDataIR;

        // --- Register file process (reset_n_i or en_clk_psg_i) ------------
        if (!resetNI) {
            regAddrR     = 0;
            dataOR       = 0;
            envShapeWrR  = false;
            chALevelR    = 0x800;
            chBLevelR    = 0x800;
            chCLevelR    = 0x800;
            for (int i = 0; i < 16; i++) regFileAr[i] = 0;
        } else if (enClkPsgI) {
            // Read or write using OLD regAddrR (VHDL concurrent semantics)
            if (enDataRdS) {
                dataOR = regFileAr[regAddrR] & 0xFF;
            } else if (enDataWrS) {
                regFileAr[regAddrR] = dataIR;
                chALevelR = chALevelX;
                chBLevelR = chBLevelX;
                chCLevelR = chCLevelX;
            }
            // Update address register AFTER read/write (uses old value above)
            regAddrR    = regAddrX;
            envShapeWrR = envShapeWrX;
        }

        // --- Clock conditioning (no reset for sel_ff_r) -------------------
        if (enClkPsgI) {
            selFfR = selFfX;
        }

        // --- Clock divider + count enable (reset or en_int_clk_psg_s) -----
        if (!resetNI) {
            clkDiv8R = 0;
            enCntR   = false;
        } else if (enIntClkPsgS) {
            clkDiv8R = clkDiv8X;
            enCntR   = enCntX;
        }

        // --- Tone counters (reset or en_int_clk_psg_s) --------------------
        if (!resetNI) {
            chACntR = 0; toneAR = true;
            chBCntR = 0; toneBR = true;
            chCCntR = 0; toneCR = true;
        } else if (enIntClkPsgS) {
            chACntR = chACntX; toneAR = toneAX;
            chBCntR = chBCntX; toneBR = toneBX;
            chCCntR = chCCntX; toneCR = toneCX;
        }

        // --- Noise (reset or en_int_clk_psg_s) ----------------------------
        if (!resetNI) {
            noiseCntR  = 0;
            noiseFfR   = true;
            noiseLfsrR = 0x1_0000;
        } else if (enIntClkPsgS) {
            boolean oldNoiseFfR = noiseFfR;  // pre-edge value for edge-detect
            noiseCntR = noiseCntX;
            noiseFfR  = noiseFfX;
            // Rising-edge detect on noise_ff: uses OLD noiseFfR (pre-edge)
            if (!oldNoiseFfR && noiseFfX) {
                noiseLfsrR = noiseLfsrX;
            }
        }

        // --- Envelope counter + shape counter + FSM -----------------------
        // All three VHDL processes use the same edge-detect condition on
        // env_ff_r, and all updates are simultaneous (VHDL semantics).
        // Save the pre-edge values before updating.
        if (envRstS) {
            envCntR     = 0;
            envFfR      = true;
            shapeCntR   = 0;
            continueFfR = true;
            attackFfR   = false;
            holdFfR     = false;
        } else if (enIntClkPsgS) {
            boolean oldEnvFfR  = envFfR;   // pre-edge value for edge-detect
            boolean oldHoldFfR = holdFfR;  // pre-edge value (shape-cnt gating)
            envCntR = envCntX;
            envFfR  = envFfX;
            // Edge-detect on env_ff (any edge), gated by hold
            if (!oldHoldFfR && (oldEnvFfR != envFfX)) {
                shapeCntR   = shapeCntX;
                continueFfR = continueFfX;
                attackFfR   = attackFfX;
                holdFfR     = holdFfX;
            }
        }

        // --- DAC registers (en_clk_psg_i, NOT the internal divided clock) -
        if (enClkPsgI) {
            // sum uses OLD dac values (VHDL concurrent semantics)
            sumAudioR = (dacAR + dacBR + dacCR) & 0x3FFF;
            dacAR = levelAS & 0xFFF;
            dacBR = levelBS & 0xFFF;
            dacCR = levelCS & 0xFFF;
        }

        // --- Signed PCM (en_clk_psg_i) ------------------------------------
        if (enClkPsgI) {
            // pcm sum uses OLD sign values (VHDL concurrent semantics)
            pcm14sR = (signExt12to14(signAR)
                     + signExt12to14(signBR)
                     + signExt12to14(signCR)) & 0x3FFF;
            signAR = signAX & 0xFFF;
            signBR = signBX & 0xFFF;
            signCR = signCX & 0xFFF;
        }

        // ==================================================================
        // OUTPUT WIRES  (direct connections in VHDL)
        // ==================================================================
        dataRO    = dataOR;
        chAO      = dacAR;
        chBO      = dacBR;
        chCO      = dacCR;
        mixAudioO = sumAudioR;
        pcm14sO   = pcm14sR;
    }

    // -----------------------------------------------------------------------
    // Helper: sign-extend a 12-bit value to 14 bits
    // -----------------------------------------------------------------------
    private static int signExt12to14(int v12) {
        // replicate bit 11 into bits 12 and 13
        if ((v12 & 0x800) != 0) {
            return v12 | 0x3000;
        }
        return v12 & 0xFFF;
    }
}
