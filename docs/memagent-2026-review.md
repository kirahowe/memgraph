# Agent Memory 2026: memgraph vs. the ICLR MemAgents Workshop and the Field

A research review, July 2026. Compares memgraph (this repo) against the
accepted papers of the ICLR 2026 Workshop on Memory for LLM-Based Agentic
Systems ([MemAgents](https://sites.google.com/view/memagent-iclr26/),
April 27 2026, Rio de Janeiro — 110+ submissions, **70 accepted papers**,
15 orals) and the surrounding 2025–26 literature. Answers three questions:
where are the gaps in practice, how should this approach be benchmarked, and
how does it stack up.

**Method note.** The accepted-paper list and abstracts were verified directly
against the [ICLR virtual site](https://iclr.cc/virtual/2026/workshop/10000792)
(all 70 paper pages fetched and parsed) and the workshop
[schedule](https://sites.google.com/view/memagent-iclr26/schedule); the
deeper per-paper analysis draws on arXiv versions and extensive search-based
research. OpenReview's API itself sits behind a browser-verification wall,
so review threads were not read. Three papers an earlier pass had marked
"likely accepted" — Mem-T, The AI Hippocampus, and the SJTU agent-native
study — are **not** on the workshop program; they remain cited below as field
context, correctly attributed.

-----

## 1. The workshop at a glance

Seventy papers. The center of gravity: **write-path control** (what to admit,
how to compress without losing what matters), **evaluation beyond recall**
(structure, shift-recovery, causality, procedural retrieval), **graph and
hierarchical memory** (at least six graph-memory papers), and **experience →
procedural knowledge** (reflection, coaching, skill libraries, feedback
distillation). A full title list is in Appendix B. The papers most relevant
to memgraph, deep-dived below (★ = oral):

| Paper | Core idea | Refs |
|---|---|---|
| ★ **Evaluating AGENTS.md: Are Repository-Level Context Files Helpful for Coding Agents?** (Gloaguen, Mündler, Mueller, Raychev, Vechev — ETH/LogicStar) | Across SWE-bench tasks and a novel developer-provided-context issue set, repo context files produce **no improvement in task success** while **increasing inference cost >20%**. Agents respect the instructions — and the unnecessary requirements make tasks harder. Conclusion: context files should state only minimal requirements | [OpenReview pLi3A8bscP](https://openreview.net/forum?id=pLi3A8bscP) |
| ★ **StructMemEval** — *Evaluating Memory Structure in LLM Agents* | Benchmarks memory *organization* (ledgers, trees, state tracking — 51 core / 207 extended problems), not recall. Retrieval-only agents fail regardless of budget; memory agents succeed **only when told how to organize**; LLMs don't invent structure spontaneously | [arXiv:2602.11243](https://arxiv.org/abs/2602.11243) |
| ★ **ALMA** — *Learning to Continually Learn via Meta-learning Agentic Memory Designs* (Clune lab) | A GPT-5 meta-agent searches memory designs *as executable code* (schema + retrieval + update); learned designs beat all hand-crafted baselines on 4 domains (84.1% ALFWorld). The search specializes per domain: fine-grained fact stores for object-interaction tasks, strategy libraries for reasoning-heavy ones | [arXiv:2602.07755](https://arxiv.org/abs/2602.07755) |
| ★ **AMA-Bench** — *Evaluating Long-Horizon Memory for Agentic Applications* | Existing benchmarks are dialogue-centric; real agent memory is a stream of **machine-generated agent-environment interactions**. Real expert-curated trajectories + synthetic arbitrary-horizon trajectories with rule-based QA. Systems fail from **loss of causality** and lossy similarity-based retrieval; their AMA Agent answers with a causality graph + tool-augmented retrieval | [iclr.cc/virtual/2026/10021275](https://iclr.cc/virtual/2026/10021275) |
| ★ **GAM** — *Hierarchical Graph Memory for LLM-based Agents* | Decouples memory *encoding* from *consolidation*: ongoing dialogue isolated in an event-progression graph, integrated into a topic-associative network only on semantic shifts; graph-guided multi-factor retrieval. Beats SOTA on LoCoMo/LongDialQA | [iclr.cc/virtual/2026/10021239](https://iclr.cc/virtual/2026/10021239) |
| **Diagnosing Retrieval vs. Utilization Bottlenecks in LLM Agent Memory** | 3×3 study (write strategy × retrieval method) on LoCoMo: **retrieval method dominates** — 20-point accuracy spread across retrievers vs 3–8 points across write strategies. Raw chunked storage (zero LLM calls) matches or beats Mem0-style extraction and MemGPT-style summarization | [iclr.cc/virtual/2026/10021251](https://iclr.cc/virtual/2026/10021251) |
| **TierMem** — *From Lossy to Verified: A Provenance-Aware Tiered Memory for Agents* | Write-time summarization loses what future queries need (the "write-before-query barrier"). Immutable raw log under a provenance-linked summary index; a trained sufficiency router escalates only when summaries can't support the answer; verified write-back consolidates grounded evidence. LoCoMo 0.851 vs 0.873 always-raw at −54% tokens / −61% latency | [arXiv:2602.17913](https://arxiv.org/abs/2602.17913) |
| **A-MAC** — *Adaptive Memory Admission Control for LLM Agents* (Workday AI) | Admission (what gets written) as a structured, interpretable decision: 4 rule-based signals (<65ms) + 1 LLM utility call, linearly weighted, weights fit per domain. F1 0.583 vs LLM-native writers, 31% less latency | [arXiv:2603.04549](https://arxiv.org/abs/2603.04549) |
| **MRAgent** — *Memory Is Reconstructed, Not Retrieved* | Replaces static retrieve-then-reason with **active reconstruction** over a Cue–Tag–Content associative graph: the LLM iteratively explores and prunes retrieval paths as evidence accumulates. Up to +23% on LoCoMo/LongMemEval at lower token cost | [iclr.cc/virtual/2026/10021254](https://iclr.cc/virtual/2026/10021254) |
| **Belief Engine** — *Bayesian Memory for Configurable Opinion Dynamics* | Externalizes belief state; stance updated by a bounded **Bayesian log-odds rule** with tunable sensitivity/anchoring/asymmetry — smoother, more reproducible belief trajectories than LLM-based updating | [iclr.cc/virtual/2026/10021252](https://iclr.cc/virtual/2026/10021252) |
| **Epistemic Memory Failures in Long-Form Narrative Agents** (deployment study) | Names "known-information forgetting": world state is consistent, facts are correct, but the agent forgets *what it already knows* — a mismatch between world state and epistemic state, caused by recency-based context injection. Key-facts injection with "already knows" markers cut it 73% | [iclr.cc/virtual/2026/10021229](https://iclr.cc/virtual/2026/10021229) |
| **MINJA** — *Memory Injection Attacks on LLM Agents via Query-Only Interaction* | Poisons agent memory through ordinary queries alone — bridging steps + indication prompts, progressively shortened until malicious records retrieve naturally. Presented *at this workshop* | [arXiv:2503.03704](https://arxiv.org/abs/2503.03704) |
| **PROCED-MEM** — benchmarking procedural memory retrieval | Evaluates procedural retrieval *independently of execution* (ALFWorld + OSWorld): a 30–42% MAP **generalization cliff** on novel contexts, and a granularity reversal (visual features best coarse, worst fine) | [iclr.cc/virtual/2026/10021288](https://iclr.cc/virtual/2026/10021288) |
| **Cost-Sensitive Store Routing** — *Did You Check the Right Pocket?* | Multi-store agents shouldn't query every store per question: store routing as a cost-sensitive decision problem; oracle routing beats uniform retrieval on accuracy *and* tokens | [iclr.cc/virtual/2026/10021245](https://iclr.cc/virtual/2026/10021245) |
| **Agentic Memory Should Localize Compression** | Formalizes **interference**: expected policy divergence before/after a memory update. Stability is governed by retrieval–update overlap; modular designs localize update effects | [iclr.cc/virtual/2026/10021220](https://iclr.cc/virtual/2026/10021220) |
| *Distilling Feedback into Memory-as-a-Tool* (Komorebi AI) | Distills transient critiques into persistent guideline *files* the agent reads/edits via ls/read/write/edit tools; matches expensive test-time refinement after ~2 feedback rounds, then compounds | [arXiv:2601.05960](https://arxiv.org/abs/2601.05960) |
| **ERL** — *Experiential Reflective Learning* | Post-task reflection over single trajectories → natural-language heuristics (strategies *and* failure modes); top-20 injected at test time. Gaia2 56.1% (+7.8 over ReAct). Heuristics beat raw trajectory replay — abstraction transfers, traces don't | [arXiv:2603.24639](https://arxiv.org/abs/2603.24639) |
| **WebCoach** — cross-session memory guidance for web agents | Condenser → episodic store → a *coach* that retrieves by similarity+recency and *decides whether* to inject advice via runtime hooks (gated push, not always-on RAG). WebVoyager 47%→61% | [arXiv:2511.12997](https://arxiv.org/abs/2511.12997) |
| **SABER** — *Small Actions, Big Errors* (Amazon) | Deviations at **mutating** steps cut success odds up to 96%; non-mutating barely matter. Fix: mutation-gated verification + block-based context cleaning keeping constraint-critical history salient | [arXiv:2512.07850](https://arxiv.org/abs/2512.07850) |
| **ShiftBench** — recovery of agent memory under distribution shift (tiny paper) | Reporting protocol: mark shift points, measure **Recovery@T** (evidence-hit rate T queries after the shift). Method rankings *invert* under shift — hierarchical retrieval overtakes flat despite weaker steady-state accuracy | [OpenReview CCSztIjmOy](https://openreview.net/attachment?id=CCSztIjmOy&name=pdf) |
| **DialSim** — real-time long-term dialogue simulator (KAIST/SNU) | ~1,300 sessions / 352K tokens; spontaneous mid-dialogue questions under a 1–5s answer budget; unanswerable questions test abstention; **adversarial character-renaming detects parametric-knowledge leakage**. No agent above 60% | [arXiv:2406.13144](https://arxiv.org/abs/2406.13144) |
| **SIRA** — *SuperIntelligent Retrieval Agent* (Meta) | Compresses multi-round search into one shot: LLM enriches the corpus with indexing phrases at *write* time + expands queries at read time over plain BM25. SOTA on BEIR, training-free | [arXiv:2605.06647](https://arxiv.org/abs/2605.06647) |

Other accepted papers of note: UltRAG (KG-RAG at Wikidata scale via neural
query executors), SimpleMem ★, Agentic Context Engineering ★ (ACE — evolving
contexts as self-improvement), Toward a Theory of Hierarchical Memory ★,
Log-Augmented Generation ★ (reusable computation logs), SkillRL ★ (recursive
skill libraries), Just-In-Time RL ★ (continual learning without gradient
updates), MemGrad (textual gradients over agentic *software development*),
EcphoryRAG, CraniMem, MIRROR, ENGRAM, CloneMem, and a survey (*From Storage
to Experience*). Full list in Appendix B.

Notably: **not one accepted paper defends the status quo of agent-maintained
markdown files** — the one paper that tested static context files (AGENTS.md,
an oral) found them actively counterproductive, and the write-path papers
(A-MAC, TierMem, the freshness line of work) are each, implicitly, a case
against in-place-mutated notes as a memory substrate.

-----

## 2. Where memgraph's bets got independently validated

The 2026 literature converged, from several directions, on positions this
design took in advance. Worth stating plainly because it means the core is
sound:

**The incumbent — agent-maintained markdown memory — now has measured
failure modes on both of its halves.** To be precise about the target:
memgraph's competitor is not so much hand-authored `AGENTS.md` (which it
will likely obviate in passing) as **auto-memory** — the pattern, now
shipping in Claude Code, where the agent itself accumulates markdown files
scattered across memory directories and *updates them in place* over time.
The 2026 literature dismantles that pattern on two independent axes.

*The injection half:* the AGENTS.md oral shows ambient repository context,
loaded regardless of the task at hand, delivers zero task-success gain at
+20% inference cost — and auto-memory is ambient injection too; its files
load at session start whether or not they bear on the work. What this
validates is not "structured memory wins" but "**selective, on-demand
memory** wins over always-injected context." memgraph's query-at-need read
model is on the right side of that line; a hypothetical "dump the graph into
the prompt" mode would not be.

*The write half:* in-place updates are an ungated LLM-driven write path that
destroys its own history — the precise design the field spent 2026 arguing
against. The freshness result (+10.8pp for deterministic versioning over
LLM-mediated updates) says the LLM shouldn't adjudicate what's current;
A-MAC says unscored admission hoards hallucinated and obsolete facts;
TierMem says a lossy rewrite whose source evidence is gone leaves omissions
*unverifiable*; and the A-MEM line of critique (self-modifying notes) named
the drift problem years ago: memory that rewrites itself under its own
influence, with no provenance guarantees. An in-place-edited markdown file
can't answer "what did we believe before, and why did it change" — it can't
even show *that* it changed. MINJA lands squarely on this surface as well:
auto-memory writes whatever sessions contain, with no admission gate, no
confidence ceiling, and no epistemic distinction between a passing remark
and a standing decision. memgraph's design answers each point one-for-one:
writes are typed and policy-gated, mutation is supersession with history,
commitments can't be silently rewritten, and injection is replaced by
query-at-need. The AGENTS.md result also sets the bar the replacement must
clear: demonstrated *net* task improvement after its own overhead (§4.3).

**Bi-temporal KG with invalidate-don't-delete is the winning structure for
update-heavy recall.** The SJTU "agent-native memory" study
([arXiv:2606.24775](https://arxiv.org/abs/2606.24775) — field context, not a
workshop paper) benchmarked 12 systems across 11 datasets: no architecture
dominates, but *structure-aware systems lead on LongMemEval* (Zep's temporal
KG tops the update-heavy workload) and *trace-preserving memories win
stateful agentic workloads*. memgraph is both at once. AMA-Bench (workshop
oral) adds the agentic-trajectory version of the same finding: systems fail
from **loss of causality**, and the fix that worked was a causality graph —
provenance-linked structure, exactly the episode/derived-from machinery here.

**Deterministic write-time control beats LLM judgment on the write path.**
*Don't Ask the LLM to Track Freshness*
([arXiv:2606.01435](https://arxiv.org/abs/2606.01435)) shows deterministic
version-aware conflict resolution beats LLM-mediated resolution by +10.8pp.
SAGE's novelty gate ([arXiv:2605.30711](https://arxiv.org/abs/2605.30711))
makes ADD/NOOP deterministic and beats Mem0 while cutting cost 3.4×. A-MAC
keeps 4 of its 5 admission signals rule-based. TOKI
([arXiv:2606.06240](https://arxiv.org/abs/2606.06240)) formalizes bi-temporal
contradiction resolution as a typed operator algebra. memgraph's architecture
— the LLM never runs on the write path, conflicts resolve by epistemic-class
policy, the judge is offline and gated — is exactly this position, taken
before these papers landed.

**Epistemic typing is now shipping in SOTA systems — and getting theory.**
Hindsight ([arXiv:2512.12818](https://arxiv.org/abs/2512.12818)), currently
claiming #1 on BEAM, separates world facts / experiences / observations /
opinions with evolving confidence. Kumiho
([arXiv:2603.17244](https://arxiv.org/abs/2603.17244)) proves AGM
belief-revision postulates for a versioned graph memory — "commitments never
silently clobbered" is a practical instance of core-retainment. At the
workshop itself, the Belief Engine paper showed externalized, rule-updated
belief state is *more stable and reproducible* than asking the LLM to update
beliefs — the same argument memgraph makes about conflict policy. And the
narrative-agents deployment study coined "known-information forgetting" —
world state right, epistemic state wrong — which is precisely the distinction
a fact-with-provenance store can answer that a text pile cannot ("what did
we already establish, and where?").

**Agents don't invent structure — impose the schema.** StructMemEval (oral):
memory agents solve organization-requiring tasks *only when prompted how to
organize*. memgraph's controlled 22-predicate vocabulary, did-you-mean
rejection of novel predicates, and the skill that tells the agent when/how to
read and write are the "organization hint," made permanent and enforced at
the API rather than hoped-for in a prompt.

**Declarative and procedural memory want different stores.** ALMA's
meta-search, given freedom to design memory as code, converged on the split
this project's synthesis doc predicted: fine-grained factual stores for
world-state-heavy domains, strategy libraries for reasoning-heavy ones. The
Distilling Feedback paper adds a useful nuance to the "markdown is dead"
thesis: file-based guideline memory edited through ordinary file tools
*works* — for procedural knowledge specifically. The right reading is not
that files lose to graphs, but that procedural memory tolerates prose files,
while declarative memory — the part needing invalidation, time travel, and
conflict semantics — is what demands the graph. memgraph implicitly agrees
(skills hold the judgment, the graph holds the facts), but its procedural
side is unbuilt (§3.4).

**Localized maintenance beats global reorganization.** The SJTU study's
cost-performance finding, now with workshop theory behind it: *Agentic Memory
Should Localize Compression* formalizes interference as retrieval–update
overlap and argues modular designs localize update effects. memgraph's
`ingest-code` reconciliation (invalidate only what the analysis stopped
producing), read-time decay (no batch job), and per-subject-bounded conflict
sweep are all localized-maintenance designs; GAM's decoupling of encoding
from consolidation mirrors the episode → consolidate pipeline.

**Forgetting via decay + reinforcement, with typed exemptions.** Learned
forgetting and consolidation remain the field's acknowledged blind spots;
production systems mostly lack decay. memgraph's disuse half-life computed at
read time, reinforcement toward per-source ceilings, and exemption of
commitments is ahead of most shipping systems — though see §3.5.

**The niche is still open — but no longer empty.** The hosted players (Mem0,
Zep, Supermemory) all point at user-profile personalization. The convergent
neighbor is **Letta Code's Context Repositories** (Feb 2026): a git-backed
memory filesystem, versioned by commits, subagents editing memory in isolated
worktrees. It shares the owned-portable philosophy but is note-shaped, not
fact-shaped — no bi-temporal queries, no epistemic typing, no conflict
machinery. It is, however, shipping inside a coding agent people use.

-----

## 3. Gaps in practice

Ordered roughly by how hard the literature argues for each.

### 3.1 No raw-evidence tier: extraction faces the write-before-query barrier

TierMem's core argument applies directly to `session-extract`: compression
(extraction) decisions are made *before* knowing what a future query will
hinge on. memgraph extracts durable facts from a transcript, records an
episode summary, and drops the transcript. Anything the extractor didn't deem
durable is unrecoverable, and no answer can be audited past the episode
summary. The workshop's *Diagnosing Retrieval vs. Utilization* study makes
the same point from the other end: raw chunked storage with zero LLM calls
matched or beat expensive lossy extraction — "current memory pipelines may
discard useful context that downstream retrieval mechanisms fail to
compensate for." TierMem's fix is cheap and structurally compatible: keep the
raw transcript as an immutable, page-addressed artifact (content-addressed
files next to the dump would do), give episodes provenance pointers into
pages, and let a sufficiency path escalate graph → episode summary → raw
pages when graph facts can't support an answer. This also upgrades the
provenance story from "which session" to "which utterance."

### 3.2 Retrieval is the thinnest layer — and the evidence says it's where the points are

Current retrieval: exact/alias entity lookup, BFS, full-text search, ranked
by effective confidence. The *Diagnosing* study's headline is blunt:
on conversational QA, retrieval method accounts for a 20-point accuracy
spread while write-time sophistication accounts for 3–8 — under current
practices, improving retrieval buys more than improving writes. An honest
reading for memgraph: its write-time structure is *not* justified by plain
recall accuracy (flat stores with good retrieval match it there); it is
justified by the queries flat stores cannot answer at all — history,
time-travel, conflict surfacing, provenance. Both halves of that sentence
should be measured (§4).

Concretely missing, in increasing ambition: hybrid retrieval (Datalevin
already has SIMD vector search; Mem0's 2026 gains came largely from fusing
semantic + keyword + entity scorers); SIRA-style write-time enrichment
(consolidation could emit search phrases / alt-labels so FTS behaves closer
to semantic search without embeddings); and the escalation pattern now
validated twice at the workshop — TierMem's sufficiency router and MRAgent's
active reconstruction (iteratively explore and prune retrieval paths as
evidence accumulates, +23% at *lower* token cost). memgraph's BFS is a fixed-
depth expansion; an evidence-guided walk over the same graph is the upgrade.
The store-routing paper adds the multi-store version: decide *which* memory
(graph facts vs episode summaries vs raw pages, per §3.1) a query needs
rather than hitting everything. And WebCoach's coach pattern is the push-side
complement to the skill's pull-side judgment: a gated hook (on file-open or
task-start) deciding *whether* the graph has something worth interrupting
with — standing decisions being the obvious trigger.

### 3.3 Admission control is cruder than the state of the art

`session-extract` gates writes with a confidence cap (0.7) and source typing
— a fixed prior, not a decision. A-MAC scores each candidate on future
utility, factual confidence, novelty, recency, and a content-type prior; SAGE
makes the ADD/NOOP call deterministically from embedding density. memgraph
has the ingredients (novelty ≈ duplicate detection already exists as
reinforcement; type prior ≈ epistemic class) but doesn't compose them into an
explicit admit/reject score, and admits everything the extractor produces. A
rule-based admission score with one optional LLM utility signal would drop
into the ingest path without violating the no-LLM-on-write-path principle.
One caution from §3.1: given the raw-tier argument, aggressive admission
control *without* a raw fallback compounds the write-before-query problem —
gate the graph, keep the log.

### 3.4 Procedural memory is the field's biggest win — and still a TODO here

The single largest cluster at the workshop (Distilling Feedback, ERL,
WebCoach, SkillRL, Real-Time Procedural Learning, MemGrad — the last applied
to multi-agent *software development* specifically) plus the coding-agent
literature (MemCoder; subtask-level memory +4.7pp on SWE-bench Verified;
"Getting Better at Working With You" compiling user corrections into
enforcement rules) all monetize the same thing: **turning failures and
feedback into reusable procedural knowledge**. memgraph's failure ingester
and ADR ingester are the top two TODO items and remain unbuilt. The
literature supplies design guidance: ERL shows heuristics distilled from
*single* trajectories beat replaying raw trajectories, and Memory Transfer
Learning ([arXiv:2604.14004](https://arxiv.org/abs/2604.14004)) confirms it
across six coding benchmarks — high-level insights transfer, low-level traces
cause negative transfer. Extract the lesson, not the diff. SABER adds the
targeting guidance: it's *mutating* actions where deviations kill success, so
failure extraction should preferentially capture lessons about writes,
migrations, and deploys — and the skill's read policy should treat "about to
mutate" as the moment to consult standing decisions. PROCED-MEM's
generalization cliff (30–42% MAP drop on novel contexts) is the warning
label: procedural retrieval that looks fine in-domain falls off a cliff out
of it, so eval must include novel-context cases.

### 3.5 No outcome signal reaches the store

Reinforcement currently counts *writes* (re-assertion, re-derivation), never
*usefulness*. The RL thread (Memory-R1 → Mem-T's hindsight credit assignment
→ SWE-MeM's memory-aware GRPO for coding agents) attributes downstream task
success back to the memory operations that enabled it. memgraph deliberately
avoids learned components — defensible for inspectability — but a non-learned
version of the same signal is available: when a fact was retrieved in a
session whose work was accepted (vs reverted), that's evidence about the
fact. The deferred "reinforcement on true retrieval" TODO is half of this;
the other half is valence (did the session succeed?), which the failure
ingester would supply. Without any usage signal, decay is blind: a fact
that's read constantly but never re-asserted fades identically to one nobody
needs. The Belief Engine paper also suggests a more principled confidence
update than the current cap-and-ceiling arithmetic: bounded Bayesian
log-odds with explicit anchoring and asymmetry parameters — same
inspectability, better-behaved dynamics.

### 3.6 Memory poisoning is an unmodeled threat — and it was presented at this workshop

MINJA achieves 98.2% injection success into agent memories through ordinary
interaction — no privileged access — and the 2026 follow-ups ("poison once,
exploit forever") show one poisoned record can steer behavior indefinitely.
Its presence on the MemAgents program makes the threat model mainstream.
memgraph's mitigations are real but incidental: session facts cap at 0.7,
provenance is kept, commitments can't be silently overwritten, unused facts
decay. But `session-extract` will faithfully ingest whatever a transcript
says, and a poisoned "preference" would sit at 0.7 steering the agent until
it decays. Missing: any trust model on sources, any anomaly check on writes
(a SAGE-style novelty/outlier gate doubles as a defense), and any red-team
case in the benchmark.

### 3.7 Concurrency and multi-writer semantics

Single-writer is a stated v0 scope choice, but the field moved: Letta MemFS
runs concurrent subagent memory edits in git worktrees with merge resolution;
TOKI frames contradiction resolution *as* write-time concurrency control.
Coding agents increasingly are multi-agent, and two subagents asserting facts
about the same entity will hit last-write-wins at the LMDB level with no
story. Even a lease/lock or append-log-and-reconcile design would close the
obvious hole.

### 3.8 Scale is unproven

BEAM runs to 10M tokens; AMA-Bench scales synthetic trajectories to arbitrary
horizons; the shoply fixture is three sessions and three code passes over a
toy repo. Nothing in memgraph's design obviously breaks at 500k LOC /
thousands of sessions — candidate-set reads and batched BFS were built for
exactly this — but there is no measurement, and the per-invocation pod-start
cost that motivates the MCP front-end will bite long before graph size does.

### 3.9 Judge evaluation inherits the judge-reliability problem

`bb bench llm` measures judge verdict accuracy against labeled pairs — good.
But the 2026 judge literature (flip rates averaging 14%, up to 56%,
[arXiv:2606.13685](https://arxiv.org/abs/2606.13685)) says single-run judge
accuracy is noisy enough to mislead. Cheap fix: run each labeled pair k
times, report flip rate alongside accuracy, and let stability inform the
0.8 resolution gate.

-----

## 4. How to benchmark this

### 4.1 What exists is the right shape

The shoply benchmark is — by the standards of the 2026 eval-critique
literature — methodologically ahead of most published memory benchmarks:
deterministic scoring (no LLM judge in the CI gate), capability-mapped
questions, a longitudinal fixture with real store mechanics, and an explicit
mechanics/LLM-quality split. The *Anatomy of Agentic Memory* critique
([arXiv:2602.19320](https://arxiv.org/abs/2602.19320)) faults the field for
judge-sensitivity, metric misalignment, and ignoring maintenance cost —
shoply avoids the first two by construction. AMA-Bench (oral) independently
landed on the same design for its synthetic tier: generated trajectories of
arbitrary horizon scored by *rule-based* QA. And its diagnosis of why systems
fail — loss of causality, lossy similarity retrieval — is a list of things
shoply's provenance and history questions already probe. What shoply lacks is
coverage and scale.

### 4.2 The "no codebase-memory benchmark" premise is now stale

The handoff doc's claim that no LongMemEval equivalent exists for codebase
memory was true when written; it isn't anymore:

- **SWE-ContextBench** ([arXiv:2602.08316](https://arxiv.org/abs/2602.08316)):
  1,100 base + 376 related tasks mined from real GitHub issue/PR dependency
  links across 51 repos; measures accuracy *and efficiency* gains when prior
  cases are available, sessions deliberately separated. The external
  benchmark closest to memgraph's thesis.
- **The AGENTS.md study protocol** (this workshop, oral): SWE-bench tasks ±
  context, plus real developer-provided context files, measuring net success
  and cost. This is a ready-made A/B design — swap "context file" for
  "memgraph skill" and the comparison is direct, including against the
  context-file baseline it just demolished.
- **AMA-Bench** (this workshop, oral): agentic-trajectory memory with
  rule-based QA at arbitrary horizon.
- **RealMem** ([arXiv:2601.06966](https://arxiv.org/abs/2601.06966)):
  project-oriented, cross-session, evolving goals.
- **STALE** ([arXiv:2605.06527](https://arxiv.org/abs/2605.06527)): do agents
  notice memories are no longer valid, especially under *implicit*
  invalidation? Best model: 55.2%.
- **ShiftBench** (this workshop): Recovery@T after distribution shift.
- **PROCED-MEM** (this workshop): procedural retrieval isolated from
  execution, with novel-context generalization cases.
- **MemoryAgentBench** ([arXiv:2507.05257](https://arxiv.org/abs/2507.05257)):
  conflict resolution / selective forgetting as a first-class competency
  (best system: 54% — the field's weak axis, and memgraph's strong one).

Strategic implication: measure where the field is weakest and memgraph is
strongest — conflict resolution, staleness, temporal validity, provenance/
causality — using external protocols where adaptable. A strong showing there
is differentiated in a way LoCoMo-style recall numbers no longer are
(vendors report 91–94% and the benchmark is considered near-saturated).

### 4.3 Concrete plan, in order of value

1. **End-task A/B on real tasks (the only metric that ultimately matters).**
   Adopt the AGENTS.md study's protocol: same tasks, same agent, four arms —
   no memory · static context file · **auto-memory (agent-maintained
   markdown, in-place updates — the actual incumbent)** · memgraph skill —
   on a repo with seeded history (SWE-ContextBench's related-task pairs are
   the task source model). Measure task success, tokens, wall-clock, and
   re-litigation counts (standing `decided-against` decisions proposed
   again). The auto-memory arm is the one that matters: beating "no memory"
   is table stakes, beating the incumbent is the product claim. The
   AGENTS.md result sets the bar *and* the framing: context files fail this
   test; a memory system that passes it has a headline. Even n=20 task
   pairs says more than any retrieval metric. The staleness tier (#2) is
   where the auto-memory arm should visibly lose: after the fixture's
   migration and rename, in-place-updated notes either still assert the old
   world or have silently lost the evidence that it ever was the old world.
2. **A staleness/implicit-conflict tier in shoply (STALE-style).** Today's
   fixture invalidates explicitly. Add cases where the code contradicts a
   session-derived fact *without anyone saying so* — the dependency quietly
   removed, the preference the code stopped following — and score whether
   reads surface current truth, the sweep finds the conflict, and decay
   buries the stale fact before it misleads. The field's 55% axis;
   memgraph's machinery is built for it and should prove it.
3. **Abstention questions.** Questions whose correct answer is "the graph
   doesn't know" — score refusal vs confabulation at the retrieval layer
   (empty vs near-miss garbage) and at the skill layer (does the agent say
   so). Both LongMemEval and DialSim treat abstention as first-class.
4. **A poisoning red-team case.** One fixture session containing a planted
   instruction-shaped "preference" and a plausible-but-false fact; score
   whether admission/confidence/decay/conflict machinery contains the blast
   radius (fact stays ≤0.7, decays unreinforced, never overrides a
   commitment, surfaces in a sweep). MINJA-style, miniaturized.
5. **A shift-recovery case (ShiftBench's axis).** The fixture already has a
   rename and a migration; measure **Recovery@T**: evidence-hit rate at T
   queries after the shift — how many reads/writes until old names resolve,
   conflicts settle, stale facts fade. ShiftBench found method rankings
   *invert* under shift; memgraph's alias machinery and mechanical
   reconciliation should recover in O(1) passes, which would be a
   differentiating number.
6. **Scale tier.** Generate synthetic history (hundreds of sessions,
   thousands of entities — AMA-Bench-style arbitrary-horizon generation with
   rule-based QA, for repo events) and report read latency, sweep cost, and
   accuracy at 10×/100× fixture size. Report **maintenance cost** (tokens
   and wall-clock per consolidate pass) — the metric the Anatomy critique
   says everyone omits.
7. **Retrieval-vs-structure ablation (the honest one).** Following the
   *Diagnosing* study: hold the fixture fixed, compare (a) memgraph full,
   (b) raw transcript chunks + BM25/embedding retrieval, (c) memgraph facts
   with degraded retrieval. Report where structure actually pays (history,
   time-travel, conflicts — expected) and where it doesn't (plain recall —
   expected). Publishing the negative half is what makes the positive half
   credible.
8. **Judge stability.** k-run flip rate on the labeled conflict pairs,
   reported next to accuracy (§3.9).
9. **Metric hygiene from DialSim**: report **latency per read** alongside
   accuracy (pod cold-start makes this memgraph's honest weak spot today),
   and add a **contamination control** — fixture entities with deliberately
   swapped names, so a correct answer *must* come from the graph rather than
   the model's parametric knowledge.

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
behind it (the AGENTS.md oral, the SJTU study, AMA-Bench's causality finding,
TOKI, Kumiho, Hindsight, Belief Engine, StructMemEval, the freshness-tracking
result, the localize-compression position paper). On conflict handling and
temporal validity specifically — the axis where measured field performance
sits at ~54–55% — memgraph's machinery is ahead of every production system
surveyed except Zep, and it does epistemic typing Zep doesn't.

**Two workshop results push back on the design, and should be taken
seriously rather than explained away.** The AGENTS.md study shows repository
context *as such* doesn't help and costs money — the value proposition must
be selective retrieval at the moment of need, never ambient injection, and it
must be demonstrated as net task improvement, not assumed. And the
retrieval-vs-utilization diagnosis shows write-time structuring buys little
on plain recall — memgraph's structure earns its keep only on the queries
flat stores can't answer (history, time-travel, conflicts, provenance), so
the benchmark must foreground exactly those and honestly ablate the rest.

**The gaps are real but tractable, and mostly additive.** In priority order:
raw-evidence tier under episodes (argued independently by TierMem and the
Diagnosing study); failure/procedural ingestion (the field's biggest measured
wins, six workshop papers deep); retrieval investment — hybrid scoring,
write-time enrichment, evidence-guided graph walks (the 20-point axis);
an admission score on the ingest path; poisoning defenses; multi-writer
semantics; scale and latency numbers. None require rearchitecting; the
functional-core / store-protocol seams accommodate all of them.

**The eval story should become the differentiator.** The codebase-memory
benchmark gap is closing without us — SWE-ContextBench, RealMem, AMA-Bench,
and the AGENTS.md protocol all landed within months. The move: grow shoply
toward the axes the field measures worst (staleness, conflicts, abstention,
shift-recovery, poisoning, causality, maintenance cost), adopt the AGENTS.md
three-arm A/B as the headline experiment, and publish the numbers including
the negative ablations. A deterministic, longitudinal, adversarial
codebase-memory benchmark would be a contribution to the field in its own
right — plausibly a MemAgents 2027 submission.

**Competitive position.** The hosted players moved further away (user
profiles, context lakes); the one convergent neighbor is Letta's git-backed
memory filesystem, which shares the ownership philosophy but not the
structure. The defensible claim, sharpened by this year's literature: *the
only system that is simultaneously owned, codebase-scoped, bi-temporal,
epistemically typed, and deterministic on the write path*. And the incumbent
it actually competes with — auto-memory's in-place-mutated markdown — now
has a literature's worth of arguments against it: ambient injection that
measurably doesn't pay (AGENTS.md), an LLM write path the freshness work
shows is a design error, and destroyed history that TierMem shows makes
omissions unverifiable. The pitch writes itself: auto-memory with the
epistemics done right.

-----

## Appendix A: key sources

Workshop: [MemAgents site](https://sites.google.com/view/memagent-iclr26/) ·
[schedule](https://sites.google.com/view/memagent-iclr26/schedule) ·
[ICLR virtual page](https://iclr.cc/virtual/2026/workshop/10000792) ·
[OpenReview group](https://openreview.net/group?id=ICLR.cc/2026/Workshop/MemAgent) ·
[MCML recap](https://mcml.ai/news/2026-05-06-mem-agents-workshop-at-iclr-2026/)

Field (non-workshop): [Zep/Graphiti](https://arxiv.org/abs/2501.13956) ·
[TOKI bitemporal algebra](https://arxiv.org/abs/2606.06240) ·
[Kumiho AGM semantics](https://arxiv.org/abs/2603.17244) ·
[Hindsight](https://arxiv.org/abs/2512.12818) ·
[Memory-R1](https://arxiv.org/abs/2508.19828) ·
[Mem-T](https://arxiv.org/abs/2601.23014) ·
[SWE-MeM](https://arxiv.org/abs/2606.28434) ·
[SAGE gate](https://arxiv.org/abs/2605.30711) ·
[Don't Ask the LLM to Track Freshness](https://arxiv.org/abs/2606.01435) ·
[Memory Transfer Learning](https://arxiv.org/abs/2604.14004) ·
[The AI Hippocampus](https://arxiv.org/abs/2601.09113) ·
[Agent-Native Memory study](https://arxiv.org/abs/2606.24775) ·
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

## Appendix B: the full accepted-paper list (70)

Verified against the ICLR virtual site (ids 10021220–10021289). ★ = oral.
Grouped by theme; each title links as
`https://iclr.cc/virtual/2026/<id>`.

**Memory architectures & graph memory:** GAM: Hierarchical Graph Memory ★
(10021239) · Memory Is Reconstructed, Not Retrieved (10021254) · SimpleMem ★
(10021281) · ENGRAM (10021233) · CraniMem (10021260) · MIRROR (10021270) ·
EcphoryRAG (10021255) · MemoGraph (10021271) · Human-Like Lifelong Memory
(10021262) · A Lightweight, Domain-Adaptive Memory System (10021267) ·
CoMem: Decoupled Long-Context Model (10021231) · Toward a Theory of
Hierarchical Memory ★ (10021287) · Entropic Memory (10021227) · Episodic
Memory from Compression Boundaries ★ (10021278) · Belief Engine (10021252)

**Write-path / compression / admission:** Agentic Memory Should Localize
Compression (10021220) · Adaptive Memory Admission Control (10021240) ·
MemFly: Information-Bottleneck Memory Optimization (10021228) · From Lossy to
Verified: TierMem (10021250) · INFMEM: System-2 Memory Control ★ (10021221) ·
Log-Augmented Generation ★ (10021230)

**Retrieval & routing:** SuperIntelligent Retrieval Agent (10021280) ·
Did You Check the Right Pocket? Store Routing (10021245) · UltRAG: KG RAG
(10021223) · LP-RAG (10021256) · Diagnosing Retrieval vs. Utilization
Bottlenecks (10021251) · Compute Allocation for Reasoning-Intensive Retrieval
(10021237) · Look Before You Leap: Thermodynamic Arbitration (10021225)

**Procedural / experiential / self-improvement:** Learning to Continually
Learn via Meta-learning Agentic Memory Designs ★ (10021266) · Distilling
Feedback into Memory-as-a-Tool (10021248) · Experiential Reflective Learning
(10021249) · WebCoach (10021277) · SkillRL ★ (10021283) · Agentic Context
Engineering ★ (10021286) · Just-In-Time Reinforcement Learning ★ (10021226) ·
Real-Time Procedural Learning From Experience (10021273) · Retrieval-
Augmented LLM Agents: Learning to Learn from Experience (10021272) · MemGrad:
Agentic Software Development via Textual Gradients (10021276) · Learning What
to Learn: Curriculum Curation (10021263) · Feedback Descent (10021258) ·
FactorMiner (10021261) · Learning Safe Robot Planning from Unsafe Experiences
(10021269) · SelfEvoWM (10021241) · Memory Transplants for LLM Agents
(10021285) · Learning Multimodal Trajectory Representations (10021257)

**Benchmarks & evaluation:** Evaluating AGENTS.md ★ (10021235) · Evaluating
Memory Structure in LLM Agents ★ (10021253) · AMA-Bench ★ (10021275) ·
PROCED-MEM (10021288) · ShiftBench (10021282) · DialSim (10021244) · CloneMem
(10021246) · ATOD (10021289) · Epistemic Memory Failures in Long-Form
Narrative Agents (10021229) · Do LLMs Benefit From Their Own Words? (10021234)

**Safety & failure modes:** Memory Injection Attacks (MINJA) (10021247) ·
SABER: Safeguarding Mutating Steps (10021279) · LLMs Can't Play Hangman:
Private Working Memory (10021284)

**KV-cache / parametric / systems:** CAOTE: KV Cache Token Eviction
(10021222) · Norm-Guided KV-Cache Eviction (10021224) · R-KVHash (10021259) ·
Alleviating Forgetfulness of Linear Attention (10021236) · Spectral Attention
Steering ★ (10021274) · LaCy: Small-Model Parametric Memory (10021268) ·
Tool Use Is Provably More Scalable than In-Weight Memory (10021232) ·
Latent Action Reparameterization (10021238) · Memory-Efficient Multilingual
Embeddings (10021264) · Chow–Liu Ordering for Chain-of-Agents ★ (10021243)

**Surveys & applications:** From Storage to Experience: Survey on LLM Agent
Memory (10021242) · Multi-Agent Framework for IT Operations (10021265)
