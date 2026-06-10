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
   │ Store protocol│         src/memgraph/store.clj      the swappable seam
   └────┬─────────┘
        │
   datalevin impl            src/memgraph/store/datalevin.clj   the only layer that
   in-memory impl            src/memgraph/store/memory.clj      knows Datalog/datoms
```

- **Storage**: [Datalevin](https://github.com/datalevin/datalevin) via Babashka
  pod — the `dtlv` binary is a GraalVM native image that speaks the pod
  protocol, so the whole stack is two fast-start native binaries. The store
  protocol keeps SQLite (or anything else) a live fallback; the test suite runs
  identically against the in-memory implementation, which is the proof of the
  seam.
- **Bi-temporality is modeled, not engine-native**: explicit `t-valid` /
  `t-invalid` / `recorded-at` attributes, identical in shape across backends.
- **Objects are entities or literals** (RDF-style): traversal only follows
  entity-kind objects; preferences live as literal facts without minting junk
  nodes. Enforced at write time per the predicate registry.
- **Inverses are computed at query time** (`--direction in|both`), not stored
  as twins — nothing to keep consistent on invalidation (resolves handoff
  fork #1).
- **`has-status` is a predicate**, so ADR status history accumulates
  bi-temporally and status changes flow through the conflict machinery
  (resolves fork #2).
- **`ensure-entity` is its own seam** with a trivial exact name+scope body for
  now; renames/splits/aliases will live there (fork #3, deferred).

## Ingestion tiers

1. **`ingest-code`** — mechanical Clojure analysis (edamame ns-form parsing, no
   LLM): `defined-in`, `depends-on`, `written-in` facts at 0.95 confidence
   under a `:code` episode ref'd to the git SHA. Idempotent; a namespace that
   moves files supersedes its old location automatically.
2. **`session-extract`** — LLM extraction of durable knowledge (preferences,
   decisions, gotchas, conventions) from a session transcript — plain text or
   Claude Code session JSONL. The extractor is pluggable: defaults to an
   already-authenticated `claude -p` (subscription-as-judge, ~$0 marginal),
   overridable via `--extractor` / `$MEMGRAPH_EXTRACTOR_CMD`. Session-derived
   facts are second-class evidence by design: confidence capped at 0.7,
   source-type `session-log`. `--dry-run` shows what would be ingested.
3. **`ingest`** — batch JSONL (file or stdin) under one episode. Each line
   goes through the full conflict machinery; `class` is accepted as an alias
   for the epistemic field.
4. **`assert`** — one fact, interactively or from a skill.

## Maintenance

- `decay` — soft forgetting: confidence decays on stale facts; commitments and
  decision-record facts never decay.
- `consolidate` — stubbed surface for Dreaming-style offline consolidation
  (lands with the pluggable LLM judge).
- `dump` — export everything as JSONL: the portability story. The live LMDB
  directory is gitignored; the dump is the committable artifact.

## Tests

```bash
bb test    # 25 tests / 151 assertions
```

The core-semantics suite runs against BOTH store implementations (the proof of
the protocol seam); the logic suite tests pure decision functions with no store
at all; the session suite injects a fake extractor and never shells out.
`MEMGRAPH_TEST_SKIP_DATALEVIN=1 bb test` runs pod-free.

## Deviations from the handoff doc (v0 honesty)

- The `Store` protocol holds raw primitives; conflict semantics moved up into
  `core` so both backends share one implementation (the handoff sketched
  conflict detection inside `-assert-fact`).
- `ingest` runs per-fact transactions, not one batch transaction — per-fact
  conflict policy won over batch atomicity for now.
- The pod loads `dtlv` from `$PATH` (or `$MEMGRAPH_DTLV`) at a pinned release
  (0.10.18) rather than from the pod registry — registry download requires
  TLS trust of whatever proxy the environment runs behind; a binary on PATH
  doesn't.
- `decay` is age-based only (no reference tracking yet); `consolidate` is a
  stub; ACL fields are carried in the schema but unenforced — all per the
  roadmap.

## Documents

- `TODO.md` — remaining roadmap and decisions taken.
- `docs/agent-memory-synthesis.md` — the conceptual landscape this grew from.
- `docs/memgraph-handoff.md` — design decisions, rationale, and open forks.
- `.claude/skills/memgraph/SKILL.md` — the usage judgment: when an agent should
  consult, write, and how to phrase facts.
