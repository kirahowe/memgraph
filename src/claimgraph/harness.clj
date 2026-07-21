(ns claimgraph.harness
  "Per-harness expectations for the ambient loop (docs/consuming-auto-memory.md),
  pinned in one place: where each coding harness keeps its auto-memory notes,
  which file it auto-injects, and the markers delimiting the managed section
  claimgraph compiles into that file. Everything here is pure except notes-dir's
  home-directory lookup, passed in by callers where testability matters.

  The managed-section markers are the echo-loop guard's anchor: ingest-notes
  strips the section before hashing and extraction (our compiled view is never
  re-consumed), and compile-context rewrites only what sits between them."
  (:require [clojure.string :as str]
            [claimgraph.logic :as logic]))

(def begin-marker "<!-- claimgraph:managed:begin -->")
(def end-marker "<!-- claimgraph:managed:end -->")

(defn strip-managed-section
  "Remove the marker-delimited managed section (markers included). A begin
  marker without an end strips to EOF — when in doubt, never re-consume our
  own view. Content without markers passes through untouched."
  [content]
  (let [s (str content)
        begin (str/index-of s begin-marker)]
    (if-not begin
      s
      (let [end (str/index-of s end-marker begin)
            after (if end (subs s (+ end (count end-marker))) "")]
        (str (subs s 0 begin) after)))))

(defn splice-managed-section
  "Replace the marker-delimited managed section of content with inner
  (markers re-added around it), or insert the block at the TOP when absent —
  the harness injects the head of the file, so the compiled view must sit
  inside that window. Splice-then-strip returns the original non-managed
  content unchanged."
  [content inner]
  (let [s (str content)
        block (str begin-marker "\n" inner "\n" end-marker)
        begin (str/index-of s begin-marker)]
    (if-not begin
      (if (str/blank? s) (str block "\n") (str block "\n\n" s))
      (let [end (str/index-of s end-marker begin)
            after (if end (subs s (+ end (count end-marker))) "")]
        (str (subs s 0 begin) block after)))))

(defn munge-project-path
  "Claude Code's project-directory munging: the absolute project path with
  every non-alphanumeric character replaced by '-'
  (/home/kira/my_app -> -home-kira-my-app)."
  [abs-path]
  (str/replace (str abs-path) #"[^A-Za-z0-9]" "-"))

(def harnesses
  "Registry of known auto-memory layouts. :notes-dir is (fn [home abs-project-dir])
  -> the directory the harness writes notes into; :inject-file is the file the
  harness auto-injects at session start (compile-context's write target);
  :note-glob is what counts as a note there (unknown layouts degrade
  gracefully — anything matching is a plain note)."
  {:claude-code
   {:id :claude-code
    :label "Claude Code auto memory"
    :inject-file "MEMORY.md"
    :note-glob "**.md"
    :notes-dir (fn [home abs-project-dir]
                 (str home "/.claude/projects/"
                      (munge-project-path abs-project-dir) "/memory"))}

   ;; Codex memories are per-machine, not per-project (docs/consuming-auto-
   ;; memory.md §5): thread summaries + durable entries + evidence files,
   ;; consolidated into memory_summary.md — which is also its injection
   ;; slot. The layout is undocumented upstream, so the glob is generous
   ;; and unknown files are treated as plain notes.
   :codex
   {:id :codex
    :label "Codex memories"
    :inject-file "memory_summary.md"
    :note-glob "**.{md,txt}"
    :notes-dir (fn [home _abs-project-dir]
                 (str home "/.codex/memories"))}})

(defn resolve-harness
  "Harness keyword/string -> registry entry, or a deterministic failure
  listing what is supported."
  [h]
  (let [k (logic/->kw (or h :claude-code))]
    (or (get harnesses k)
        (logic/fail (str "Unknown harness: " (name k))
                    {:type :unknown-harness
                     :harness (name k)
                     :supported (mapv name (keys harnesses))}))))
