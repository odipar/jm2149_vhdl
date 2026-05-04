# Code Review Report — `jm2149_vhdl`

## Overview

`jm2149_vhdl` provides a cycle-accurate Java simulation model of the YM2149 / AY-3-8910 Programmable Sound Generator (PSG) chip.  The project is structured around three progressively refined Java implementations of the same VHDL core, backed by NVC-generated VCD conformance traces and a set of unit / benchmark tests.

### Repository layout

```
pom.xml                                  Maven build (Java 17, JUnit 5.10)
vhdl/
  ym2149_audio/                          VHDL core (git submodule)
  testbench/tb_feat_ym2149.vhd           Feature testbench
  simulations/ym2149_audio/              Zipped VCD conformance traces
src/main/java/org/jm2149/vhdl/
  Ym2149Audio.java                       Implementation 1 – monolithic literal translation
  idiomatic/
    ToneGenerator.java                   Tone counter + output flip-flop (one per channel)
    NoiseGenerator.java                  Noise counter, LFSR, output flip-flop
    EnvelopeGenerator.java               Envelope period counter, shape counter, FSM
    Ym2149AudioIdiomatic.java            Implementation 2 – idiomatic, factored
  optimized/
    RegisterFile.java                    Register cache with pre-decoded signal fields
    Ym2149AudioOptimized.java            Implementation 3 – performance-optimised
src/test/java/org/jm2149/vhdl/
  VcdParser.java                         Streaming NVC VCD parser
  VcdConformanceRunner.java              Shared VCD replay + assertion engine
  Ym2149AudioConformanceTest.java        Conformance tests for Ym2149Audio
  idiomatic/
    Ym2149AudioIdiomaticTest.java        Unit tests for the high-level API
    IdiomaticConformanceTest.java        Conformance tests for Ym2149AudioIdiomatic
  optimized/
    OptimizedConformanceTest.java        Conformance tests for Ym2149AudioOptimized
    ClockRateTest.java                   2 MHz / 10% CPU performance gate
    ComparisonBenchmarkTest.java         Head-to-head throughput comparison
```

---

## Pros

### 1. Cycle-accurate VHDL fidelity
Every Java field corresponds to a named VHDL signal.  The naming convention — `_r` for registered flip-flop state, `_x` for next-state (combinatorial), `_s` for combinatorial-only signals — is applied uniformly throughout all three implementations.  This makes the Java code a reliable, auditable translation of the hardware, not just a functional approximation.

### 2. Correct handling of VHDL concurrent-update semantics
VHDL processes update all registers simultaneously at the clock edge.  The Java models faithfully reproduce this by saving pre-edge values before any assignment:

- `oldEnCntR` is captured before `enCntR` is written so all three tone generators and the noise generator receive the *registered* (pre-edge) count-enable.
- `oldNoiseFfR` is saved before the noise flip-flop is updated so the LFSR rising-edge detect uses the correct pre-edge value.
- `oldEnvFfR` and `oldHoldFfR` are saved before the envelope registers are updated for the same reason.

Getting these ordering details right is non-trivial and easy to miss; the code handles them correctly.

### 3. Three-tier architecture with clear motivation
The repository presents a didactic progression:

| Class | Purpose |
|---|---|
| `Ym2149Audio` | Literal, monolithic translation — highest transparency to the VHDL |
| `Ym2149AudioIdiomatic` | Factored-out generators — improved readability and testability |
| `Ym2149AudioOptimized` | Register caching + early-exit fast path — production-grade performance |

Each tier is documented with explicit rationale, making the codebase educational as well as functional.

### 4. Thorough Javadoc
Every public and most private members carry Javadoc comments that include:
- The corresponding VHDL signal name and bit-width.
- Pre/post-conditions (e.g. "must be called before the next tick").
- Parameter descriptions with hardware semantics.
- Usage examples in `<pre>` blocks.

This level of documentation is rare in hardware-modelling projects and substantially reduces the ramp-up time for new contributors.

### 5. VCD conformance testing
The conformance tests replay actual NVC-generated VCD traces and compare the Java model's outputs at every rising `clk_i` edge.  This provides:
- Bit-exact validation against the reference VHDL simulation.
- Coverage of the full register map and all envelope shapes across two distinct testbench scenarios.
- A shared `VcdConformanceRunner` that is reused identically across all three implementations, ensuring equal coverage depth.

### 6. Shared conformance infrastructure (`VcdConformanceRunner`)
`VcdConformanceRunner.run()` and `VcdConformanceRunner.assertOutputs()` are public static utilities, making it trivial to add conformance coverage for a new implementation without duplicating the VCD replay logic.

### 7. High-level convenience API
`Ym2149AudioIdiomatic` and `Ym2149AudioOptimized` each expose a `set*()`/`writeRegister()`/`applyReset()`/`run()` API that hides the raw bus protocol.  This lowers the barrier for experimentation and makes the classes directly usable in audio applications without deep knowledge of the bus timing.

### 8. Register-caching optimisation (`RegisterFile`)
`RegisterFile` pre-decodes all signals derived from the raw register bytes (12-bit tone periods, flatline flags, mixer enable bits, envelope shape bits, …) at write time.  `risingEdge()` then reads pre-decoded values in O(1) instead of repeating bit-masking and shift arithmetic on every clock cycle.  The design is clean: a single `updateCache(reg)` switch statement ensures only the fields affected by a given register are recalculated.

### 9. Early-exit fast path in `Ym2149AudioOptimized`
When `enClkPsgI = false` and `resetNI = true`, only the three input flip-flops change.  The optimised implementation detects this condition at the top of `risingEdge()` and returns immediately, skipping all generator, register-file, and DAC logic.  This is a significant win when the PSG clock is gated (common in systems that divide the master clock).

### 10. Performance gate test (`ClockRateTest`)
`ClockRateTest` verifies that `Ym2149AudioOptimized` can simulate the YM2149's 2 MHz target clock rate while consuming less than 10% of a single CPU thread.  Three scenarios are tested: idle, 20 kHz single-channel register writes, and 20 kHz three-channel register writes.  This acts as a regression guard against accidental performance regressions.

### 11. Input validation with clear error messages
`writeRegister`, `setTonePeriod`, and `setVolume` all validate their arguments and throw `IllegalArgumentException` with descriptive messages.  `run()` validates that `cycles > 0`.  The `IllegalArgumentException` type (unchecked) is appropriate for programmer-error guards.

### 12. Flatline detection is explicit and consistent
The flatline thresholds (tone period < 6, noise period < 5) are encoded identically in all three implementations and are exposed via static `isFlatline()` methods on `ToneGenerator` and `NoiseGenerator`, making them easy to discover and test in isolation.

### 13. Immutable DAC ROM
`DACROM` is `private static final int[]` in all three chip classes.  Java arrays are not inherently immutable, but the field is private, and the array is only ever indexed for reading in normal operation.  The logarithmic amplitude values match the VHDL specification exactly.

### 14. Clean VCD parser
`VcdParser` is a self-contained streaming parser with a `VcdListener` callback interface.  It handles both plain `.vcd` and zipped `.vcd.zip` inputs transparently.  The default methods on `VcdListener` (`onVariable`, `onEnd`) allow listeners to implement only the callbacks they need.

---

## Cons

### 1. `DACROM` constant is duplicated across three classes
The identical 32-entry array:
```java
private static final int[] DACROM = {
    0x000, 0x017, 0x01B, ...
};
```
appears in `Ym2149Audio`, `Ym2149AudioIdiomatic`, and `Ym2149AudioOptimized`.  A single shared constant in a package-level utility class (or in `EnvelopeGenerator`, which is the primary consumer) would eliminate the duplication and ensure that a future correction to the table is applied everywhere.

### 2. `signedLevel()` and `signExt12to14()` are duplicated
These two private static helper methods are identical in `Ym2149AudioIdiomatic` and `Ym2149AudioOptimized`.  They are not present in `Ym2149Audio` (where the logic is inlined).  Extracting them to a shared utility class would eliminate the duplication and make the PCM logic easier to test in isolation.

### 3. `Ym2149Audio` uses mutable public fields for I/O
```java
public boolean enClkPsgI = false;
public int     chAO = 0;
// ...
```
The original monolithic class exposes all six inputs and six outputs as public mutable fields.  This style:
- Allows callers to forget to set an input (it silently retains its previous value).
- Makes the data-flow less explicit than the parameter-based API of `Ym2149AudioIdiomatic`.
- Breaks encapsulation (outputs can be written by callers).

The idiomatic variant's approach — inputs as method parameters, outputs via typed getters — is strictly better.

### 4. `Ym2149Audio` lacks the high-level convenience API
`Ym2149AudioIdiomatic` and `Ym2149AudioOptimized` both provide `writeRegister()`, `set*()`, `applyReset()`, and `run()`.  `Ym2149Audio` has none of these, making it harder to use for quick experiments despite being the reference implementation.

### 5. `EnvelopeGenerator.dacLevel()` receives the DAC ROM as a parameter
```java
public int dacLevel(int[] dacRom, boolean envAttack) { ... }
```
The DAC ROM is a fixed, chip-level constant that the envelope generator should not need to receive from its caller.  Storing a reference to the shared ROM in the generator (or duplicating the 32-entry constant there) would eliminate the leaky abstraction and simplify call sites.

### 6. `chALevelR` / `chBLevelR` / `chCLevelR` reset value inconsistency
In `Ym2149Audio`, the fields are initialised to `0` but the reset handler sets them to `0x800`:
```java
private int chALevelR = 0;   // field initialisation
// ...
if (!resetNI) {
    chALevelR = 0x800;       // hardware reset
```
In `Ym2149AudioIdiomatic` and `Ym2149AudioOptimized`, the same pattern applies.  The VHDL hardware resets them to `0x800` (mid-scale), but the Java objects start in a different state until an explicit hardware reset is performed.  This is not a bug (the caller is expected to assert `reset_n_i` before use) but it is undocumented and can lead to subtle incorrect behaviour if the convenience API is bypassed.

### 7. VCD symbol mapping is hardcoded in `VcdConformanceRunner`
```java
final String S_CLK  = "!";   // clk_i
final String S_EN   = "\"";  // en_clk_psg_i
```
The one-character VCD identifiers assigned by NVC are tool-implementation-specific and may change if the VCD is regenerated with a different tool version or simulation setup.  If that happens, the conformance tests will silently pass (all signals remain at their initial values) rather than failing loudly.  A header-parsing step that derives the symbol map from the VCD `$var` declarations would be more robust.

### 8. No `@Tag` or category separation between conformance, unit, and benchmark tests
All tests are run by a plain `mvn test`.  Long-running benchmark tests (`ClockRateTest`, `ComparisonBenchmarkTest`) execute alongside fast unit tests.  Using JUnit 5 `@Tag` annotations would allow selective execution: e.g. `mvn test -Dgroups=unit` for a fast feedback loop and `mvn test -Dgroups=benchmark` for throughput checks.

### 9. `VcdConformanceRunner` uses `LinkedHashMap` for per-edge signal grouping
As noted in `ComparisonBenchmarkTest`'s own Javadoc, the runner uses `LinkedHashMap<String, Integer>` for grouping signal changes within each timestamp.  For single-character VCD symbols, a `int[128]` array indexed by the character code would avoid hashing and boxing overhead entirely, which matters for tests that replay large VCD files.

### 10. No README or top-level project documentation
There is no `README.md` explaining the project's purpose, build instructions, how to obtain or regenerate the VCD traces, or the differences between the three implementations.  New contributors must read the source Javadoc and discover the architecture by exploring the code.

### 11. Register indices 14–15 are silently ignored in `RegisterFile`
```java
default:
    break;   // registers 14–15 have no decoded signals
```
`RegisterFile.write()` accepts any index 0–15 (enforced by the caller's range check) but silently discards writes to registers 14 and 15.  A comment at the call site or in `updateCache()` explaining why these are ignored would prevent confusion, since those registers are I/O port direction / data registers in the real chip that are simply not modelled here.

### 12. No thread-safety documentation
The models maintain mutable state (flip-flop registers) as instance fields and are not thread-safe.  This is typical for hardware simulation models, but the absence of any note about thread safety (e.g. in the class-level Javadoc) could lead to incorrect concurrent use.

### 13. `run()` samples `sumAudioR` directly instead of calling `getMixAudioO()`
In both `Ym2149AudioIdiomatic` and `Ym2149AudioOptimized`:
```java
out[i] = sumAudioR;   // bypasses getMixAudioO()
```
This is correct because `getMixAudioO()` returns `sumAudioR`, but it bypasses the public getter API, creating a subtle coupling to the private field name.  Using `getMixAudioO()` (which the JIT will inline anyway) would be more consistent.

### 14. `applyReset()` duration is undocumented
```java
for (int i = 0; i < 8; i++) { risingEdge(...); }
```
Eight clock cycles are used for reset assertion, but no Javadoc or comment explains *why* eight cycles are sufficient (or why more would not be required).  Linking this to the number of clock stages in the reset path would make the choice traceable.

---

## Summary

The codebase is well-engineered and has a high level of technical quality.  The cycle-accurate VHDL semantics are correctly modelled, the documentation is thorough, and the test suite is unusually strong for a hardware simulation project.  The main areas for improvement are: consolidating duplicated constants and helpers, improving the API consistency of the monolithic class, making VCD symbol mapping more robust, and adding a project-level README and test categorisation.
