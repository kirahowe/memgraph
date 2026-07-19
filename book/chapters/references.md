# References

## This repository

- [`README.md`](https://github.com/kirahowe/memgraph/blob/main/README.md), the operational overview
- [`ROADMAP.md`](https://github.com/kirahowe/memgraph/blob/main/ROADMAP.md), the 28-item build plan with findings recorded per item
- [`docs/agent-memory-synthesis.md`](https://github.com/kirahowe/memgraph/blob/main/docs/agent-memory-synthesis.md), the conceptual landscape the design grew from
- [`docs/memgraph-handoff.md`](https://github.com/kirahowe/memgraph/blob/main/docs/memgraph-handoff.md), design decisions with rationale and the alternatives weighed
- [`docs/memory-systems-comparison.md`](https://github.com/kirahowe/memgraph/blob/main/docs/memory-systems-comparison.md), the July 2026 field comparison
- [`docs/memagent-2026-review.md`](https://github.com/kirahowe/memgraph/blob/main/docs/memagent-2026-review.md), memgraph against the ICLR 2026 MemAgents workshop
- [`docs/consuming-auto-memory.md`](https://github.com/kirahowe/memgraph/blob/main/docs/consuming-auto-memory.md), the ambient loop design note
- [`.claude/skills/memgraph/SKILL.md`](https://github.com/kirahowe/memgraph/blob/main/.claude/skills/memgraph/SKILL.md), the usage judgment

## Memory systems

- Packer et al., *MemGPT: Towards LLMs as Operating Systems*, 2023. [arXiv:2310.08560](https://arxiv.org/abs/2310.08560)
- Rasmussen et al., *Zep: A Temporal Knowledge Graph Architecture for Agent Memory*, 2025. [arXiv:2501.13956](https://arxiv.org/abs/2501.13956)
- Chhikara et al., *Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory*, 2025. [arXiv:2504.19413](https://arxiv.org/abs/2504.19413)
- Xu et al., *A-MEM: Agentic Memory for LLM Agents*, NeurIPS 2025. [arXiv:2502.12110](https://arxiv.org/abs/2502.12110)
- Lin et al., *Sleep-time Compute*, 2025. [arXiv:2504.13171](https://arxiv.org/abs/2504.13171)
- [engram](https://github.com/Gentleman-Programming/engram), codebase experiential memory with conflict surfacing
- [ctxgraph](https://github.com/rohansx/ctxgraph), bi-temporal episode graph in SQLite
- [Graphiti](https://github.com/getzep/graphiti); [spec-kit](https://github.com/github/spec-kit); the OpenAI temporal-agents cookbook

## The ICLR 2026 MemAgents workshop (papers cited in this book)

- Gloaguen, Mündler, Müller, Raychev, Vechev, *Evaluating AGENTS.md: Are Repository-Level Context Files Helpful for Coding Agents?* (oral). [arXiv:2602.11988](https://arxiv.org/abs/2602.11988)
- *StructMemEval: Evaluating Memory Structure in LLM Agents* (oral). [arXiv:2602.11243](https://arxiv.org/abs/2602.11243)
- *ALMA: Learning to Continually Learn via Meta-learning Agentic Memory Designs* (oral). [arXiv:2602.07755](https://arxiv.org/abs/2602.07755)
- *AMA-Bench: Evaluating Long-Horizon Memory for Agentic Applications* (oral). [ICLR virtual 10021275](https://iclr.cc/virtual/2026/10021275)
- *Diagnosing Retrieval vs. Utilization Bottlenecks in LLM Agent Memory*. [ICLR virtual 10021251](https://iclr.cc/virtual/2026/10021251)
- *TierMem: From Lossy to Verified, a Provenance-Aware Tiered Memory for Agents*. [arXiv:2602.17913](https://arxiv.org/abs/2602.17913)
- *A-MAC: Adaptive Memory Admission Control for LLM Agents*. [arXiv:2603.04549](https://arxiv.org/abs/2603.04549)
- *MRAgent: Memory Is Reconstructed, Not Retrieved*. [ICLR virtual 10021254](https://iclr.cc/virtual/2026/10021254)
- *Belief Engine: Bayesian Memory for Configurable Opinion Dynamics*. [ICLR virtual 10021252](https://iclr.cc/virtual/2026/10021252)
- *MINJA: Memory Injection Attacks on LLM Agents via Query-Only Interaction*. [arXiv:2503.03704](https://arxiv.org/abs/2503.03704)
- *SABER: Small Actions, Big Errors*. [arXiv:2512.07850](https://arxiv.org/abs/2512.07850)
- *ShiftBench: Recovery of Agent Memory Under Distribution Shift*. [OpenReview CCSztIjmOy](https://openreview.net/attachment?id=CCSztIjmOy&name=pdf)
- *DialSim: A Real-Time Simulator for Long-Term Dialogue*. [arXiv:2406.13144](https://arxiv.org/abs/2406.13144)
- *SIRA: SuperIntelligent Retrieval Agent*. [arXiv:2605.06647](https://arxiv.org/abs/2605.06647)
- *ERL: Experiential Reflective Learning*. [arXiv:2603.24639](https://arxiv.org/abs/2603.24639)
- *WebCoach: Cross-Session Memory Guidance for Web Agents*. [arXiv:2511.12997](https://arxiv.org/abs/2511.12997)
- *Distilling Feedback into Memory-as-a-Tool*. [arXiv:2601.05960](https://arxiv.org/abs/2601.05960)
- *PROCED-MEM: Benchmarking Procedural Memory Retrieval*. [ICLR virtual 10021288](https://iclr.cc/virtual/2026/10021288)

## The wider 2025–2026 field

- *Don't Ask the LLM to Track Freshness*. [arXiv:2606.01435](https://arxiv.org/abs/2606.01435)
- *SAGE: a deterministic novelty gate for memory writes*. [arXiv:2605.30711](https://arxiv.org/abs/2605.30711)
- *TOKI: a bitemporal operator algebra for contradiction resolution*. [arXiv:2606.06240](https://arxiv.org/abs/2606.06240)
- *Kumiho: AGM belief-revision semantics for versioned graph memory*. [arXiv:2603.17244](https://arxiv.org/abs/2603.17244)
- *Hindsight: epistemically typed memory with evolving confidence*. [arXiv:2512.12818](https://arxiv.org/abs/2512.12818)
- *Memory-R1: RL-trained memory management*, ACL 2026. [arXiv:2508.19828](https://arxiv.org/abs/2508.19828)
- *MemoryOS: OS-style tiered memory with heat-based eviction*, EMNLP 2025. [arXiv:2506.06326](https://arxiv.org/abs/2506.06326)
- *Forgetful but Faithful: principled forgetting with regret bounds*. [arXiv:2512.12856](https://arxiv.org/abs/2512.12856)
- *Memory Transfer Learning: insights transfer, traces do not*. [arXiv:2604.14004](https://arxiv.org/abs/2604.14004)
- *An empirical study of agent-native memory systems* (12 systems, 11 datasets). [arXiv:2606.24775](https://arxiv.org/abs/2606.24775)
- *The Coin Flip Judge: flip rates in LLM-as-judge evaluation*. [arXiv:2606.13685](https://arxiv.org/abs/2606.13685)
- *Anatomy of Agentic Memory: a critique of memory evaluation*. [arXiv:2602.19320](https://arxiv.org/abs/2602.19320)

## Benchmarks

- *LongMemEval*. [arXiv:2410.10813](https://arxiv.org/abs/2410.10813)
- *MemoryAgentBench* (conflict resolution and selective forgetting as first-class competencies). [arXiv:2507.05257](https://arxiv.org/abs/2507.05257)
- *BEAM* (10M-token horizons). [arXiv:2510.27246](https://arxiv.org/abs/2510.27246)
- *STALE* (do agents notice memories are no longer valid). [arXiv:2605.06527](https://arxiv.org/abs/2605.06527)
- *SWE-ContextBench* (cross-session codebase tasks). [arXiv:2602.08316](https://arxiv.org/abs/2602.08316)
- *RealMem* (project-oriented, evolving goals). [arXiv:2601.06966](https://arxiv.org/abs/2601.06966)

## Foundations

- Tulving, *Episodic and Semantic Memory*, 1972. [pdf](https://alicekim.ca/EMSM72.pdf)
- Anderson and Schooler, *Reflections of the Environment in Memory*, Psychological Science, 1991. [doi:10.1111/j.1467-9280.1991.tb00174.x](https://doi.org/10.1111/j.1467-9280.1991.tb00174.x)
- Alchourrón, Gärdenfors, and Makinson, *On the Logic of Theory Change*, Journal of Symbolic Logic, 1985. [doi:10.2307/2274239](https://doi.org/10.2307/2274239)
- Snodgrass, *Developing Time-Oriented Database Applications in SQL*, 1999. [pdf](https://www2.cs.arizona.edu/~rts/tdbbook.pdf)
- Lamport, *Time, Clocks, and the Ordering of Events in a Distributed System*, CACM, 1978. [doi:10.1145/359545.359563](https://doi.org/10.1145/359545.359563)
- Kulkarni, Demirbas, Madappa, Avva, and Leone, *Logical Physical Clocks and Consistent Snapshots*, OPODIS 2014. [pdf](https://cse.buffalo.edu/tech-reports/2014-04.pdf)
- Shapiro, Preguiça, Baquero, and Zawirski, *Conflict-free Replicated Data Types*, SSS 2011. [hal:inria-00609399](https://hal.inria.fr/inria-00609399)
- Kleppmann, Wiggins, van Hardenberg, and McGranaghan, *Local-First Software: You Own Your Data, in Spite of the Cloud*, Onward! 2019. [ink & switch](https://www.inkandswitch.com/local-first/)
- Kreps, *The Log: What Every Software Engineer Should Know About Real-Time Data's Unifying Abstraction*, 2013. [linkedin engineering](https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying)
- Cormack, Clarke, and Buettcher, *Reciprocal Rank Fusion Outperforms Condorcet and Individual Rank Learning Methods*, SIGIR 2009. [doi:10.1145/1571941.1572114](https://doi.org/10.1145/1571941.1572114)

## Standards anchored by the predicate vocabulary

- [PROV-O](https://www.w3.org/TR/prov-o/) (W3C provenance ontology)
- [SPDX](https://spdx.dev/) (software relationships)
- [DOAP](https://github.com/ewilderj/doap) (description of a project)
- [Dublin Core](https://www.dublincore.org/specifications/dublin-core/dcmi-terms/) (dcterms)
- [MADR](https://adr.github.io/madr/) (markdown any decision records, the ADR ingester's format)
