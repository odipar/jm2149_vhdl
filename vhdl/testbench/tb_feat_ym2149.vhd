--
-- Feature Testbench: YM-2149 / AY-3-8910 Complex Sound Generator
-- Exercises: tone generation, noise, mixer, envelope (all shapes), register read-back.
--
-- Clock setup (matching real retro-system at ~21.477 MHz):
--   clk_i_period = 46.560852 ns  (~21.477 MHz master clock)
--   en_clk_psg_i = strobe every 6 master clocks  (~3.579 MHz PSG input rate)
--   sel_n_i = '0'  => internal /2 => ~1.789 MHz effective PSG clock
--   Internal /8 divider => en_cnt fires every 96 master clocks (~4.47 us)
--
-- Tone frequency:  f = 1 / (2 * (period+1) * 4.47us)
-- Envelope step:   dt = (env_period+1) * 4.47 us,  full sweep = 32 steps
--
-- Simulation time: 10 ms  (driven externally via  --stop-time=10ms)
--
-- NVC usage:
--   nvc -a ym2149_audio/rtl/ym2149_audio.vhd testbench/tb_feat_ym2149.vhd
--   nvc -e tb_feat_ym2149
--   nvc -r tb_feat_ym2149 --wave=tb_feat_ym2149.vcd --format=vcd --stop-time=10ms
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity tb_feat_ym2149 is
end tb_feat_ym2149;

architecture behavior of tb_feat_ym2149 is

   component ym2149_audio
   port
   ( clk_i        : in     std_logic
   ; en_clk_psg_i : in     std_logic
   ; sel_n_i      : in     std_logic
   ; reset_n_i    : in     std_logic
   ; bc_i         : in     std_logic
   ; bdir_i       : in     std_logic
   ; data_i       : in     std_logic_vector(7 downto 0)
   ; data_r_o     : out    std_logic_vector(7 downto 0)
   ; ch_a_o       : out    unsigned(11 downto 0)
   ; ch_b_o       : out    unsigned(11 downto 0)
   ; ch_c_o       : out    unsigned(11 downto 0)
   ; mix_audio_o  : out    unsigned(13 downto 0)
   ; pcm14s_o     : out    unsigned(13 downto 0)
   );
   end component;

   -- Inputs
   signal clk_i        : std_logic := '0';
   signal en_clk_psg_i : std_logic := '0';
   signal sel_n_i      : std_logic := '0';
   signal reset_n_i    : std_logic := '0';
   signal bc_i         : std_logic := '0';
   signal bdir_i       : std_logic := '0';
   signal data_i       : std_logic_vector(7 downto 0) := (others => '0');

   -- Outputs
   signal data_r_o     : std_logic_vector(7 downto 0);
   signal ch_a_o       : unsigned(11 downto 0);
   signal ch_b_o       : unsigned(11 downto 0);
   signal ch_c_o       : unsigned(11 downto 0);
   signal mix_audio_o  : unsigned(13 downto 0);
   signal pcm14s_o     : unsigned(13 downto 0);

   -- Phase label for waveform annotation (useful in VCD viewer)
   signal phase_r      : integer := 0;

   constant clk_i_period : time := 46.560852 ns;  -- ~21.477 MHz

   -- PSG clock-enable divider counter
   signal clk_psg_r : unsigned(2 downto 0) := "000";

   -- -------------------------------------------------------------------------
   -- Register-write procedure.
   --   Latch address  (BDIR=1, BC=1)
   --   Write value    (BDIR=1, BC=0)
   -- Timing window of 300 ns captures at least one PSG enable strobe (279 ns).
   -- -------------------------------------------------------------------------
   procedure wr_reg (
      signal   bc_o   : out std_logic;
      signal   bdir_o : out std_logic;
      signal   din    : out std_logic_vector(7 downto 0);
      constant addr   : in  std_logic_vector(7 downto 0);
      constant val    : in  std_logic_vector(7 downto 0)
   ) is
   begin
      -- Latch register address: BDIR=1, BC=1
      wait for 150 ns;
      bc_o   <= '1';
      bdir_o <= '1';
      din    <= addr;
      wait for 300 ns;
      bc_o   <= '0';
      bdir_o <= '0';
      wait for 150 ns;
      -- Write register value: BDIR=1, BC=0
      wait for 150 ns;
      bc_o   <= '0';
      bdir_o <= '1';
      din    <= val;
      wait for 300 ns;
      bc_o   <= '0';
      bdir_o <= '0';
      wait for 150 ns;
   end procedure;

   -- -------------------------------------------------------------------------
   -- Register-read procedure (latch address then assert BDIR=0, BC=1).
   -- Result appears on data_r_o.
   -- -------------------------------------------------------------------------
   procedure rd_reg (
      signal   bc_o   : out std_logic;
      signal   bdir_o : out std_logic;
      signal   din    : out std_logic_vector(7 downto 0);
      constant addr   : in  std_logic_vector(7 downto 0)
   ) is
   begin
      -- Latch register address
      wait for 150 ns;
      bc_o   <= '1';
      bdir_o <= '1';
      din    <= addr;
      wait for 300 ns;
      bc_o   <= '0';
      bdir_o <= '0';
      wait for 150 ns;
      -- Read (BDIR=0, BC=1)
      wait for 150 ns;
      bc_o   <= '1';
      bdir_o <= '0';
      wait for 300 ns;
      bc_o   <= '0';
      bdir_o <= '0';
      wait for 150 ns;
   end procedure;

begin

   uut: ym2149_audio
   port map
   ( clk_i        => clk_i
   , en_clk_psg_i => en_clk_psg_i
   , sel_n_i      => sel_n_i
   , reset_n_i    => reset_n_i
   , bc_i         => bc_i
   , bdir_i       => bdir_i
   , data_i       => data_i
   , data_r_o     => data_r_o
   , ch_a_o       => ch_a_o
   , ch_b_o       => ch_b_o
   , ch_c_o       => ch_c_o
   , mix_audio_o  => mix_audio_o
   , pcm14s_o     => pcm14s_o
   );

   -- Master clock: ~21.477 MHz
   clk_i_process : process
   begin
      clk_i <= '1';
      wait for clk_i_period / 2;
      clk_i <= '0';
      wait for clk_i_period / 2;
   end process;

   -- PSG clock enable: single-cycle strobe every 6 master clocks (~3.579 MHz)
   en_clk_psg_i_process : process (clk_i)
   begin
      if rising_edge(clk_i) then
         if clk_psg_r = 5 then
            clk_psg_r <= "000";
         else
            clk_psg_r <= clk_psg_r + 1;
         end if;
         if clk_psg_r = 2 then
            en_clk_psg_i <= '1';
         else
            en_clk_psg_i <= '0';
         end if;
      end if;
   end process;

   -- =========================================================================
   -- Stimulus: drives all YM-2149 features across 10 ms
   -- =========================================================================
   stim_proc : process
   begin

      -- -----------------------------------------------------------------------
      -- RESET
      -- -----------------------------------------------------------------------
      phase_r   <= 0;
      sel_n_i   <= '0';
      reset_n_i <= '0';
      bc_i      <= '0';
      bdir_i    <= '0';
      data_i    <= x"00";

      -- Hold reset for several PSG clock periods
      wait for clk_i_period * 6 * 12;
      reset_n_i <= '1';
      wait for clk_i_period * 4 * 12;

      -- -----------------------------------------------------------------------
      -- PHASE 1 (0..~1.3 ms): Three-channel tone, no noise, constant amplitude
      --
      -- R7 = 0x38: tone A/B/C enabled (bits 0-2 = 0, active-low)
      --            noise A/B/C disabled (bits 3-5 = 1)
      --
      -- Frequencies (f = 1 / (2*(period+1)*4.47us)):
      --   Ch A: period=0x060 (96)  -> ~1155 Hz
      --   Ch B: period=0x090 (144) -> ~  770 Hz
      --   Ch C: period=0x0C0 (192) -> ~  578 Hz
      -- -----------------------------------------------------------------------
      phase_r <= 1;

      wr_reg(bc_i, bdir_i, data_i, x"00", x"60"); -- R0:  Ch A period [7:0]
      wr_reg(bc_i, bdir_i, data_i, x"01", x"00"); -- R1:  Ch A period [11:8]
      wr_reg(bc_i, bdir_i, data_i, x"02", x"90"); -- R2:  Ch B period [7:0]
      wr_reg(bc_i, bdir_i, data_i, x"03", x"00"); -- R3:  Ch B period [11:8]
      wr_reg(bc_i, bdir_i, data_i, x"04", x"C0"); -- R4:  Ch C period [7:0]
      wr_reg(bc_i, bdir_i, data_i, x"05", x"00"); -- R5:  Ch C period [11:8]
      wr_reg(bc_i, bdir_i, data_i, x"08", x"0F"); -- R8:  Ch A amplitude = 15
      wr_reg(bc_i, bdir_i, data_i, x"09", x"0A"); -- R9:  Ch B amplitude = 10
      wr_reg(bc_i, bdir_i, data_i, x"0A", x"07"); -- R10: Ch C amplitude =  7
      wr_reg(bc_i, bdir_i, data_i, x"07", x"38"); -- R7:  tones on, noise off

      wait for 1300 us;

      -- -----------------------------------------------------------------------
      -- PHASE 2 (~1.3..~2.6 ms): Noise only (no tone)
      --
      -- R7 = 0x07: tone A/B/C disabled (bits 0-2 = 1)
      --            noise A/B/C enabled  (bits 3-5 = 0)
      -- Noise period R6 = 0x10 (16): audible noise band
      -- -----------------------------------------------------------------------
      phase_r <= 2;

      wr_reg(bc_i, bdir_i, data_i, x"06", x"10"); -- R6:  noise period = 16
      wr_reg(bc_i, bdir_i, data_i, x"08", x"0F"); -- R8:  Ch A amplitude = 15
      wr_reg(bc_i, bdir_i, data_i, x"09", x"0F"); -- R9:  Ch B amplitude = 15
      wr_reg(bc_i, bdir_i, data_i, x"0A", x"0F"); -- R10: Ch C amplitude = 15
      wr_reg(bc_i, bdir_i, data_i, x"07", x"07"); -- R7:  tones off, noise on

      wait for 1300 us;

      -- -----------------------------------------------------------------------
      -- PHASE 3 (~2.6..~3.9 ms): Tone + noise mixed on all channels
      --
      -- R7 = 0x00: both tone and noise enabled for all three channels
      -- Noise period changed to 0x18 (24) for a slightly different timbre
      -- -----------------------------------------------------------------------
      phase_r <= 3;

      wr_reg(bc_i, bdir_i, data_i, x"06", x"18"); -- R6:  noise period = 24
      wr_reg(bc_i, bdir_i, data_i, x"07", x"00"); -- R7:  tones + noise all on

      wait for 1300 us;

      -- -----------------------------------------------------------------------
      -- PHASE 4 (~3.9..~4.8 ms): Envelope shape 0x08  (\\\\ = repeat decay)
      --   CONT=1, ATK=0, ALT=0, HOLD=0
      --
      -- Ch A in envelope mode (R8 bit 4 = 1); B and C constant amplitude.
      -- Envelope period R11=3, R12=0 -> step dt=(3+1)*4.47us=~18us
      -- Full sweep: 32*18us = 576us  (~1.7 sweeps visible)
      -- -----------------------------------------------------------------------
      phase_r <= 4;

      wr_reg(bc_i, bdir_i, data_i, x"07", x"38"); -- R7:  back to tone only
      wr_reg(bc_i, bdir_i, data_i, x"0B", x"03"); -- R11: env period [7:0]  = 3
      wr_reg(bc_i, bdir_i, data_i, x"0C", x"00"); -- R12: env period [15:8] = 0
      wr_reg(bc_i, bdir_i, data_i, x"0D", x"08"); -- R13: shape = 0x08  \\\\
      wr_reg(bc_i, bdir_i, data_i, x"08", x"10"); -- R8:  Ch A envelope mode
      wr_reg(bc_i, bdir_i, data_i, x"09", x"08"); -- R9:  Ch B constant mid
      wr_reg(bc_i, bdir_i, data_i, x"0A", x"08"); -- R10: Ch C constant mid

      wait for 900 us;

      -- -----------------------------------------------------------------------
      -- PHASE 5 (~4.8..~5.7 ms): Envelope shape 0x0C  (////  = repeat attack)
      --   CONT=1, ATK=1, ALT=0, HOLD=0
      -- -----------------------------------------------------------------------
      phase_r <= 5;

      wr_reg(bc_i, bdir_i, data_i, x"0D", x"0C"); -- R13: shape = 0x0C  ////

      wait for 900 us;

      -- -----------------------------------------------------------------------
      -- PHASE 6 (~5.7..~6.6 ms): Envelope shape 0x0E  (/\/\  = triangle, attack-first)
      --   CONT=1, ATK=1, ALT=1, HOLD=0
      -- -----------------------------------------------------------------------
      phase_r <= 6;

      wr_reg(bc_i, bdir_i, data_i, x"0D", x"0E"); -- R13: shape = 0x0E  /\/\

      wait for 900 us;

      -- -----------------------------------------------------------------------
      -- PHASE 7 (~6.6..~7.5 ms): Envelope shape 0x0A  (\/\/  = triangle, decay-first)
      --   CONT=1, ATK=0, ALT=1, HOLD=0
      -- -----------------------------------------------------------------------
      phase_r <= 7;

      wr_reg(bc_i, bdir_i, data_i, x"0D", x"0A"); -- R13: shape = 0x0A  \/\/

      wait for 900 us;

      -- -----------------------------------------------------------------------
      -- PHASE 8 (~7.5..~8.4 ms): All three channels in envelope mode
      --   Shape 0x0E (triangle) with slightly slower period (R11=6)
      --   step dt = 7*4.47us = ~31us,  full sweep = 32*31us = ~1ms
      -- -----------------------------------------------------------------------
      phase_r <= 8;

      wr_reg(bc_i, bdir_i, data_i, x"0B", x"06"); -- R11: env period = 6
      wr_reg(bc_i, bdir_i, data_i, x"0D", x"0E"); -- R13: shape = 0x0E  /\/\
      wr_reg(bc_i, bdir_i, data_i, x"08", x"10"); -- R8:  Ch A envelope mode
      wr_reg(bc_i, bdir_i, data_i, x"09", x"10"); -- R9:  Ch B envelope mode
      wr_reg(bc_i, bdir_i, data_i, x"0A", x"10"); -- R10: Ch C envelope mode

      wait for 900 us;

      -- -----------------------------------------------------------------------
      -- PHASE 9 (~8.4..~8.8 ms): Single-shot envelopes (hold variants)
      --   Shape 0x09 (\___  decay then hold low): CONT=1, ATK=0, ALT=0, HOLD=1
      --   Shape 0x0D (/^^^  attack then hold high): CONT=1, ATK=1, ALT=0, HOLD=1
      -- -----------------------------------------------------------------------
      phase_r <= 9;

      -- Decay then hold low
      wr_reg(bc_i, bdir_i, data_i, x"0B", x"04"); -- R11: env period = 4
      wr_reg(bc_i, bdir_i, data_i, x"0D", x"09"); -- R13: shape = 0x09  \___
      wait for 200 us;

      -- Attack then hold high
      wr_reg(bc_i, bdir_i, data_i, x"0D", x"0D"); -- R13: shape = 0x0D  /^^^
      wait for 200 us;

      -- -----------------------------------------------------------------------
      -- PHASE 10 (~8.8..~9.6 ms): Noise period sweep
      --   All channels noise-only; cycle through several noise periods.
      -- -----------------------------------------------------------------------
      phase_r <= 10;

      wr_reg(bc_i, bdir_i, data_i, x"08", x"0F"); -- R8:  Ch A constant max
      wr_reg(bc_i, bdir_i, data_i, x"09", x"0F"); -- R9:  Ch B constant max
      wr_reg(bc_i, bdir_i, data_i, x"0A", x"0F"); -- R10: Ch C constant max
      wr_reg(bc_i, bdir_i, data_i, x"07", x"07"); -- R7:  noise only

      wr_reg(bc_i, bdir_i, data_i, x"06", x"08"); -- R6:  noise period =  8
      wait for 100 us;
      wr_reg(bc_i, bdir_i, data_i, x"06", x"10"); -- R6:  noise period = 16
      wait for 100 us;
      wr_reg(bc_i, bdir_i, data_i, x"06", x"18"); -- R6:  noise period = 24
      wait for 100 us;
      wr_reg(bc_i, bdir_i, data_i, x"06", x"1F"); -- R6:  noise period = 31 (max)
      wait for 100 us;

      -- -----------------------------------------------------------------------
      -- PHASE 11 (~9.6..10 ms): Register read-back
      --   Read R7 (mixer), R13 (envelope shape), R6 (noise period).
      -- -----------------------------------------------------------------------
      phase_r <= 11;

      rd_reg(bc_i, bdir_i, data_i, x"07"); -- read R7  (expect 0x07)
      rd_reg(bc_i, bdir_i, data_i, x"0D"); -- read R13 (expect 0x0F or last written)
      rd_reg(bc_i, bdir_i, data_i, x"06"); -- read R6  (expect 0x1F)

      wait for 300 us;

      -- Simulation ends at 10 ms via --stop-time=10ms (nvc flag)
      wait;
   end process;

end behavior;
