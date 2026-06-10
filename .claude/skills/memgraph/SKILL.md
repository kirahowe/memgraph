---
name: memgraph
description: Consult and maintain this project's knowledge graph — a bi-temporal, epistemically-typed memory store. Use when starting work on an entity (query what's known), when the user states a preference or decision (record it with the right epistemic class), when asked "why" or "since when" about the codebase (history), or at the end of a substantial session (ingest what was learned).
---

# memgraph — the project's structured memory

This repo carries a queryable knowledge graph instead of relying on markdown
piles. Every fact is bi-temporal (valid time + record time), epistemically
typed (observation / commitment / preference), confidence-weighted, and
provenance-anchored to an episode. The CLI is `bin/memgraph`; all output is
JSON (add `--pretty` when showing a human).

## When to READ

- **Before modifying an entity** (service, namespace, module): check what's known.
  ```
  bin/memgraph facts --entity AuthService
  bin/memgraph neighbor --entity AuthService --depth 2
  ```
- **Before proposing architecture or a library**: check for standing decisions.
  A `decided-against` or `prefers` fact outlives any code state — respect it
  or explicitly surface that you're proposing to revisit it.
  ```
  bin/memgraph search "GraphQL"
  bin/memgraph facts --entity api-layer --predicate decided-against
  ```
- **When asked "why" / "since when" / "what changed"**: use history and as-of.
  ```
  bin/memgraph history --subject AuthService --predicate depends-on
  bin/memgraph facts --entity AuthService --as-of 2026-03-01
  ```
- **Reverse lookups** ("what depends on X?"): `--direction in`.

## When to WRITE

Choose the epistemic class deliberately — it sets the conflict behavior:

| Class | Use for | On contradiction |
|---|---|---|
| `observation` | code-derived or verifiable facts | supersedes silently (old version kept in history) |
| `preference` | style/idiom/tool preferences | supersedes silently |
| `commitment` | human decisions ("we decided against X") | **flags** — never silently overwritten |

- **User states a preference**: record it immediately.
  ```
  bin/memgraph assert --subject <scope-entity> --predicate prefers \
    --object "small focused PRs" --class preference
  ```
- **User makes or reports a decision**: record as commitment, ideally with
  `--source-type decision-record`.
  ```
  bin/memgraph assert --subject api-layer --predicate decided-against \
    --object GraphQL --class commitment --source-type decision-record
  ```
- **End of a substantial session**: extract durable knowledge from the
  transcript (preferred — review with `--dry-run` first):
  ```
  bin/memgraph session-extract --file transcript.txt --ref <session-id> --dry-run
  bin/memgraph session-extract --file transcript.txt --ref <session-id>
  ```
  Or batch hand-written facts under one episode (JSONL via stdin or file,
  snake_case or kebab-case keys, `class` = epistemic class):
  ```
  bin/memgraph ingest --source-type session-log --ref <session-id> --file facts.jsonl
  ```
- **After structural refactors**: refresh the mechanical layer:
  ```
  bin/memgraph ingest-code --dir src
  ```
  Idempotent: unchanged facts no-op; a namespace that moved files supersedes
  its old `defined-in`; facts about deleted code are invalidated.
- **Periodically (or after ingesting)**: run the consolidation pass:
  ```
  bin/memgraph consolidate
  ```
  Closes open episodes with summaries (making episodic history searchable),
  reviews conflicts, decays stale facts, and surfaces `x/*` predicates worth
  promoting.

## Handling responses

- `status: flagged` means the new fact contradicts a standing commitment. Do
  NOT pick a winner yourself — show the user the `candidates` and ask which
  holds. Resolve with `bin/memgraph invalidate --fact-id <loser> --reason "..."`
  or re-assert with `--on-conflict supersede` once the user rules.
- Conflicts accumulate across sessions: `bin/memgraph conflicts` lists what's
  open. `bin/memgraph judge` classifies each pair (contradicts / duplicate /
  supersedes / compatible); add `--resolve` to auto-close the easy ones —
  contradictions always remain for the user to decide.
- `did-you-mean` on an unknown predicate: use the suggestion if it matches your
  intent. For a genuinely new relation, coin it in the staging namespace:
  `--predicate x/my-relation` (auto-registers with :testing status). Never
  invent `core/*` predicates.
- Predicates: `bin/memgraph predicates --usage` lists the vocabulary. Prefer
  `core/*` predicates; bare names like `depends-on` resolve to `core/*`.

## Phrasing facts well

- Subject and entity-objects are graph nodes: name them like stable entities
  (`AuthService`, `memgraph.core`, `ADR-7`), not sentences.
- Free-text goes in literal objects: `--object "idempotency keys on retries"`.
- Scope facts when they're not project-wide: `--scope module:auth`.
- Confidence: default 0.8 is right for user assertions; use 0.95+ only for
  mechanically verified facts, 0.5-0.6 for your own inferences (and
  `--source-type inferred`).
