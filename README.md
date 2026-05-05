# jm2149_vhdl

Cycle-accurate Java models of the YM2149 / AY-3-8910 Programmable Sound Generator,
derived from an open-source VHDL implementation.

---

## Design Methodology

The project follows a four-stage pipeline:

```
VHDL spec
   │
   ▼
Testbench → VCD simulation traces
   │
   ▼
Cycle-accurate Java model  (direct translation)
   │
   ▼
Refined Java variants       (idiomatic → optimized → refactored → indexed)
```

### 1. VHDL Specification

The starting point is the open-source
[ym2149_audio](https://github.com/dnotq/ym2149_audio) VHDL core
(included as a git submodule at `vhdl/ym2149_audio`).  It models the
complete YM2149 / AY-3-8910 chip behaviour: tone generators, noise
generator, envelope generator, mixer, DAC ROM, and bus interface.

### 2. Testbench → VCD Simulation Traces

A VHDL testbench (`vhdl/testbench/tb_feat_ym2149.vhd`) exercises every
major chip feature over 30 ms of simulated time:

| Phase | Time      | Feature                                              |
|-------|-----------|------------------------------------------------------|
| 1     | 0 – 5 ms  | Three-channel tones, constant amplitude              |
| 2     | 5 – 10 ms | Noise only on all channels                           |
| 3     | 10 – 15 ms| Tone + noise mix                                     |
| 4     | 15 – 20 ms| Envelope sawtooth-up (`/|/|`) on A, tones on B/C    |
| 5     | 20 – 25 ms| Envelope triangle (`/\/\`) on A+B, tone on C        |
| 6     | 25 – 30 ms| All features active (tone + noise + envelope)        |

The testbench is simulated with [NVC](https://www.nickg.me.uk/nvc/) to
produce Value Change Dump (VCD) files that record every signal at every
clock edge:

```sh
nvc -a tb_feat_ym2149.vhd ym2149_audio.vhd
nvc -e tb_feat_ym2149
nvc -r tb_feat_ym2149 --format=vcd --wave=tb_feat_ym2149.vcd --stop-time=30ms
```

Pre-generated VCD traces (compressed as `.zip`) are stored under
`vhdl/simulations/ym2149_audio/` and are used directly by the Java
conformance test suite — no VHDL simulator is required to run the tests.

### 3. Cycle-Accurate Java Model

`Ym2149Audio` (`src/main/java/org/jm2149/vhdl/Ym2149Audio.java`) is a
direct, signal-by-signal translation of the VHDL source into Java.
Every VHDL signal maps to a field; signals ending in `_r` are registered
(flip-flop) state; `_x` (next-state) and `_s` (combinatorial) values are
local variables recomputed inside `risingEdge()`.

Correctness is verified by the conformance test suite, which replays the
VCD traces and asserts that the Java model produces bit-identical outputs
at every rising `clk_i` edge.

### 4. Refined Java Variants

Successive refinements improve code quality and runtime efficiency while
remaining functionally identical (all variants pass the same conformance
tests).

| Package / Class           | Key changes from predecessor                          |
|---------------------------|-------------------------------------------------------|
| `Ym2149Audio`             | Direct VHDL translation                               |
| `idiomatic.Ym2149AudioIdiomatic` | Extracts `ToneGenerator`, `NoiseGenerator`, `EnvelopeGenerator`; input-via-parameters API |
| `optimized.Ym2149AudioOptimized` | Adds `RegisterFile` (decode-once register cache); early-return when PSG clock is inactive |
| `refactored.Ym2149AudioRefactored` | Merges guard conditions; extracts `resetState()`, `updateRegisterFile()`, `tickToneGenerators()`, `tickEnvelope()` helpers |
| `indexed.Ym2149AudioIndexed` | Exposes 5-bit per-channel DAC indices instead of 12-bit levels; omits audio mixing and signed PCM (concerns outside the chip boundary) |

---

## Repository Layout

```
jm2149_vhdl/
├── vhdl/
│   ├── ym2149_audio/          # git submodule – original VHDL core
│   ├── testbench/
│   │   └── tb_feat_ym2149.vhd # feature testbench (all chip functions)
│   └── simulations/
│       └── ym2149_audio/
│           ├── commit_ce6654e/ # basic conformance VCD (10 ms)
│           └── commit_84bb268/ # feature conformance VCD (30 ms)
└── src/
    ├── main/java/org/jm2149/vhdl/
    │   ├── Ym2149Audio.java              # direct translation
    │   ├── idiomatic/                    # structured, component-based
    │   ├── optimized/                    # register-cache optimisation
    │   ├── refactored/                   # reduced cyclomatic complexity
    │   └── indexed/                      # DAC-index output variant
    └── test/java/org/jm2149/vhdl/
        ├── VcdParser.java                # VCD file parser
        ├── VcdConformanceRunner.java     # shared VCD-replay harness
        ├── Ym2149AudioConformanceTest.java
        ├── FeatYm2149ConformanceTest.java
        ├── idiomatic/
        ├── optimized/
        ├── refactored/
        └── indexed/
```

---

## Register Map

| Register | Bits   | Function                                                     |
|----------|--------|--------------------------------------------------------------|
| R0       | 7:0    | Channel A tone period (low byte)                             |
| R1       | 3:0    | Channel A tone period (high nibble)                          |
| R2       | 7:0    | Channel B tone period (low byte)                             |
| R3       | 3:0    | Channel B tone period (high nibble)                          |
| R4       | 7:0    | Channel C tone period (low byte)                             |
| R5       | 3:0    | Channel C tone period (high nibble)                          |
| R6       | 4:0    | Noise shift period                                           |
| R7       | 5:0    | Mixer: bit5=noiseC, bit4=noiseB, bit3=noiseA, bit2=toneC, bit1=toneB, bit0=toneA (active-low) |
| R8       | 4:0    | Channel A amplitude: bit4=envelope mode, bit3:0=fixed level  |
| R9       | 4:0    | Channel B amplitude                                          |
| R10      | 4:0    | Channel C amplitude                                          |
| R11      | 7:0    | Envelope period (low byte)                                   |
| R12      | 7:0    | Envelope period (high byte)                                  |
| R13      | 3:0    | Envelope shape: bit3=continue, bit2=attack, bit1=alternate, bit0=hold |

---

## Building and Testing

Requirements: **Java 17**, **Maven 3.x**.

```sh
# compile
mvn compile

# run all tests (conformance + unit)
mvn test
```

The conformance tests load pre-generated VCD zip files from
`vhdl/simulations/` and require no external tools.

---

## Quick-Start (High-Level API)

All model variants expose the same convenience API:

```java
import org.jm2149.vhdl.refactored.Ym2149AudioRefactored;

Ym2149AudioRefactored psg = new Ym2149AudioRefactored();
psg.applyReset();
psg.setTonePeriod(0, 500);                              // channel A period
psg.setVolume(0, 12);                                   // channel A volume
psg.setMixer(true, false, false, false, false, false);  // tone A on, rest off
int[] samples = psg.run(44100);                         // 44 100 clock cycles
```

For cycle-accurate control, drive inputs and read outputs directly:

```java
Ym2149AudioRefactored psg = new Ym2149AudioRefactored();
for (int i = 0; i < N; i++) {
    psg.risingEdge(enClkPsg, selN, resetN, bc, bdir, data);
    int sample = psg.getMixAudioO();
}
```

---

## Third-Party Attribution

### ym2149_audio VHDL Core

The VHDL specification that this project is based on is the
[ym2149_audio](https://github.com/dnotq/ym2149_audio) core, authored by
**Matthew Hagerty** ([@dnotq](https://github.com/dnotq)).

It is licensed under the **BSD 3-Clause License**:

> Copyright (c) 2020, Matthew Hagerty  
> All rights reserved.
>
> Redistribution and use in source and binary forms, with or without
> modification, are permitted provided that the following conditions are met:
>
> 1. Redistributions of source code must retain the above copyright notice,
>    this list of conditions and the following disclaimer.
> 2. Redistributions in binary form must reproduce the above copyright notice,
>    this list of conditions and the following disclaimer in the documentation
>    and/or other materials provided with the distribution.
> 3. Neither the name of the copyright holder nor the names of its contributors
>    may be used to endorse or promote products derived from this software
>    without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
> AND WITHOUT ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
> THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
> ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
> LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
> CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
> SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
> INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
> CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
> ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
> POSSIBILITY OF SUCH DAMAGE.

**License implications for this project:**  
The BSD 3-Clause License is a permissive open-source license.  It allows the
VHDL source to be used as a reference and basis for derived works (such as the
Java models in this repository) provided that:

- The original copyright notice and the three conditions above are preserved in
  any redistribution of source or binary form.
- The name of the original author (Matthew Hagerty) is not used to endorse or
  promote derived products without explicit written permission.

No copyleft or "share-alike" obligation applies: derived works (including the
Java translations in this repository) may be distributed under different
license terms.

---

## Authorship and Implementation Notes

The overall **design methodology** — the four-stage pipeline (VHDL spec →
testbench/VCD → direct Java translation → iterative Java refinements) — was
conceived and directed by the project author **[@odipar](https://github.com/odipar)**.

The actual **Java implementation** (translating the VHDL to Java, writing the
conformance test infrastructure, and producing each successive refinement) was
carried out with the assistance of an **LLM / GitHub Copilot** coding agent
under the author's guidance.

The full development history is available in the
[commit log](../../commits/main), which serves as a detailed record of each
incremental step.
