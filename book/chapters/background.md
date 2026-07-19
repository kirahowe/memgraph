# Background: the problem and the field

## Statelessness and the markdown pile

A large language model completes one request at a time. Each API call is an
independent function invocation; whatever continuity a coding agent appears
to have across sessions is reconstructed from files on disk. The dominant
reconstruction mechanism in 2026 is markdown: `CLAUDE.md`, `AGENTS.md`,
auto-memory directories, ADR folders, scratch notes. The harness loads some
subset of these into the context window at session start and hopes the
relevant parts are in there.

This works for a week and degrades from then on. The failure modes are
structural, not incidental:

- **No structured retrieval.** Finding what the pile knows about a service
  means grep and full-file reads.
- **No invalidation.** When the deployment target changes from Heroku to Fly,
  the note saying Heroku does not go away. Contradictions accumulate
  silently, and Claude Code's own memory documentation concedes the
  consequence: when two files "give different guidance for the same
  behavior, Claude may pick one arbitrarily"
  ([code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)).
- **No epistemic typing.** "We decided against GraphQL after a real incident"
  and "the model noticed we use kebab-case" are the same kind of line in the
  same kind of file. One of these should be nearly immovable and the other
  should fade if it stops being true. The pile cannot tell them apart.
- **No history.** An edited file destroys its own past. "What did we believe
  before, and why did it change" is unanswerable, and so is "did this ever
  change at all."
- **No consolidation.** The pile grows monotonically until someone compacts
  it by hand, or the harness truncates it by size, and neither operation
  knows what it is throwing away.

Cognitive science has distinguished kinds of memory since
[Tulving (1972)](https://alicekim.ca/EMSM72.pdf) separated episodic memory
(what happened) from semantic memory (what is known), and the agent
literature added working memory (the live context) and procedural memory
(how we do things here). A markdown pile collapses all four into one bucket
with a single retention policy: whatever fits.

## The architectural camps

By 2026 the field had sorted into four recognizable approaches.

**Vector and RAG stores** ([Mem0](https://arxiv.org/abs/2504.19413),
LangMem, most platform offerings) embed extracted fact strings and retrieve
by similarity. Cheap and fast, and structurally blind to contradiction: old
and new versions of a fact coexist as separate vectors, with recency ranking
as the only arbiter. Notably, [Mem0's v3 changelog](https://docs.mem0.ai/changelog)
records the retreat: the release moved to an ADD-only write model (its own
words, "Memories accumulate; nothing is overwritten or deleted") and dropped
its external graph backends, giving up exactly the write-time contradiction
handling its earlier version performed.

**Temporal knowledge graphs** ([Zep/Graphiti](https://arxiv.org/abs/2501.13956),
Cognee, the OpenAI temporal-agents cookbook) store entities and fact edges
with validity timestamps, invalidating contradicted edges rather than
deleting them. This is the strongest shipped structure for update-heavy
recall; a twelve-system study ([arXiv:2606.24775](https://arxiv.org/abs/2606.24775))
found temporal-graph systems leading exactly the workloads where facts
change.

**OS-style hierarchies** ([MemGPT](https://arxiv.org/abs/2310.08560), now
Letta) treat the context window as main memory and the store as swap, with
the model paging data in and out through function calls. The framing is
productive; the eviction policies are heuristic.

**Files plus agency**, the camp that grew fastest in 2026: give the agent a
filesystem and file tools and let it curate its own notes
([Letta's pivot](https://www.letta.com/blog/context-repositories/),
[Anthropic's memory tool](https://docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool),
Claude Code auto-memory, Basic Memory). The
markdown pile got a promotion: the agent maintains it now. What the camp
cannot answer is unchanged: what did we believe in March, is this a decision
or an observation, what contradicts what.

A fifth pattern, [A-MEM](https://arxiv.org/abs/2502.12110)'s self-modifying
note network, deserves its own warning label: new notes rewrite their
neighbors' content in place, which means the store drifts under its own
influence and destroys provenance as it goes.

## What the field measured in 2025 and 2026

memgraph's design bets were placed before most of the following results
landed. They are worth stating because they turned design taste into
measured findings. The full review is in
[`docs/memagent-2026-review.md`](https://github.com/kirahowe/memgraph/blob/main/docs/memagent-2026-review.md),
written against the ICLR 2026 MemAgents workshop program (70 accepted
papers).

**Ambient context injection does not pay.** The AGENTS.md study
([arXiv:2602.11988](https://arxiv.org/abs/2602.11988), Gloaguen et al., an
oral at the workshop) measured repository context files across SWE-bench
tasks and a set of developer-committed ones. Its finding, verbatim:
"Providing context files does not generally improve task success rates,
while increasing inference cost by over 20% on average."
The lesson is narrower than "context files are useless": always-injected
context loses to selective retrieval at the moment of need. Any memory
system whose read path is "dump everything into the prompt" is on the wrong
side of this result.

**The LLM should not adjudicate the write path.**
[Don't Ask the LLM to Track Freshness](https://arxiv.org/abs/2606.01435)
found deterministic version-aware conflict resolution beat LLM-mediated
resolution by 10.8 points. [SAGE](https://arxiv.org/abs/2605.30711) made
add-or-skip decisions deterministic and beat Mem0 while cutting cost 3.4x.
[A-MAC](https://arxiv.org/abs/2603.04549) kept four of its five admission
signals rule-based. [TOKI](https://arxiv.org/abs/2606.06240) formalized
bi-temporal contradiction handling as a typed operator algebra. The
convergent position: writes should be decided by policy, with the LLM at
most proposing candidates.

**Lossy extraction faces a write-before-query barrier.**
[TierMem](https://arxiv.org/abs/2602.17913) named the problem: compression
decides what to keep before any future query exists, so whatever the
extractor drops is unrecoverable and no answer can be audited past the
summary. Its fix, an immutable raw tier under the extractions with
escalation when summaries cannot support an answer, is cheap and structural.

**Retrieval is where the accuracy points are.** A 3x3 study of write
strategy against retrieval method
([ICLR virtual 10021251](https://iclr.cc/virtual/2026/10021251)) found a
20-point accuracy spread across retrievers and only 3 to 8 points across
write strategies. The honest reading for a structured store: write-time
structure is not justified by plain recall, where flat stores with good
retrieval keep up. It is justified by the queries flat stores cannot answer
at all: history, time travel, conflict surfacing, provenance.

**Agents do not invent structure.**
[StructMemEval](https://arxiv.org/abs/2602.11243) showed memory agents
succeed at organization-requiring tasks only when told how to organize.
A controlled vocabulary enforced at the API is that instruction made
permanent.

**Memory poisoning is practical.** [MINJA](https://arxiv.org/abs/2503.03704)
achieved 98% injection success into agent memories through ordinary queries,
no privileged access required. A memory system that ingests transcripts and
notes has an attack surface, and needs a trust model, not just a confidence
cap.

**Belief revision has theory waiting to be used.** The
[AGM postulates](https://doi.org/10.2307/2274239) (Alchourrón, Gärdenfors,
Makinson, 1985) describe rational belief change; [Kumiho](https://arxiv.org/abs/2603.17244)
proved AGM properties for a versioned graph memory, and the workshop's
Belief Engine paper showed rule-updated belief state is more stable than
asking the LLM to update beliefs. "A commitment is never silently
clobbered" is a practical instance of this line.

## The strategy that fell out

Two conclusions shaped everything downstream.

First, the competitor is not hand-written context files; it is auto-memory,
the agent-maintained markdown the harnesses now ship by default. Its capture
is genuinely valuable (the model already judged what was worth keeping) and
its storage is exactly the pile the literature dismantled. So memgraph does
not compete with ambient capture; it consumes it. The harness's notes become
an ingestion tier, and the graph compiles its current view back into the
file the harness injects. Capture is delegated in, injection is delegated
out, and the structured store sits in the middle as the consolidator. The
design note is [`docs/consuming-auto-memory.md`](https://github.com/kirahowe/memgraph/blob/main/docs/consuming-auto-memory.md).

Second, the claim has to be demonstrated as net end-task improvement, not
retrieval metrics. The AGENTS.md result set the bar: context that merely
exists does not help. The benchmark chapter takes that protocol (same tasks,
same agent, memory arms varied) and reports where memgraph wins, where it
merely ties, and what the failure that remains looks like.
