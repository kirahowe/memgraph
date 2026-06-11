# TODO

Remaining roadmap, in rough priority order. Rationale for most items lives in
`docs/memgraph-handoff.md` §7.

## Review feedback round (2026-06-11) — being addressed in order

- [x] 1. Traversal chattiness: BFS now hands its whole frontier to
      `-get-facts-for` — one query per direction per level, regardless of
      frontier width.
- [ ] 2. Maintenance paths pull the whole graph: `conflicts`, `stats`,
      `decay`, `predicates --usage`, and `stale-facts` all use `-all-facts`
      and filter client-side; push filters/aggregates into store queries.
- [ ] 3. Conflict detection only fires on same-(subject, predicate)
      :one-cardinality collisions; cross-predicate and many-valued
      contradictions never reach the flag/judge pipeline.
- [ ] 4. Extractor identity discipline: resolution + aliases exist, but the
      session extractor doesn't consult existing entities when naming
      subjects, and ambiguous matches silently create rather than flag.
- [ ] 5. Valid time on writes: no `--valid-until`; supersede always closes at
      now, so "true until March, recorded in June" isn't expressible.
- [ ] 6. Decay is age-based only: reads bump nothing, so a hot fact decays
      like a dead one. Original intent was confidence decay on
      *un-referenced* facts.

## Next up

- [ ] **Failure ingester.** When agent work is rejected or reverted, extract
      why — this is where procedural memory grows from.
- [ ] **Decision-record (ADR) ingester.** Highest-authority source; parse ADR
      files into `supersedes` / `decided-against` / `has-status` commitments.
- [ ] **Predicate promotion command.** `x/*` → `core/*`: register the stable
      twin, rewrite facts, deprecate the staging term with `:replaced-by`
      (never delete). Usage counts already exist (`predicates --usage`).
- [ ] **`load` command.** Restore a store from `dump` JSONL — completes the
      portability loop (dump is currently one-way).
- [ ] **MCP front-end.** Thin second front-end over `memgraph.core` when
      cold-start per query hurts or per-turn call counts grow.
- [ ] **Vector/semantic search.** Datalevin has SIMD vector search; defer
      until FTS + graph retrieval proves insufficient.
- [ ] **ACL tier.** `read-acl`/`write-acl` are carried in the schema but
      unenforced; activate when multi-user.
- [ ] **Multi-language code ingesters.** `ingest-code` is Clojure-only
      (edamame ns-form parsing); tree-sitter would generalize it.
- [ ] **Codebase-memory benchmark.** No LongMemEval/LoCoMo equivalent exists
      for codebase memory — both a validation obstacle and an opportunity.

## Decided against

- **dump-to-SQLite migration path** (2026-06-10). JSONL dump suffices for
  portability; a SQLite backend remains possible behind the `Store` protocol
  if ever actually needed.

## Done

- [x] v0: schema, Store protocol, two backends, conflict machinery, predicate
      registry, bi-temporal reads, BFS, FTS, episodes, JSONL ingest, Clojure
      code ingester, decay, dump, CLI, skill.
- [x] Functional-core/imperative-shell refactor: pure decision logic in
      `memgraph.logic`, effects concentrated in `memgraph.core` + store impls.
- [x] Session-log extractor (`session-extract`): pluggable LLM extractor over
      transcripts (plain text or Claude Code session JSONL), dry-run mode,
      confidence capped at 0.7, source-type `:session-log`.
- [x] Mechanical invalidation on git events: every `ingest-code` pass
      reconciles the store against the new analysis and invalidates
      code-sourced facts it no longer produces (deleted files, removed
      requires, dropped namespaces). Non-lossy; history retains them.
- [x] LLM judge on the semantic-conflict path (`conflicts` + `judge`):
      classifies open conflict pairs as contradicts / duplicate / supersedes /
      compatible. Report-only by default; `--resolve` acts above a confidence
      gate and never auto-resolves contradictions. Shared LLM machinery in
      `memgraph.llm` (`$MEMGRAPH_LLM_CMD`).
- [x] Consolidation (`consolidate`): one offline pass that LLM-summarizes and
      closes open episodes (with a mechanical fallback; summaries become
      full-text searchable), judges open conflicts, decays stale confidence,
      and reports `x/*` promotion candidates. Pattern promotion to procedural
      memory remains future work alongside the promotion command.
- [x] Entity resolution: layered lookup (exact → alias → unique normalized
      match, type-guarded; ambiguity never guesses), write-path self-healing
      aliases, and curation verbs — `entity rename` / `alias` / `merge`
      (repoint + collapse duplicates) / `split` (derived-from lineage) /
      `duplicates` (cluster report).
