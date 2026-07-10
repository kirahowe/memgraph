# Roadmap

The build plan coming out of the July 2026 research round, as an ordered list
of concrete issues. Sources: the field comparison
(`docs/memory-systems-comparison.md`), the MemAgents/ICLR 2026 review
(`docs/memagent-2026-review.md`), the ambient-loop design note
(`docs/consuming-auto-memory.md`), and the standing `TODO.md`. This document
says *what* to build and *in what order*; the how lives in those docs and gets
worked out per issue.

## The ordering logic

The research settled three things that dictate the sequence:

1. **The core architecture is validated; the gaps are functional, not
   structural** (review §2, §5). Nothing here requires rearchitecting — every
   item is additive behind existing seams.
2. **The two biggest functional gaps — ambient capture and ambient injection —
   can both be closed by consuming the harness's auto-memory instead of
   building either** (comparison §4, consuming-auto-memory throughout). That
   is the cheapest high-leverage work available and it makes the system
   valuable to a user who never learns a memgraph verb. It goes first.
3. **The eval story is the differentiator, and the bar is *net task
   improvement*, not retrieval metrics** (review §4). The AGENTS.md result
   killed "context helps" as an assumption; the claim has to be demonstrated.
   Proving comes immediately after the loop closes, before further
   capability investment — the benchmark tells us where the remaining phases
   actually pay.

After that, capability work in the order the literature argues for it
(review §3, §5): procedural memory and evidence depth (the field's biggest
measured wins), then retrieval (the 20-point axis), then hardening and scale.

Standing constraints that shape every item: no LLM on the write path, no
ambient injection built by us (compiled views into the harness's slot only),
invalidate-don't-delete, and no LoCoMo/LongMemEval chasing (review §4.3).

-----

## Phase 1 — Close the ambient loop

Goal: zero-effort capture in, zero-effort injection out, with memgraph as the
consolidator in the middle. Build order and design are already specified in
`docs/consuming-auto-memory.md` §8.

### 1. `ingest-notes` — Claude Code auto-memory as the fourth ingestion tier ✅ *(2026-07-10)*
Adapter over the existing `session-extract` machinery: delta-detect changed
note files, extract with a notes-tuned prompt, ingest under new source-type
`agent-note` with an inference-grade confidence ceiling. The note tier can
never mint commitments; no reconciliation (compaction-absence ≠ falsity —
disuse decay already models it). Every note-derived fact goes through the
full conflict machinery — that's the value-add over the pile.
*(consuming-auto-memory §2; comparison §4 "behind the field" #1)*

### 2. `compile-context` — the graph writes the injected view back
Deterministic, budgeted compilation of "what's currently true" into a
marker-delimited managed section of `MEMORY.md`: top valid facts, standing
commitments, open conflicts, recent supersessions. The harness injects it for
free every session. Ships in the same change as the echo-loop guard: the
managed section is excluded from `ingest-notes`, making compile → ingest →
compile a fixed point. *(consuming-auto-memory §3; closes comparison §4 gap #2)*

### 3. SessionEnd hook — make the loop ambient
Document the hook in the skill and add a `memgraph hooks install`
convenience: every session ends with `ingest-notes && compile-context`;
`consolidate` runs at lower frequency. *(consuming-auto-memory §4)*

### 4. `load` command — complete the portability loop
Restore a store from the `dump` JSONL. Already on TODO; promoted into this
phase because multi-machine users of the ambient loop converge through the
committed dump, not through per-machine note files — which requires dump to
be two-way. *(TODO; consuming-auto-memory §7 "path plumbing")*

-----

## Phase 2 — Prove it: the benchmark as the differentiator

Goal: measure where the field is weakest and memgraph is strongest
(staleness, conflicts, abstention, provenance), then run the headline
experiment. Plan and rationale: review §4.3; items below keep its numbering
where relevant. The fixture tiers are cheap, extend the existing `bb bench`
harness, and build toward the A/B.

### 5. Notes-loop bench fixture
A fixture auto-memory directory in `bench/`: notes that restate facts,
contradict a standing commitment, and get compacted away — regression-gating
the compaction/decay semantics and the echo guard from Phase 1.
*(consuming-auto-memory §8.5)*

### 6. Staleness / implicit-conflict tier
Cases where the code contradicts a session-derived fact without anyone saying
so; score whether reads surface current truth, the sweep finds the conflict,
and decay buries the stale fact. The field measures ~55% on this axis; our
machinery is built for it. *(review §4.3.2, STALE)*

### 7. Abstention tier
Questions whose correct answer is "the graph doesn't know" — score refusal vs
confabulation at both the retrieval layer and the skill layer.
*(review §4.3.3)*

### 8. Poisoning red-team case
One fixture session with a planted instruction-shaped "preference" and a
plausible-but-false fact; score whether admission/confidence/decay/conflict
machinery contains the blast radius. MINJA-style, miniaturized. Findings feed
issue 23. *(review §4.3.4, §3.6)*

### 9. Shift-recovery case
The fixture already contains a rename and a migration; measure Recovery@T —
how many reads/writes until old names resolve and stale facts fade.
*(review §4.3.5, ShiftBench)*

### 10. Judge stability
Run each labeled conflict pair k times; report flip rate alongside accuracy
and let stability inform the 0.8 resolution gate. Cheap. *(review §3.9, §4.3.8)*

### 11. Metric hygiene
Report latency per read alongside accuracy (pod cold-start is the honest weak
spot — this is also the trigger data for issue 27), and add a contamination
control: fixture entities with swapped names so correct answers must come
from the graph, not parametric knowledge. *(review §4.3.9, DialSim)*

### 12. The headline four-arm end-task A/B
Same tasks, same agent, four arms: no memory · static context file ·
auto-memory (the actual incumbent) · memgraph. Measure task success, tokens,
wall-clock, and re-litigation counts. Beating "no memory" is table stakes;
beating auto-memory is the product claim. Even n=20 task pairs says more than
any retrieval metric. Depends on Phase 1 (the memgraph arm *is* the ambient
loop). *(review §4.3.1; protocol from the AGENTS.md oral + SWE-ContextBench)*

### 13. Retrieval-vs-structure ablation
memgraph full vs raw chunks + BM25/embeddings vs memgraph with degraded
retrieval. Publish where structure pays (history, time-travel, conflicts) and
where it doesn't (plain recall) — the negative half is what makes the
positive half credible. Also the baseline data for Phase 4. *(review §4.3.7)*

-----

## Phase 3 — Evidence depth and procedural memory

Goal: the two ingestion gaps the literature argues for hardest — keep raw
evidence under the extractions, and start turning failures and decisions into
procedural knowledge (the largest cluster of measured wins at the workshop).

### 14. Raw-evidence tier under episodes
Keep raw transcripts as immutable content-addressed artifacts with provenance
pointers from episodes, upgrading provenance from "which session" to "which
utterance" and breaking the write-before-query barrier. Prerequisite for
sufficiency escalation (issue 20) and safe admission control (issue 22).
Notes-as-primary, transcripts-as-fallback posture per the double-coverage
caveat. *(review §3.1 — its top-priority gap; consuming-auto-memory §7)*

### 15. Failure ingester
When agent work is rejected or reverted, extract *why* — the top standing
TODO item. The literature supplies the design guardrails already captured in
the review: extract the lesson, not the diff; preferentially capture lessons
about mutating actions. Supplies the valence half of issue 24.
*(TODO #1; review §3.4)*

### 16. Decision-record (ADR) ingester
Highest-authority source; parse ADR files into `supersedes` /
`decided-against` / `has-status` commitments. spec-kit's
constitution/spec output is now the largest authoring pipeline in the field —
this is the "eat their output" wedge. *(TODO #2; comparison §6.7)*

### 17. Predicate promotion command
`x/*` → `core/*`: register the stable twin, rewrite facts, deprecate the
staging term. Promoted from the TODO backlog because note-noise from
`ingest-notes` makes the staging namespace work harder. *(TODO #3;
consuming-auto-memory §7 "note noise")*

-----

## Phase 4 — Retrieval: the 20-point axis

Goal: the evidence says retrieval buys more points than write-path
sophistication; ours is the thinnest layer. Ordered by increasing ambition
(review §3.2). Issue 13's ablation numbers say how far down this list to go.

### 18. Hybrid retrieval
Fuse FTS + entity/graph + vector scoring (Datalevin already has SIMD vector
search — TODO's deferred item, now with evidence behind it). *(review §3.2;
TODO)*

### 19. Write-time enrichment
Consolidation emits search phrases / alt-labels so FTS behaves closer to
semantic search without embeddings. SIRA-style, training-free. *(review §3.2)*

### 20. Sufficiency escalation and evidence-guided walks
Escalate graph facts → episode summaries → raw pages when the graph can't
support an answer (needs issue 14), and replace fixed-depth BFS with an
evidence-guided walk over the same graph. *(review §3.2, TierMem/MRAgent)*

### 21. Gated push — the coach pattern
The push-side complement to the skill's pull-side judgment: a hook (task
start, about-to-mutate) that decides *whether* the graph has something worth
interrupting with — standing decisions being the obvious trigger.
*(review §3.2 WebCoach, §3.4 SABER)*

-----

## Phase 5 — Hardening, trust, and scale

Goal: close the remaining literature-flagged gaps once the loop is proven and
retrieval carries its weight.

### 22. Admission scoring on the ingest path
Compose the ingredients we already have (novelty/duplicate detection,
epistemic-class prior, source typing) into an explicit rule-based
admit/reject score with at most one optional LLM utility signal. Requires the
raw tier (gate the graph, keep the log). *(review §3.3, A-MAC/SAGE)*

### 23. Source trust model and poisoning defenses
Trust levels on sources and a novelty/outlier check on writes, driven by what
the red-team case (issue 8) shows leaks through the existing incidental
mitigations. *(review §3.6)*

### 24. Outcome signal into the store
Reinforcement on true retrieval (the deferred TODO half) plus session valence
from the failure ingester (issue 15): facts retrieved in accepted work are
evidence; facts retrieved in reverted work are too. Keeps decay from being
blind to usefulness. *(review §3.5)*

### 25. Multi-writer semantics
Coding agents are increasingly multi-agent; two subagents asserting about the
same entity currently hit last-write-wins at the LMDB level. Even a
lease/lock or append-log-and-reconcile design closes the obvious hole.
*(review §3.7)*

### 26. Scale tier for the benchmark
Synthetic history at 10×/100× fixture size (AMA-Bench-style generation,
rule-based QA); report read latency, sweep cost, accuracy, and maintenance
cost per consolidate pass — the metric the field omits. *(review §3.8, §4.3.6)*

### 27. MCP front-end
Thin second front-end over `memgraph.core`. Stays trigger-gated as designed
(handoff §3.4): build when issue 11's latency numbers show cold-start pain or
per-turn call counts grow — the ambient loop's hook cadence may be what
finally trips it. *(TODO; comparison §4)*

### 28. Codex notes adapter
The second harness proves the `--harness` abstraction and its evidence files
exercise richer episode provenance; the beginning of the cross-harness
consolidator story nothing else in the field has. *(consuming-auto-memory
§5, §8.4)*

-----

## Explicitly deferred (unchanged triggers)

- **ACL tier** — fields carried, unenforced; activate when multi-user. *(TODO)*
- **Multi-language code ingesters** — tree-sitter generalization; when demand
  is real. *(TODO)*
- **Multi-system benchmark harness** — only after the benchmark proves out
  here; a neutral codebase-memory benchmark is possibly the highest-leverage
  artifact in the field, but sequencing stands. *(TODO; comparison §5)*
- **Standalone vector search** — subsumed into issue 18.
- **Learned components anywhere** (RL memory managers, learned eviction) —
  against the inspectability principle; non-learned equivalents preferred
  (issues 22, 24). *(review §3.5)*

## What we are deliberately not doing

- Competing with harnesses on ambient capture (distribution problem, not
  technology problem — consume it instead).
- Building our own always-on injection; compiled views into the harness's
  existing slot only.
- Chasing conversational-recall leaderboards (saturated, wrong domain,
  competitors' turf).
- LLM adjudication on the write path, ever (the freshness result: +10.8pp for
  deterministic versioning).
