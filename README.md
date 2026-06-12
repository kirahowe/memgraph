# memgraph

A bi-temporal, epistemically-typed knowledge graph for AI coding-agent memory.
Owned, portable, inspectable — a structured replacement for the
`CLAUDE.md`/`AGENTS.md` markdown pile.

Every fact is a reified edge carrying a metadata bundle: valid time +
transaction time, confidence, epistemic class (observation / commitment /
preference), source type, scope, and provenance (episode). Nothing is ever
hard-deleted: contradictions close a validity interval, so the graph answers
both *"what do we currently believe about X"* and *"what did we believe in
March, and why did it change."*

```
$ bin/memgraph history --subject AuthService --predicate has-version
1.0.0   t-invalid: 2026-06-10T00:07:14Z   (superseded)
2.0.0   t-invalid: null                   (current)
```

## Install

Two native binaries, no JVM:

```bash
scripts/setup.sh        # installs babashka (bb) + the Datalevin pod binary (dtlv)
bin/memgraph init       # creates ./.memgraph/db and seeds the 22-predicate vocabulary
```

## Quickstart

```bash
# Mechanical code-analysis pass — no LLM, high confidence, idempotent.
# This alone replaces most of what people stuff into CLAUDE.md.
bin/memgraph ingest-code --dir src

# Record a human decision (a commitment — it will never be silently clobbered)
bin/memgraph assert --subject api-layer --predicate decided-against \
  --object GraphQL --class commitment --source-type decision-record

# Record a preference
bin/memgraph assert --subject AuthService --predicate prefers \
  --object "Result types over exceptions" --class preference

# Query
bin/memgraph facts --entity AuthService --pretty
bin/memgraph facts --entity memgraph.store --direction in     # who depends on it?
bin/memgraph neighbor --entity AuthService --depth 2          # BFS expansion
bin/memgraph search "GraphQL"                                 # full-text
bin/memgraph facts --entity AuthService --as-of 2026-03-01    # time travel
bin/memgraph history --subject AuthService --predicate depends-on
```

All commands emit JSON to stdout (`--pretty` for humans); errors are JSON on
stderr with exit 1. Database path: `--db`, `$MEMGRAPH_DB`, or `./.memgraph/db`.
Run `bin/memgraph help` for the full verb list.

## How conflicts resolve

When a new fact contradicts a currently-valid fact with the same
(subject, predicate) on a single-valued predicate, the resolution defaults
from the epistemic class:

- **observation / preference → supersede.** The old fact's interval is closed
  (non-lossy — it stays in history); the new fact becomes current. Code-derived
  facts auto-update when the code changes.
- **commitment → flag.** Both facts stay valid, the conflict is linked and the
  `candidates` are returned. A human decision is never silently overwritten by
  new evidence — it surfaces for review.
- Caller override: `--on-conflict supersede|flag|ignore`.
- Multi-valued predicates (e.g. `depends-on`) accumulate; exact duplicates no-op.
- **Exclusion groups** widen detection across predicates: registry rows can
  declare mutually-exclusive stances toward the same object (`prefers` /
  `decided-against` share the `:stance` group; `supersedes`/`superseded-by`
  the `:revision` group), matched loosely across the entity/literal divide —
  `decided-against "GraphQL"` collides with `prefers GraphQL`. Stance
  collisions always involve a commitment, so they flag by composition.
  Groups are deliberately conservative: a false conflict nags the human, so
  only clearly-opposed pairs are declared; everything fuzzier goes to the
  sweep.

Flagged conflicts stay open until resolved. `memgraph conflicts` lists them;
`memgraph judge` runs an LLM over each pair and classifies the relation —
`contradicts`, `duplicate`, `supersedes`, or `compatible`. By default it only
reports; with `--resolve` it invalidates duplicates and superseded facts and
unlinks compatible pairs, gated by `--min-confidence` (0.8). A `contradicts`
verdict is never auto-resolved — genuine contradictions always go to the
human. The judge command is pluggable like the extractor (`--command` /
`$MEMGRAPH_LLM_CMD`, default `claude -p`).

`memgraph judge --sweep` generates the candidates the write path can't see —
multiple values of a `:value-exclusivity :exclusive` predicate (two `prefers`
on one subject tend to be alternatives, not accumulation), and same-subject
facts sharing an object across predicates where one side is a decision
(`depends-on X` while `decided-against X` stands). Generation is pure and
bounded per-subject, never O(graph²); the LLM never runs on the write path.
Verdicts feed the same pipeline: compatible pairs are dropped silently,
genuine hits are linked, contradictions wait for the human. `consolidate`
runs the sweep as a stage.

## The vocabulary

22 curated `core/*` predicates across four categories (structural, procedural,
decision, provenance), each a first-class queryable row in the same store with
object-kind, cardinality, default epistemic class, and a `maps-to` anchor to an
established standard (PROV-O / SPDX / DOAP / Dublin Core).

Unknown predicates throw with a `did-you-mean` fuzzy suggestion — the cheap
defense against LLM-driven predicate proliferation. New relations are coined
in the `x/*` staging namespace (auto-registered with `:testing` status) and
promoted once proven. `bin/memgraph predicates --usage` shows what's earning
its place.

## Architecture

Functional core, imperative shell. All decision logic — conflict resolution,
epistemic/object-kind rules, bi-temporal filters, BFS folds, decay plans — is
pure functions over plain values in `memgraph.logic` (time and fresh ids are
passed in; decisions come back as effect plans). `memgraph.core` is the thin
shell that gathers store reads, asks logic for a decision, and executes the
plan. Mutation is concentrated there and in the store implementations.

```
CLI / skill front-end        src/memgraph/cli.clj        arg parsing, JSON in/out
        │
   imperative shell          src/memgraph/core.clj       gather reads → pure decision
        │                                                → execute effect plan
   functional core           src/memgraph/logic.clj      PURE: conflict policy, temporal
        │                    src/memgraph/predicates.clj filters, BFS folds, decay plans,
        │                                                vocabulary + validation
   ┌────┴─────────┐
   │ Store protocol│         src/memgraph/store.clj      the storage abstraction
   └────┬─────────┘
        │
   datalevin impl            src/memgraph/store/datalevin.clj   the only layer that
   in-memory impl            src/memgraph/store/memory.clj      knows Datalog/datoms
```

- **Storage**: [Datalevin](https://github.com/datalevin/datalevin) via Babashka
  pod — the `dtlv` binary is a GraalVM native image that speaks the pod
  protocol, so the whole stack is two fast-start native binaries. The pod is
  loaded from `$PATH` (override with `$MEMGRAPH_DTLV`) at a pinned release.
  The storage abstraction keeps other engines pluggable; the test suite runs
  identically against the in-memory implementation, which is the proof of it.
- **Bi-temporality is modeled, not engine-native**: explicit `t-valid` /
  `t-invalid` / `recorded-at` attributes, identical in shape across backends.
- **Objects are entities or literals** (RDF-style): traversal only follows
  entity-kind objects; preferences live as literal facts without minting junk
  nodes. Enforced at write time per the predicate registry.
- **Inverses are computed at query time** (`--direction in|both`), not stored
  as twins — nothing to keep consistent on invalidation.
- **`has-status` is a predicate**, so ADR status history accumulates
  bi-temporally and status changes flow through the conflict machinery.
- **Entity resolution is layered**: lookups resolve exact names, then aliases,
  then a unique case/separator-insensitive match (type-guarded, so a namespace
  can't silently match a class). A detected collision (two or more normalized
  matches) never guesses AND never creates — it errors with the candidates
  attached, and ingestion routes such facts to the error bucket instead of
  minting a third entity. Write-path near-matches self-heal by recording the
  queried name as an alias. Curation
  verbs handle the rest: `entity rename` keeps the old name as an alias with
  facts and history intact, `entity merge` repoints facts and collapses the
  exposed duplicates non-lossily, `entity split` records `derived-from`
  lineage, and `entity duplicates` reports likely-duplicate clusters.

## Ingestion tiers

1. **`ingest-code`** — mechanical Clojure analysis (edamame ns-form parsing, no
   LLM): `defined-in`, `depends-on`, `written-in` facts at 0.95 confidence
   under a `:code` episode ref'd to the git SHA. Each pass reconciles the
   store against the code: facts the analysis no longer produces (deleted
   files, removed requires, dropped namespaces) are invalidated mechanically,
   unchanged facts no-op, and a namespace that moves files supersedes its old
   location. The graph tracks the code with no LLM in the loop.
2. **`session-extract`** — LLM extraction of durable knowledge (preferences,
   decisions, gotchas, conventions) from a session transcript — plain text or
   Claude Code session JSONL. The extractor is pluggable: defaults to an
   already-authenticated `claude -p` (subscription-as-judge, ~$0 marginal),
   overridable via `--extractor` / `$MEMGRAPH_LLM_CMD`. The prompt carries a
   bounded roster of known entities (top by fact count, with aliases) as a
   prior — "use these exact names when you mean them" — so the extractor
   aligns synonyms instead of coining `AuthSvc` next to `AuthService`;
   normalization catches typographic drift, the roster catches semantic
   drift, and ambiguity detection backstops both. Session-derived
   facts are second-class evidence by design: confidence capped at 0.7,
   source-type `session-log`. `--dry-run` shows what would be ingested.
3. **`ingest`** — batch JSONL (file or stdin) under one episode. Each line
   goes through the full conflict machinery; `class` is accepted as an alias
   for the epistemic field.
4. **`assert`** — one fact, interactively or from a skill.

## Maintenance

- `consolidate` — the Dreaming-style offline pass: LLM-summarizes and closes
  open episodes (summaries are full-text indexed, so episodic history becomes
  searchable — "why did we do X" is a query), judges open conflicts, decays
  stale confidence, and reports `x/*` predicates earning promotion review.
  Falls back to a mechanical digest when the LLM is unavailable, so the pass
  always makes progress.
- `judge` — LLM review of open conflicts on its own (see "How conflicts
  resolve").
- `decay` — soft forgetting on its own: confidence decays on stale facts;
  commitments and decision-record facts never decay.
- `dump` — export everything as JSONL: the portability story. The live LMDB
  directory is gitignored; the dump is the committable artifact.

## Tests

```bash
bb test    # 25 tests / 151 assertions
```

The core-semantics suite runs against BOTH store implementations (the proof of
the storage abstraction); the logic suite tests pure decision functions with no
store at all; the session suite injects a fake extractor and never shells out.
`MEMGRAPH_TEST_SKIP_DATALEVIN=1 bb test` runs pod-free.

## Documents

- `TODO.md` — remaining roadmap and decisions taken.
- `docs/agent-memory-synthesis.md` — the conceptual landscape this grew from.
- `docs/memgraph-handoff.md` — design decisions, rationale, and open forks.
- `.claude/skills/memgraph/SKILL.md` — the usage judgment: when an agent should
  consult, write, and how to phrase facts.
