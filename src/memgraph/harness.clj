(ns memgraph.harness
  "Per-harness expectations for the ambient loop (docs/consuming-auto-memory.md),
  pinned in one place: where each coding harness keeps its auto-memory notes,
  which file it auto-injects, and the markers delimiting the managed section
  memgraph compiles into that file. Everything here is pure except notes-dir's
  home-directory lookup, passed in by callers where testability matters.

  The managed-section markers are the echo-loop guard's anchor: ingest-notes
  strips the section before hashing and extraction (our compiled view is never
  re-consumed), and compile-context rewrites only what sits between them."
  (:require [clojure.string :as str]
            [memgraph.logic :as logic]))

(def begin-marker "<!-- memgraph:managed:begin -->")
(def end-marker "<!-- memgraph:managed:end -->")

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

(defn munge-project-path
  "Claude Code's project-directory munging: the absolute project path with
  every non-alphanumeric character replaced by '-'
  (/home/kira/my_app -> -home-kira-my-app)."
  [abs-path]
  (str/replace (str abs-path) #"[^A-Za-z0-9]" "-"))

(def harnesses
  "Registry of known auto-memory layouts. :notes-dir is (fn [home abs-project-dir])
  -> the directory the harness writes notes into; :inject-file is the file the
  harness auto-injects at session start (compile-context's write target)."
  {:claude-code
   {:id :claude-code
    :label "Claude Code auto memory"
    :inject-file "MEMORY.md"
    :notes-dir (fn [home abs-project-dir]
                 (str home "/.claude/projects/"
                      (munge-project-path abs-project-dir) "/memory"))}})

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
