- simulated with https://www.nickg.me.uk/nvc/manual.html
- see also: https://www.nickg.me.uk/nvc/download.html
- note: 30 ms captured, covering tones / noise / mixer / envelope features
- nvc -a tb_feat_ym2149.vhd ym2149_audio.vhd
- nvc -e tb_feat_ym2149
- nvc -r tb_feat_ym2149 --format=vcd --wave=tb_feat_ym2149.vcd --stop-time=30ms

Simulation phases:
  Phase 1 ( 0- 5 ms): three-channel tones, constant amplitude
  Phase 2 ( 5-10 ms): noise only on all channels
  Phase 3 (10-15 ms): tone + noise mix on all channels
  Phase 4 (15-20 ms): envelope sawtooth-up (/|/|) on channel A, tones on B/C
  Phase 5 (20-25 ms): envelope triangle (/\/\) on channels A+B, tone on C
  Phase 6 (25-30 ms): all features active (tone+noise+envelope on all channels)
