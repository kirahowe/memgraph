# Preface {.unnumbered}

claimgraph is a memory system for AI coding agents. It stores what an agent
and its human learn about a codebase (decisions, preferences, dependencies,
failure modes, history) as a knowledge graph rather than as a pile of
markdown files. Every fact in the graph knows when it was true, when it was
recorded, how confident anyone should be in it, what kind of claim it is,
and where it came from. Nothing is ever deleted: when the world changes, the
old fact's validity interval closes and the new one opens, so the graph can
answer both "what do we believe now" and "what did we believe in March, and
why did it change."

The system is a Babashka CLI backed by [Datalevin](https://github.com/juji-io/datalevin),
wrapped in an agent skill, an MCP server, and a set of hooks that let it run
with zero effort on the user's part. It was designed and built in this
repository between 2025 and 2026, alongside a research program that surveyed
the field, compared it to roughly forty other memory systems, and benchmarked
the result. This book is the complete account: the reasoning, the design, the
working system, and the measurements.

## How to read this book

The book has three parts.

**Part I — Foundations** is prose. It explains the problem agent memory is
trying to solve, what the research literature settled in 2025 and 2026, and the
mental model behind claimgraph's design. If you read nothing else, read the
mental model chapter; every other chapter leans on it.

**Part II — The System in Practice** is executable. Each chapter is a real
Clojure namespace, evaluated against the actual claimgraph source at book build
time by [Clay](https://scicloj.github.io/clay/). The outputs you see are not
transcripts pasted into the text; they are produced fresh on every build, so
if the code drifts from the book, the build breaks. These chapters use the
in-memory store backend, which shares every line of decision logic with the
Datalevin backend through a storage protocol. The CLI equivalents appear
alongside as shell blocks.

**Part III — Operations and Reference** is operational: advanced usage, the
benchmark and its results, a comparison with the other memory systems in the
field as of July 2026, a CLI reference, and the bibliography.

## Building the book

The rendered book is generated from `book/` in the repository:

```bash
bb book            # render to book/rendered/_book/index.html
bb book:preview    # render, then serve with quarto preview
```

The build needs a JVM (the book chapters evaluate on real Clojure, not on
Babashka) and the [Quarto](https://quarto.org) CLI. The claimgraph tool itself
needs neither; it runs on two native binaries.

## Status

Everything described here is implemented and tested: 28 roadmap items landed
between the July 2026 research round and the writing of this book, plus
`claim audit` — the pre-install consistency scorecard over a repo's
existing memory pile, first of the measurement tiers planned around the
dogfooding round. The test suite holds 153 tests and 759 assertions and
runs against both store backends. The deterministic benchmark passes 33 of
33 questions and gates regressions in CI. The end-task A/B and its numbers
appear in the benchmark chapter, including the arms where claimgraph loses
and the one where the best available answer is "the graph does not know."
