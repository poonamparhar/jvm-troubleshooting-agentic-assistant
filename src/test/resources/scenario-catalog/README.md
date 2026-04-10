# Diagnostic Scenario Catalog

This folder defines the long-term test corpus for `jtroubleshoot`.

The goal is not to model every theoretical JVM failure in existence. The goal
is to cover the incident families that this product can realistically diagnose
from the artifact types it supports, plus the ambiguity, truncation, and
ingest-failure cases that can mislead agents.

## Current Base

The project already has a useful starting point:

- reference-incident bundles under `src/test/resources/reference-incidents/`
- sample artifacts under `samples/`
- programmatic JFR generation in `JfrTestRecordingFactory`
- deterministic generated incident bundles in `MemoryPressureFixtureFactory`
- reusable generated-scenario support through `ScenarioLab` implementations

The catalog’s job is to say which scenarios exist, which acquisition strategy
fits each one, and whether the project already has runnable coverage for it.

The catalog now exists, and the current catalog no longer has any `planned`
entries. Every scenario is backed by either a runnable reference bundle
(`covered_reference_bundle`) or deterministic fixture or unit coverage
(`covered_fixture_or_unit`).
The catalog also now breaks out standalone heap-histogram `analyze` coverage
and the individual JFR `compare` regressions so the inventory matches the
implemented assessor and comparator surface, including explicit JFR
monitor-wait, CPU-load, virtual-thread-submit-failed, and container-CPU-budget
coverage.

## Status Snapshot

- Newly covered in this sweep: `gc-cms-fragmentation-promotion-failure`, `blocked-thread-pileup-on-downstream-io`, `forkjoin-starvation`, `virtual-thread-pinning`, `cpu-busy-spin-thread`, `hs-err-vm-internal-crash`, `hs-err-compiler-thread-crash`, `crash-under-gc-pressure`, `truncated-hs-err`, `restart-loop-without-explicit-oom`, `host-oom-vs-cgroup-oom-ambiguity`, `cgroup-v1-memory-snapshot`, `cgroup-v2-memory-snapshot`, `interleaved-or-rotated-gc-log`, `truncated-gc-log`, `partial-thread-dump`, `corrupted-jfr-recording`, `partial-nmt-or-pmap-output`, and `mixed-supported-and-unsupported-directory-ingest`.
- Newly cataloged as explicit covered scenarios in this pass: `heap-histogram-top-heavy-consumer`, `heap-histogram-cache-retention`, `heap-histogram-collection-retention`, `heap-histogram-payload-retention`, `compare-jfr-lock-contention-regression`, `compare-jfr-gc-pause-regression`, `compare-jfr-execution-hot-path-shift`, `compare-jfr-allocation-regression`, `compare-jfr-old-object-growth`, and `compare-jfr-old-object-depth-regression`.
- Newly cataloged or implemented in the latest JFR and container pass: `jfr-thread-park-events`, `jfr-runtime-hot-path`, `jfr-code-cache-pressure`, `jfr-cpu-load-saturation`, `jfr-monitor-wait-backlog`, `jfr-threshold-blind-spot-low-latency-stalls`, `virtual-thread-submit-failed-resource-pressure`, and `container-cpu-quota-or-processor-mis-sizing`.
- Reference-bundle-backed additions in this pass: `hs-err-vm-internal-crash` and `hs-err-compiler-thread-crash`.
- Fixture or unit-backed additions in this pass: the remaining crash, threading, container, robustness, JFR runtime, and container CPU-budget scenarios listed above.
- Remaining scenarios to implement in the current catalog: none.
- Remaining future hardening work is higher-fidelity corpus improvement, not missing scenario coverage. The main candidates are upgrading some fixture or unit-backed cases into end-to-end simulator or reference bundles when that effort is worth the maintenance cost.

## Corpus Architecture

The strongest test corpus should use four lanes:

1. Reference incident bundles
   These are end-to-end bundles that the orchestrator runs through `analyze`,
   `compare`, `sequence`, or `correlate`.

2. Parser and ingest fixtures
   These focus on format coverage, malformed inputs, truncation, mixed files,
   and artifact classification behavior.

3. Compare and sequence sets
   These cover regression, improvement, and trend scenarios across snapshots.

4. Control and uncertainty bundles
   These are healthy or low-signal bundles used to verify that the tool avoids
   over-diagnosing when the data does not justify certainty.

## Acquisition Strategies

The machine-readable catalog uses four acquisition modes.

- `simulator_bundle`
  Use a small Java app or harness that produces internally consistent JVM
  artifacts from one controlled run. This is the best choice when timing,
  causality, or cross-artifact consistency matters. It is especially valuable
  for GC, JFR, thread dumps, heap histograms, and NMT bundles.

- `captured_fixture`
  Use sanitized artifacts captured offline from controlled reproductions or
  trusted incident samples. This is the best choice for legacy JDK 8 logs,
  `hs_err` crashes, `pmap`, kernel or Kubernetes OOM signals, and other
  platform-specific outputs that are brittle or unsafe to reproduce in CI.

- `synthetic_fixture`
  Use handcrafted or mutated files when the goal is format and robustness
  coverage rather than runtime realism. This is the best choice for truncation,
  corruption, partial sections, rotated logs, mixed-directory ingest, and other
  parser-edge conditions.

- `mixed_bundle`
  Use a bundle that combines simulator-generated JVM artifacts with captured or
  synthetic platform artifacts. This is the best choice for cross-artifact
  incidents such as JVM pressure plus container OOM or native memory plus
  process-map evidence.

## Recommended Simulator Families

Instead of building one simulator per scenario, build a small number of focused
labs that can emit many scenarios by configuration:

- `MemoryPressureLab`
  Heap leak, retained cache growth, metaspace leak, direct-buffer pressure,
  native-thread growth, and NMT-friendly snapshots.

- `ThreadingLab`
  Java deadlocks, monitor contention, blocked-thread pileups, pool saturation,
  busy-spin CPU loops, and virtual-thread pinning.

- `RuntimeHotspotLab`
  JFR execution hotspots, allocation hotspots, exception storms, socket or file
  I/O stalls, and safepoint-heavy windows.

- `CorrelationBundleLab`
  Bundles captured from one scenario run so JFR, GC logs, thread dumps, heap
  histograms, and NMT snapshots line up in time.

- `ExternalPlatformFixturePack`
  Captured `hs_err`, `pmap`, kernel OOM, Kubernetes restart, and cgroup-memory
  fixtures that should not be reproduced during routine test execution.

## Recommended Build Order

1. Fill the missing P0 scenarios that are directly user-facing today:
   heap pressure, metaspace and native pressure, thread saturation, crash
   coverage, and container OOM correlation.

2. Expand the missing P0 cross-artifact bundles:
   direct-buffer/native pressure and CPU hotspot versus low-memory ambiguity.

3. Fill the P1 JFR and comparison or sequence gaps:
   hotspot shifts, allocation-path bundles, retained-object bundles, and
   multi-snapshot regression trends.

4. Finish the P2 robustness and ambiguity cases:
   corrupted files, partial captures, rotated logs, low-signal controls, and
   ingest-only edge cases.

## Machine-Readable Inventory

`diagnostic-scenario-catalog.json` is the source of truth for the scenario
inventory. It records:

- scenario IDs
- artifact types
- workflow modes
- priority
- recommended acquisition strategy
- current coverage status
- references to existing sample, source, or bundle files when coverage exists
