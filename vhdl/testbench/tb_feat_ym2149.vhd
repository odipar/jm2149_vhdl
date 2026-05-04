--
-- Feature Testbench
-- YM-2149 / AY-3-8910 Complex Sound Generator
-- Exercises: tones, noise, mixer, and envelope generators.
--
-- Register map summary:
--   R0  Channel A tone period [7:0]
--   R1  Channel A tone period [11:8]
--   R2  Channel B tone period [7:0]
--   R3  Channel B tone period [11:8]
--   R4  Channel C tone period [7:0]
--   R5  Channel C tone period [11:8]
--   R6  Noise shift period [4:0]
--   R7  Mixer: bit7-6=I/O, bit5=noiseC, bit4=noiseB, bit3=noiseA,
--              bit2=toneC, bit1=toneB, bit0=toneA  (all active low)
--   R8  Channel A amplitude: bit4=env-mode, bit3:0=fixed level
--   R9  Channel B amplitude: bit4=env-mode, bit3:0=fixed level
--   R10 Channel C amplitude: bit4=env-mode, bit3:0=fixed level
--   R11 Envelope period [7:0]
--   R12 Envelope period [15:8]
--   R13 Envelope shape: bit3=continue, bit2=attack, bit1=alternate, bit0=hold
--
-- Simulation phases (30 ms total):
--   Phase 1 ( 0- 5 ms): Three-channel tones, constant amplitude
--   Phase 2 ( 5-10 ms): Noise only on all channels
--   Phase 3 (10-15 ms): Tone + noise mix on all channels
--   Phase 4 (15-20 ms): Envelope sawtooth-up (/|/|) on channel A, tone on B/C
--   Phase 5 (20-25 ms): Envelope triangle (/\/\) on channels A+B, tone on C
--   Phase 6 (25-30 ms): All features active (tone+noise+envelope on all channels)
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity tb_feat_ym2149 is
end tb_feat_ym2149;

architecture behavior of tb_feat_ym2149 is

   -- Unit Under Test (UUT)
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
   signal clk_i         : std_logic := '0';
   signal en_clk_psg_i  : std_logic := '0';
   signal sel_n_i       : std_logic := '0';
   signal reset_n_i     : std_logic := '0';
   signal bc_i          : std_logic := '0';
   signal bdir_i        : std_logic := '0';
   signal data_i        : std_logic_vector(7 downto 0) := (others => '0');

   -- Outputs
   signal data_r_o      : std_logic_vector(7 downto 0);
   signal ch_a_o        : unsigned(11 downto 0);
   signal ch_b_o        : unsigned(11 downto 0);
   signal ch_c_o        : unsigned(11 downto 0);
   signal mix_audio_o   : unsigned(13 downto 0);
   signal pcm14s_o      : unsigned(13 downto 0);

   -- Clock period: ~21.47 MHz (same as tb_ym2149.vhd)
   constant clk_i_period : time := 46.560852 ns;

   signal clk_psg_r : unsigned(2 downto 0) := "000";

   -- ---------------------------------------------------------------------------
   -- PSG register-write procedure.
   -- Follows the same BC1/BDIR timing used in tb_ym2149.vhd:
   --   step 1: latch address  (BDIR=1, BC1=1)
   --   step 2: write data     (BDIR=1, BC1=0)
   -- ---------------------------------------------------------------------------
   procedure psg_write (
      constant reg_addr : in  std_logic_vector(7 downto 0);
      constant reg_data : in  std_logic_vector(7 downto 0);
      signal   s_bc     : out std_logic;
      signal   s_bdir   : out std_logic;
      signal   s_data   : out std_logic_vector(7 downto 0)
   ) is
   begin
      -- Latch register address
      wait for 150 ns;
      s_bc   <= '1';
      s_bdir <= '1';
      s_data <= reg_addr;
      wait for 300 ns;
      s_bc   <= '0';
      s_bdir <= '0';
      wait for 150 ns;

      -- Write register data
      wait for 150 ns;
      s_bc   <= '0';
      s_bdir <= '1';
      s_data <= reg_data;
      wait for 300 ns;
      s_bc   <= '0';
      s_bdir <= '0';
      wait for 150 ns;
   end procedure;

begin

   -- Instantiate the Unit Under Test (UUT)
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


   -- Clock process
   clk_i_process : process
   begin
      clk_i <= '1';
      wait for clk_i_period / 2;
      clk_i <= '0';
      wait for clk_i_period / 2;
   end process;

   -- PSG clock-enable: divide system clock by 6 (same divider as tb_ym2149.vhd)
   en_clk_psg_i_process : process ( clk_i )
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


   -- -------------------------------------------------------------------------
   -- Stimulus process
   -- -------------------------------------------------------------------------
   stim_proc : process
   begin
      -- Initial state
      sel_n_i   <= '0';
      reset_n_i <= '0';
      bc_i      <= '0';
      bdir_i    <= '0';
      data_i    <= x"00";

      -- Hold reset for a few PSG clock periods, then release
      wait for clk_i_period * 6 * 12;
      reset_n_i <= '1';
      wait for clk_i_period * 2 * 12;


      -- -----------------------------------------------------------------------
      -- Phase 1 (0-5 ms): Three-channel tones, constant amplitude
      --
      -- Channel A ~440 Hz  (period = 0x00FE = 254)
      -- Channel B ~659 Hz  (period = 0x00AA = 170)
      -- Channel C ~523 Hz  (period = 0x00D5 = 213)
      -- Amplitudes: A=max(0x0F), B=mid(0x0A), C=low(0x07)
      -- Mixer R7=0x38: noise disabled, all tones enabled
      -- -----------------------------------------------------------------------

      -- Channel A tone period
      psg_write(x"00", x"FE", bc_i, bdir_i, data_i); -- R0 fine
      psg_write(x"01", x"00", bc_i, bdir_i, data_i); -- R1 coarse

      -- Channel B tone period
      psg_write(x"02", x"AA", bc_i, bdir_i, data_i); -- R2 fine
      psg_write(x"03", x"00", bc_i, bdir_i, data_i); -- R3 coarse

      -- Channel C tone period
      psg_write(x"04", x"D5", bc_i, bdir_i, data_i); -- R4 fine
      psg_write(x"05", x"00", bc_i, bdir_i, data_i); -- R5 coarse

      -- Channel amplitudes (fixed level, no envelope)
      psg_write(x"08", x"0F", bc_i, bdir_i, data_i); -- R8  channel A: max
      psg_write(x"09", x"0A", bc_i, bdir_i, data_i); -- R9  channel B: mid
      psg_write(x"0A", x"07", bc_i, bdir_i, data_i); -- R10 channel C: low

      -- Mixer: tones A+B+C on, noise off (0x38 = 0b0011_1000)
      psg_write(x"07", x"38", bc_i, bdir_i, data_i); -- R7

      wait for 5 ms;


      -- -----------------------------------------------------------------------
      -- Phase 2 (5-10 ms): Noise only on all channels
      --
      -- R6 noise period = 0x0A (10)
      -- R7 = 0x07 (0b0000_0111): tones off, noise on for A+B+C
      -- -----------------------------------------------------------------------

      -- Noise period
      psg_write(x"06", x"0A", bc_i, bdir_i, data_i); -- R6

      -- Mixer: noise A+B+C on, tones off (0x07 = 0b0000_0111)
      psg_write(x"07", x"07", bc_i, bdir_i, data_i); -- R7

      wait for 5 ms;


      -- -----------------------------------------------------------------------
      -- Phase 3 (10-15 ms): Tone + noise mix on all channels
      --
      -- R7 = 0x00: both tone and noise active on all three channels
      -- -----------------------------------------------------------------------

      -- Mixer: all tones and all noise enabled (0x00 = everything active)
      psg_write(x"07", x"00", bc_i, bdir_i, data_i); -- R7

      wait for 5 ms;


      -- -----------------------------------------------------------------------
      -- Phase 4 (15-20 ms): Envelope sawtooth-up (/|/|) on channel A
      --
      -- Envelope period R11=0x20 (32), R12=0x00
      --   -> shape step rate = 223.6 kHz / 33 ~ 6.8 kHz
      --   -> full 32-step sweep ~ 4.7 ms (one complete cycle per phase)
      -- R13 shape = 0x0C (continue=1, attack=1, alternate=0, hold=0) = /|/|/|
      -- R8  channel A: envelope mode (bit4=1)
      -- R7  mixer: tones only
      -- -----------------------------------------------------------------------

      -- Envelope period
      psg_write(x"0B", x"20", bc_i, bdir_i, data_i); -- R11 fine
      psg_write(x"0C", x"00", bc_i, bdir_i, data_i); -- R12 coarse

      -- Envelope shape: sawtooth-up, repeating
      psg_write(x"0D", x"0C", bc_i, bdir_i, data_i); -- R13

      -- Channel A: envelope mode
      psg_write(x"08", x"10", bc_i, bdir_i, data_i); -- R8

      -- Mixer: tones only (0x38)
      psg_write(x"07", x"38", bc_i, bdir_i, data_i); -- R7

      wait for 5 ms;


      -- -----------------------------------------------------------------------
      -- Phase 5 (20-25 ms): Envelope triangle (/\/\) on channels A and B
      --
      -- R13 shape = 0x0E (continue=1, attack=1, alternate=1, hold=0) = /\/\/\
      -- R9  channel B: envelope mode (bit4=1)
      -- Channel C keeps fixed amplitude
      -- -----------------------------------------------------------------------

      -- Envelope shape: triangle, repeating
      psg_write(x"0D", x"0E", bc_i, bdir_i, data_i); -- R13

      -- Channel B: envelope mode
      psg_write(x"09", x"10", bc_i, bdir_i, data_i); -- R9

      wait for 5 ms;


      -- -----------------------------------------------------------------------
      -- Phase 6 (25-30 ms): All features active
      --
      -- Envelope sawtooth-down (\|\|) on all channels, tone+noise mix
      -- R13 shape = 0x08 (continue=1, attack=0, alternate=0, hold=0) = \|\|\|
      -- R10 channel C: envelope mode
      -- R7 = 0x00: tone+noise on all channels
      -- -----------------------------------------------------------------------

      -- Channel C: envelope mode
      psg_write(x"0A", x"10", bc_i, bdir_i, data_i); -- R10

      -- Envelope shape: sawtooth-down, repeating
      psg_write(x"0D", x"08", bc_i, bdir_i, data_i); -- R13

      -- Mixer: all tones and all noise active (0x00)
      psg_write(x"07", x"00", bc_i, bdir_i, data_i); -- R7

      wait for 5 ms;


      wait;
   end process;

end;
