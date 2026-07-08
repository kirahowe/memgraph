# Agent Memory 2026: memgraph vs. the ICLR MemAgents Workshop and the Field

A research review, July 2026. Compares memgraph (this repo) against the papers
of the ICLR 2026 Workshop on Memory for LLM-Based Agentic Systems
([MemAgents](https://sites.google.com/view/memagent-iclr26/), April 27 2026,
Rio de Janeiro, 110+ submissions) and the surrounding 2025–26 literature.
Answers three questions: where are the gaps in practice, how should this
approach be benchmarked, and how does it stack up.

**Method note.** OpenReview and arXiv were unreachable from this environment
(network policy), so papers were reconstructed through extensive web-search
snippet mining rather than full-PDF reads — deep enough for architecture,
benchmarks, and headline numbers; page-level details may be missing. Eleven
workshop papers were confirmed via camera-ready headers or the workshop
schedule; three more are likely-accepted; the full accepted list (posters
included) is longer than what search surfaces.

-----

## 1. The workshop at a glance

Confirmed accepted papers (camera-ready header or schedule page):

| Paper | Core idea | Refs |
|---|---|---|
| **TierMem** — *From Lossy to Verified: A Provenance-Aware Tiered Memory for Agents* | Write-time summarization loses what future queries need ("write-before-query barrier"). Keep an immutable raw log (Tier-2) under a provenance-linked summary index (Tier-1); a trained sufficiency router escalates only when summaries can't support the answer; verified write-back consolidates grounded evidence. LoCoMo 0.851 vs 0.873 always-raw at −54% tokens / −61% latency | [arXiv:2602.17913](https://arxiv.org/abs/2602.17913) |
| **A-MAC** — *Adaptive Memory Admission Control for LLM Agents* (Workday AI) | Memory *admission* (what gets written) as a structured, interpretable decision: 4 rule-based signals (<65ms) + 1 LLM utility call, linearly weighted, weights fit per domain. F1 0.583 vs LLM-native writers, 31% less latency | [arXiv:2603.04549](https://arxiv.org/abs/2603.04549) |
| **StructMemEval** — *Evaluating Memory Structure in LLM Agents* | Benchmarks memory *organization* (ledgers, trees, state tracking, counts — 51 core / 207 extended problems), not recall. Retrieval-only agents fail regardless of budget; memory agents succeed **only when told how to organize**; LLMs don't invent structure spontaneously | [arXiv:2602.11243](https://arxiv.org/abs/2602.11243) |
| **ALMA** — *Learning to Continually Learn via Meta-learning Agentic Memory Designs* (Clune lab, UBC/Vector) | A GPT-5 meta-agent searches over memory designs *expressed as executable code* (schema + retrieval + update policies); learned designs beat all hand-crafted memory baselines on 4 sequential-decision domains (e.g. 84.1% ALFWorld). Key finding: the search specializes memory per domain — fine-grained fact stores for object-interaction tasks, abstract strategy libraries for reasoning-heavy ones | [arXiv:2602.07755](https://arxiv.org/abs/2602.07755) |
| *Distilling Feedback into Memory-as-a-Tool* (Komorebi AI) | Amortizes self-critique: distills transient rubric-judge critiques into persistent guideline *files* the agent reads/edits via ls/read/write/edit tools. Matches expensive test-time refinement after ~2 feedback rounds, then keeps compounding. Introduces Rubric Feedback Bench | [arXiv:2601.05960](https://arxiv.org/abs/2601.05960) |
| **ERL** — *Experiential Reflective Learning for Self-Improving LLM Agents* | Post-task reflection over single trajectories → natural-language heuristics (strategies *and* failure modes); LLM relevance-scores the pool, injects top-20 at test time. Gaia2: 56.1% (+7.8 over ReAct, +5.2 over ExpeL). Heuristics beat raw few-shot trajectory replay — abstraction transfers, traces don't | [arXiv:2603.24639](https://arxiv.org/abs/2603.24639) |
| **WebCoach** — *Self-Evolving Web Agents with Cross-Session Memory Guidance* | Condenser → episodic store → a *coach* that retrieves by similarity+recency and *decides whether* to inject advice via runtime hooks (gated push, not always-on RAG). WebVoyager 47%→61% on a 38B backbone | [arXiv:2511.12997](https://arxiv.org/abs/2511.12997) |
| **SABER** — *Small Actions, Big Errors* (Amazon) | Decomposes trajectories into mutating vs non-mutating steps; deviations at *mutating* steps cut success odds up to 96%, non-mutating barely matter. Fix: mutation-gated verification + block-based context cleaning that keeps constraint-critical history salient | [arXiv:2512.07850](https://arxiv.org/abs/2512.07850) |
| **SIRA** — *SuperIntelligent Retrieval Agent* (Meta) | Compresses multi-round exploratory search into one corpus-discriminative retrieval action: LLM enriches the corpus with indexing phrases at *write* time + expands queries at read time over plain BM25. SOTA on BEIR, training-free | [arXiv:2605.06647](https://arxiv.org/abs/2605.06647) |
| **ShiftBench** — *Measuring Recovery of Agent Memory Under Distribution Shift* (tiny paper) | Reporting protocol, not a dataset: mark shift points, measure **Recovery@T** (evidence-hit rate T queries after the shift). Method rankings *invert* under shift — hierarchical retrieval overtakes flat despite weaker steady-state accuracy | [OpenReview CCSztIjmOy](https://openreview.net/attachment?id=CCSztIjmOy&name=pdf) |
| **DialSim** — real-time long-term dialogue simulator (KAIST/SNU) | ~1,300 sessions / 352K tokens of TV-show dialogue; spontaneous mid-dialogue questions under a 1–5s answer-time budget; unanswerable questions test abstention; adversarial character-renaming detects parametric-knowledge leakage. No agent above 60% | [OpenReview jysCqv1y8O](https://openreview.net/pdf?id=jysCqv1y8O) |

Likely-accepted (workshop-adjacent evidence, no header confirmation):
**Mem-T** (densified RL rewards for memory ops, [arXiv:2601.23014](https://arxiv.org/abs/2601.23014)),
**The AI Hippocampus** (survey/taxonomy, [arXiv:2601.09113](https://arxiv.org/abs/2601.09113), also TMLR),
**Are We Ready For An Agent-Native Memory System?** (SJTU study of 12 memory
systems from a data-management perspective, [arXiv:2606.24775](https://arxiv.org/abs/2606.24775)).

The workshop's center of gravity: **write-path control** (what to admit, how
to compress without losing what matters), **evaluation beyond recall**
(structure, shift-recovery, real-time constraints), and **experience →
procedural knowledge** (reflection, coaching, feedback distillation). Notably
absent from the confirmed list: anything about markdown files — the research
world skipped straight past the CLAUDE.md era.

-----

## 2. Where memgraph's bets got independently validated

The 2026 literature converged, from several directions, on positions this
design took in advance. Worth stating plainly because it means the core is
sound:

**Bi-temporal KG with invalidate-don't-delete is the winning structure for
update-heavy recall.** The SJTU agent-native study benchmarked 12 systems
across 11 datasets and found no architecture dominates, but *structure-aware
systems lead on LongMemEval* — Zep's temporal KG tops the update-heavy
workload — and *trace-preserving memories win stateful agentic workloads*
(DB-Bench), the closest proxy to coding-agent work. memgraph is both at once:
a temporal KG whose facts are also traces (episodes, provenance,
non-lossy supersession).

**Deterministic write-time control beats LLM judgment on the write path.**
The strongest empirical result in this direction: *Don't Ask the LLM to Track
Freshness* ([arXiv:2606.01435](https://arxiv.org/abs/2606.01435)) shows
replacing LLM-mediated conflict resolution with deterministic version-aware
aggregation gains +10.8pp. SAGE's novelty gate
([arXiv:2605.30711](https://arxiv.org/abs/2605.30711)) makes ADD/NOOP
deterministic and beats Mem0 while cutting cost 3.4×. A-MAC keeps 4 of its 5
admission signals rule-based. TOKI ([arXiv:2606.06240](https://arxiv.org/abs/2606.06240))
formalizes bi-temporal contradiction resolution as a typed operator algebra —
write-time concurrency control, not LLM vibes. memgraph's architecture — the
LLM never runs on the write path, conflicts resolve by epistemic-class policy,
the judge is offline and gated — is exactly this position, taken before these
papers landed.

**Epistemic typing is now shipping in SOTA systems.** Hindsight
([arXiv:2512.12818](https://arxiv.org/abs/2512.12818)), currently claiming #1
on BEAM, separates world facts / experiences / observations / opinions with
evolving confidence — structurally the observation/commitment/preference split
plus per-source confidence. Kumiho ([arXiv:2603.17244](https://arxiv.org/abs/2603.17244))
goes further and proves AGM belief-revision postulates for a versioned
property-graph memory. The "commitments never silently clobbered" rule is a
practical instance of what Kumiho calls core-retainment.

**Agents don't invent structure — impose the schema.** StructMemEval's
headline finding is that memory agents solve organization-requiring tasks
*only when prompted how to organize*. memgraph's controlled 22-predicate
vocabulary, did-you-mean rejection of novel predicates, and the skill that
tells the agent when/how to read and write are precisely the "organization
hint," made permanent and enforced at the API rather than hoped-for in a
prompt.

**Declarative and procedural memory want different stores.** ALMA's
meta-search, given freedom to design memory as code, converged on exactly the
split this project's synthesis doc predicted: fine-grained factual stores for
world-state-heavy domains, abstract strategy libraries for reasoning-heavy
ones. And the Distilling Feedback paper is a useful nuance to the
"markdown pile is dead" thesis: file-based guideline memory edited through
ordinary file tools *works* — for procedural knowledge specifically. The
right reading is not that files lose to graphs, but that procedural memory
tolerates (maybe prefers) prose files, while declarative memory — the part
that needs invalidation, time travel, and conflict semantics — is what
demands the graph. memgraph's design implicitly agrees (skills hold the
judgment, the graph holds the facts), but its procedural side is unbuilt
(§3.4).

**Localized maintenance beats global reorganization.** The SJTU study's
cost-performance finding. memgraph's `ingest-code` reconciliation (invalidate
only what the analysis stopped producing), read-time decay (no batch job), and
per-subject-bounded conflict sweep are all localized-maintenance designs.

**Forgetting via decay + reinforcement, with typed exemptions.** The AI
Hippocampus survey lists learned forgetting and consolidation as the field's
acknowledged blind spots; production systems mostly lack decay (YourMemory and
FSFM are the exceptions). memgraph's disuse half-life computed at read time,
reinforcement on re-assertion toward per-source ceilings, and exemption of
commitments is ahead of most shipping systems — though see §3.5 on what it
still lacks.

**The niche is still open — but no longer empty.** The owned / portable /
inspectable / codebase-scoped corner remains uncontested by the hosted
players (Mem0, Zep, Supermemory all point at user-profile personalization).
The new adjacent competitor is **Letta Code's Context Repositories**
(Feb 2026): a git-backed memory *filesystem*, versioned by commits, with
subagents editing memory in isolated worktrees. It shares the owned-portable
philosophy but is note-shaped, not fact-shaped — no bi-temporal queries, no
epistemic typing, no conflict machinery. It is, however, shipping inside a
coding agent people use, which memgraph is not yet.

-----

## 3. Gaps in practice

Ordered roughly by how hard the literature argues for each.

### 3.1 No raw-evidence tier: extraction faces the write-before-query barrier

TierMem's core argument applies directly to `session-extract`: compression
(extraction) decisions are made *before* knowing what a future query will
hinge on. memgraph extracts durable facts from a transcript, records an
episode summary, and drops the transcript. Anything the extractor didn't deem
durable is unrecoverable, and no answer can be audited past the episode
summary. TierMem's fix is cheap and structurally compatible: keep the raw
transcript as an immutable, page-addressed artifact (content-addressed files
next to the dump would do), give episodes provenance pointers into pages, and
let a "sufficiency" path escalate from graph → episode summary → raw pages
when graph facts can't support an answer. This also upgrades the provenance
story from "which session" to "which utterance."

### 3.2 Retrieval is the thinnest layer

Current retrieval: exact/alias entity lookup, BFS, full-text search, ranked
by effective confidence. The 2026 evidence says two things. First, hybrid
retrieval matters: Mem0's 2026 gains came largely from fusing semantic +
keyword + entity scorers; Datalevin already has SIMD vector search, so the
deferred vector-search TODO is cheap to activate. Second — and more
interesting — SIRA and TierMem both reframe retrieval as *planned evidence
allocation* rather than single-shot lookup: decide what evidence suffices,
escalate when it doesn't. The SJTU study's sharpest line applies: retrieval
quality depends more on *how a system organizes evidence for later
reconstruction* than on ranking the one right memory first. memgraph organizes
evidence well; it doesn't yet have the escalation/sufficiency logic that
exploits the organization.

Two cheaper ideas from the workshop also apply. SIRA's write-time enrichment
(spend LLM compute when indexing so future queries hit in one shot) maps onto
consolidation: the episode-summarization pass could also emit search phrases /
alt-labels for facts and entities, making FTS behave closer to semantic search
without embeddings. And WebCoach's coach pattern is the push-side complement
to the skill's pull-side judgment: rather than relying on the agent to
remember to consult memory, a gated hook (e.g. on file-open or task-start)
could decide *whether* the graph has something worth interrupting with —
standing decisions being the obvious trigger.

### 3.3 Admission control is cruder than the state of the art

`session-extract` gates writes with a confidence cap (0.7) and source typing —
a fixed prior, not a decision. A-MAC scores each candidate on future utility,
factual confidence, novelty, recency, and a content-type prior; SAGE makes
the ADD/NOOP call deterministically from embedding density (is this actually
new?). memgraph has the ingredients (novelty ≈ duplicate detection already
exists as reinforcement; type prior ≈ epistemic class) but doesn't compose
them into an explicit admit/reject score, and admits everything the extractor
produces. A rule-based admission score with one optional LLM utility signal
would drop straight into the ingest path without violating the
no-LLM-on-write-path principle (A-MAC's LLM call is the one exception worth
debating; it can be made offline/batch).

### 3.4 Procedural memory is the field's biggest win — and still a TODO here

The single largest cluster at the workshop (Distilling Feedback, Experiential
Reflective Learning, WebCoach) plus the coding-agent literature (MemCoder's
verification-feedback loop; subtask-level memory keyed to
reproduce/localize/edit/validate stages, +4.7pp on SWE-bench Verified;
"Getting Better at Working With You" compiling user corrections into
enforcement rules) all monetize the same thing: **turning failures and
feedback into reusable procedural knowledge**. memgraph's failure ingester and
ADR ingester are the top two TODO items and remain unbuilt. The literature
suggests the failure ingester is the more valuable of the two, and supplies
design guidance: ERL shows heuristics distilled from *single* trajectories
beat replaying raw trajectories (abstraction transfers; traces don't), and
Memory Transfer Learning ([arXiv:2604.14004](https://arxiv.org/abs/2604.14004))
confirms it across six coding benchmarks — high-level insights transfer,
low-level traces cause negative transfer. That's a phrasing guideline for
what the failure ingester should extract (the lesson, not the diff), and it
matches the existing "phrasing facts well" discipline in the skill. SABER
adds the targeting guidance: it's *mutating* actions where deviations kill
success (up to 96% odds reduction), so failure extraction should
preferentially capture lessons about writes, migrations, and deploys — and
the skill's read policy should treat "about to mutate" as the moment to
consult standing decisions.

### 3.5 No outcome signal reaches the store

Reinforcement currently counts *writes* (re-assertion, re-derivation), never
*usefulness*. The RL thread (Memory-R1 → Mem-T's hindsight credit assignment
→ SWE-MeM's memory-aware GRPO for coding agents) is all about attributing
downstream task success back to the memory operations that enabled it. memgraph
deliberately avoids learned components — defensible for inspectability — but a
non-learned version of the same signal is available: when a fact was retrieved
in a session whose work was accepted (vs reverted), that's evidence about the
fact. The deferred "reinforcement on true retrieval" TODO is half of this;
the other half is valence (did the session succeed?), which the failure
ingester would supply. Without any usage signal, decay is blind: a fact that's
read constantly but never re-asserted fades identically to one nobody needs.

### 3.6 Memory poisoning is an unmodeled threat

MINJA ([arXiv:2503.03704](https://arxiv.org/abs/2503.03704)) achieves 98.2%
injection success into agent memories through ordinary interaction — no
privileged access — and the 2026 follow-ups ("poison once, exploit forever")
show one poisoned record can steer behavior indefinitely. memgraph's
mitigations are real but incidental: session facts cap at 0.7 confidence,
provenance is kept, commitments can't be silently overwritten, and unused
facts decay. But `session-extract` will faithfully ingest whatever a
transcript says, and a poisoned "preference" would sit at 0.7 steering the
agent until it decays. Missing: any trust model on sources, any anomaly check
on writes (a SAGE-style novelty/outlier gate doubles as a defense), and any
red-team case in the benchmark.

### 3.7 Concurrency and multi-writer semantics

Single-writer is a stated v0 scope choice, but the field moved: Letta MemFS
runs concurrent subagent memory edits in git worktrees with merge resolution;
TOKI frames contradiction resolution *as* write-time concurrency control.
Coding agents increasingly are multi-agent (parallel subagents in worktrees is
now a normal Claude Code pattern), and two subagents asserting facts about the
same entity will hit last-write-wins at the LMDB level with no story. Even a
lease/lock or append-log-and-reconcile design would close the obvious hole.

### 3.8 Scale is unproven

BEAM runs to 10M tokens; Mem-α generalizes to 474K-token streams; the shoply
fixture is three sessions and three code passes over a toy repo. Nothing in
memgraph's design obviously breaks at 500k LOC / thousands of sessions — the
candidate-set reads and batched BFS were built for exactly this — but there is
no measurement, and the per-invocation pod-start cost that motivates the MCP
front-end will bite long before graph size does.

### 3.9 Judge evaluation inherits the judge-reliability problem

`bb bench llm` measures judge verdict accuracy against labeled pairs — good.
But the 2026 judge literature (flip rates averaging 14%, up to 56%,
[arXiv:2606.13685](https://arxiv.org/abs/2606.13685)) says single-run judge
accuracy is noisy enough to mislead; verdicts sit behind a 0.8 confidence
gate, but nothing measures the *stability* of those verdicts across runs.
Cheap fix: run each labeled pair k times, report flip rate alongside accuracy,
and let stability inform the gate.

-----

## 4. How to benchmark this

### 4.1 What exists is the right shape

The shoply benchmark is — by the standards of the 2026 eval-critique
literature — methodologically ahead of most published memory benchmarks:
deterministic scoring (no LLM judge in the gate), capability-mapped questions,
a longitudinal fixture with real store mechanics, and an explicit
separation of mechanics (CI-gated) from LLM quality (informational). The
*Anatomy of Agentic Memory* critique ([arXiv:2602.19320](https://arxiv.org/abs/2602.19320))
faults the field for judge-sensitivity, metric misalignment, and ignoring
maintenance cost — shoply avoids the first two by construction. What it lacks
is coverage and scale.

### 4.2 The "no codebase-memory benchmark" premise is now stale

The handoff doc's claim that no LongMemEval equivalent exists for codebase
memory was true when written; it isn't anymore:

- **SWE-ContextBench** ([arXiv:2602.08316](https://arxiv.org/abs/2602.08316)):
  1,100 base + 376 related tasks mined from real GitHub issue/PR dependency
  links across 51 repos; measures accuracy *and efficiency* gains when prior
  cases are available, with sessions deliberately separated. This is the
  external benchmark closest to memgraph's thesis, and the efficiency-delta
  protocol (does memory make the second related task cheaper/faster?) is the
  right headline metric.
- **RealMem** ([arXiv:2601.06966](https://arxiv.org/abs/2601.06966)):
  project-oriented, cross-session, evolving goals — 2,000+ dialogues.
- **STALE** ([arXiv:2605.06527](https://arxiv.org/abs/2605.06527)): staleness
  detection — do agents notice their memories are no longer valid, especially
  under *implicit* invalidation (no explicit negation)? Best model: 55.2%.
- **ShiftBench** (this workshop): recovery speed after distribution shift.
- **MemoryAgentBench** ([arXiv:2507.05257](https://arxiv.org/abs/2507.05257)):
  conflict resolution / selective forgetting as a first-class competency
  (best system: 54% — the field's weak axis, and memgraph's strong one).

Strategic implication: memgraph should *measure itself where the field is
weakest and it is strongest* — conflict resolution, staleness, temporal
validity — using external protocols where adaptable, because a strong showing
there is differentiated in a way LoCoMo-style recall numbers no longer are
(vendors report 91–94% and the benchmark is considered near-saturated).

### 4.3 Concrete plan, in order of value

1. **End-task A/B on this repo (the only metric that ultimately matters).**
   SWE-ContextBench's protocol, miniaturized: pairs of related tasks run by a
   real coding agent with and without the memgraph skill, on a repo with
   seeded history. Measure: tokens to completion, wall-clock, whether standing
   decisions get re-litigated (count `decided-against` violations proposed),
   and correctness of "why/since-when" answers. Even n=20 task pairs would say
   more than any retrieval metric. The bench harness driving the core API
   directly (not the CLI) makes this wiring feasible.
2. **A staleness/implicit-conflict tier in shoply (STALE-style).** Today's
   fixture invalidates explicitly (migration recorded, decision relitigated).
   Add cases where the code contradicts a session-derived fact *without
   anyone saying so* — the dependency quietly removed, the preference the
   code stopped following — and score whether reads surface current truth,
   whether the sweep finds the conflict, and whether decay buries the stale
   fact before it misleads. This is the field's 55% axis; memgraph's
   machinery is built for it and should prove it.
3. **Abstention questions.** LongMemEval's most transferable idea. Questions
   whose correct answer is "the graph doesn't know" — score refusal vs
   confabulation at the retrieval layer (does `facts` return empty vs
   near-miss garbage) and at the skill layer (does the agent say so).
4. **A poisoning red-team case.** One fixture session containing a planted
   instruction-shaped "preference" and a plausible-but-false fact; score
   whether admission/confidence/decay/conflict machinery contains the blast
   radius (fact stays ≤0.7, decays unreinforced, never overrides a
   commitment, surfaces in a sweep). MINJA-style, miniaturized.
5. **A shift-recovery case (ShiftBench's axis).** The fixture already has a
   rename and a migration; make recovery *speed* a measured quantity using
   ShiftBench's Recovery@T framing: mark the shift point (the rename/
   migration), then measure evidence-hit rate at T queries after it — how
   many reads/writes until queries against old names resolve, conflicts
   settle, and stale facts fade. ShiftBench found method rankings *invert*
   under shift; memgraph's alias machinery and mechanical reconciliation
   should recover in O(1) passes, which would be a differentiating number.
6. **Scale tier.** Generate a synthetic history (hundreds of sessions,
   thousands of entities — LoCoMo-style generation but for repo events) and
   report read latency, sweep cost, and answer accuracy at 10×/100× fixture
   size. Also report **maintenance cost** (tokens and wall-clock per
   consolidate pass) — the metric the Anatomy critique says everyone omits.
7. **Judge stability.** k-run flip rate on the labeled conflict pairs,
   reported next to accuracy (§3.9).
8. **Two metric-hygiene ideas from DialSim**, cheap to adopt: report
   **latency per read** alongside accuracy (a memory that answers in 30s is
   a different product than one that answers in 200ms — and pod cold-start
   makes this memgraph's honest weak spot today), and add a
   **contamination control** — questions about entities whose names are
   deliberately swapped in the fixture, so a correct answer *must* come from
   the graph rather than the model's parametric knowledge. DialSim found
   models leak parametric knowledge exactly this way.

What *not* to do: chase LoCoMo/LongMemEval leaderboards. memgraph's domain is
codebase state, not conversational recall; the saturated benchmarks would
force the design toward user-profile memory, which is the competitors' turf
and explicitly out of scope.

-----

## 5. Verdict

**The core architecture is validated — more strongly than when it was
designed.** Bi-temporal KG, invalidate-don't-delete, epistemic typing with
commitment protection, deterministic write path, imposed schema, localized
maintenance, decay-with-reinforcement: each now has independent 2026 evidence
behind it (SJTU study, TOKI, Kumiho, Hindsight, StructMemEval, the
freshness-tracking result). memgraph is a working instance of the position the
research frontier argued itself into this year. On conflict handling and
temporal validity specifically — the axis where measured field performance
sits at ~54–55% — its machinery is ahead of every production system surveyed
except Zep, and it does epistemic typing Zep doesn't.

**The gaps are real but tractable, and mostly additive.** In priority order:
raw-evidence tier under episodes (TierMem's argument is directly on point);
failure/procedural ingestion (the field's biggest measured wins); an
admission score on the ingest path; retrieval escalation + vector search;
poisoning defenses; multi-writer semantics; scale numbers. None require
rearchitecting; the functional core / store protocol seams accommodate all of
them.

**The eval story needs to become the differentiator.** The "no codebase
benchmark" gap is closing without us — SWE-ContextBench and RealMem landed
this year. The move is to grow shoply toward the axes the field measures
worst (staleness, conflicts, abstention, shift-recovery, poisoning,
maintenance cost), adopt the external end-task A/B protocol, and publish the
numbers. A deterministic, longitudinal, adversarial codebase-memory benchmark
would be a contribution to the field in its own right — arguably a MemAgents
2027 submission.

**Competitive position.** The hosted players moved further away (user
profiles, context lakes); the one convergent neighbor is Letta's git-backed
memory filesystem, which shares the ownership philosophy but not the
structure. The defensible claim, sharpened by this year's literature: *the
only system that is simultaneously owned, codebase-scoped, bi-temporal,
epistemically typed, and deterministic on the write path.* The literature now
supplies the receipts for why each of those properties matters.

-----

## Appendix: key sources

Workshop: [MemAgents site](https://sites.google.com/view/memagent-iclr26/) ·
[OpenReview group](https://openreview.net/group?id=ICLR.cc/2026/Workshop/MemAgent) ·
[MCML recap](https://mcml.ai/news/2026-05-06-mem-agents-workshop-at-iclr-2026/)

Workshop papers: [TierMem](https://arxiv.org/abs/2602.17913) ·
[A-MAC](https://arxiv.org/abs/2603.04549) ·
[StructMemEval](https://arxiv.org/abs/2602.11243) ·
[ALMA](https://arxiv.org/abs/2602.07755) ·
[Distilling Feedback](https://arxiv.org/abs/2601.05960) ·
[Experiential Reflective Learning](https://arxiv.org/abs/2603.24639) ·
[WebCoach](https://arxiv.org/abs/2511.12997) ·
[SABER](https://arxiv.org/abs/2512.07850) ·
[SIRA](https://arxiv.org/abs/2605.06647) ·
[ShiftBench](https://openreview.net/attachment?id=CCSztIjmOy&name=pdf) ·
[DialSim](https://openreview.net/pdf?id=jysCqv1y8O) ·
[Mem-T](https://arxiv.org/abs/2601.23014) ·
[AI Hippocampus](https://arxiv.org/abs/2601.09113) ·
[Agent-Native Memory study](https://arxiv.org/abs/2606.24775)

Field: [Zep/Graphiti](https://arxiv.org/abs/2501.13956) ·
[TOKI bitemporal algebra](https://arxiv.org/abs/2606.06240) ·
[Kumiho AGM semantics](https://arxiv.org/abs/2603.17244) ·
[Hindsight](https://arxiv.org/abs/2512.12818) ·
[Memory-R1](https://arxiv.org/abs/2508.19828) ·
[SWE-MeM](https://arxiv.org/abs/2606.28434) ·
[SAGE gate](https://arxiv.org/abs/2605.30711) ·
[Don't Ask the LLM to Track Freshness](https://arxiv.org/abs/2606.01435) ·
[MINJA](https://arxiv.org/abs/2503.03704) ·
[Memory Transfer Learning](https://arxiv.org/abs/2604.14004) ·
[Letta Context Repositories](https://www.letta.com/blog/context-repositories/)

Benchmarks: [LongMemEval](https://arxiv.org/abs/2410.10813) ·
[MemoryAgentBench](https://arxiv.org/abs/2507.05257) ·
[BEAM](https://arxiv.org/abs/2510.27246) ·
[STALE](https://arxiv.org/abs/2605.06527) ·
[SWE-ContextBench](https://arxiv.org/abs/2602.08316) ·
[RealMem](https://arxiv.org/abs/2601.06966) ·
[LoCoMo-Plus](https://arxiv.org/abs/2602.10715) ·
[Anatomy of Agentic Memory](https://arxiv.org/abs/2602.19320) ·
[The Coin Flip Judge](https://arxiv.org/abs/2606.13685)
