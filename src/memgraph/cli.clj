(ns memgraph.cli
  "Thin CLI front-end over memgraph.core. All commands emit JSON to stdout
  (--pretty for humans) so the same output is consumable at a terminal, by a
  skill via bash, and by a future MCP wrapper. The Datalevin backend is loaded
  lazily so --help and tests don't pay the pod tax."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [memgraph.core :as core]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(def ^:private global-spec
  {:db {:desc "Database path (default: $MEMGRAPH_DB or ./.memgraph/db)"}
   :pretty {:coerce :boolean :desc "Pretty-print JSON output"}})

(defn- db-path [opts]
  (or (:db opts) (System/getenv "MEMGRAPH_DB") ".memgraph/db"))

(defn- emit [opts data]
  (println (json/generate-string data {:pretty (boolean (:pretty opts))})))

(defn- parse-time [s] (logic/parse-instant s))

(defn- open-store [opts]
  (let [open (requiring-resolve 'memgraph.store.datalevin/open-store)
        s (open (db-path opts))]
    ;; auto-seed the vocabulary on first contact with a fresh store
    (when (empty? (store/-list-predicates s {}))
      (core/seed! s))
    s))

(defn- with-store [opts f]
  (let [s (open-store opts)]
    (try (f s) (finally (store/-close s)))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn cmd-init [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts {:status "initialized"
                  :db (str (fs/canonicalize (db-path opts)))
                  :predicates (count (store/-list-predicates s {}))}))))

(defn cmd-assert [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts (core/assert-fact s (-> opts
                                         (select-keys [:subject :subject-type :subject-scope
                                                       :predicate :object :object-type
                                                       :object-scope :object-kind
                                                       :epistemic :scope :confidence
                                                       :source-type :episode :on-conflict])
                                         (assoc :epistemic (or (:class opts) (:epistemic opts))
                                                :t-valid (parse-time (:valid-from opts))
                                                :t-invalid (parse-time (:valid-until opts)))))))))

(defn cmd-facts [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts (core/get-facts s (assoc (select-keys opts [:entity :entity-scope :direction
                                                             :predicate :scope :include-invalidated
                                                             :min-confidence])
                                          :as-of (parse-time (:as-of opts))))))))

(defn cmd-neighbor [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts (core/get-neighborhood s (assoc (select-keys opts [:entity :entity-scope :depth
                                                                    :scope :min-confidence :predicate])
                                                 :as-of (parse-time (:as-of opts))))))))

(defn cmd-history [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts (core/get-history s (select-keys opts [:subject :subject-scope :predicate]))))))

(defn cmd-search [{:keys [opts args]}]
  (let [query (or (first args) (:query opts))]
    (when (str/blank? (str query))
      (logic/fail "search requires a query" {:type :missing-query}))
    (with-store opts
      (fn [s] (emit opts (core/search s (str query) {}))))))

(defn cmd-invalidate [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/invalidate s (assoc (select-keys opts [:fact-id :reason])
                                                 :at (parse-time (:at opts))))))))

(defn cmd-conflicts [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/conflicts s)))))

(defn cmd-judge [{:keys [opts]}]
  (let [judge (requiring-resolve (if (:sweep opts)
                                   'memgraph.judge/sweep-conflicts!
                                   'memgraph.judge/judge-conflicts!))]
    (with-store opts
      (fn [s]
        (emit opts (judge s (select-keys opts [:command :resolve :min-confidence])))))))

(defn cmd-entity-ensure [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/ensure-entity s {:name (:name opts)
                                              :type (:type opts)
                                              :scope (:scope opts)})))))

(defn cmd-entity-list [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (store/-list-entities s {:type (logic/->kw (:type opts))
                                                :scope (:scope opts)})))))

(defn cmd-entity-rename [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/rename-entity s (select-keys opts [:from :to :scope]))))))

(defn cmd-entity-alias [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/alias-entity s (select-keys opts [:name :alias :scope]))))))

(defn cmd-entity-merge [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/merge-entities s (select-keys opts [:from :into :scope]))))))

(defn cmd-entity-split [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/split-entity s (select-keys opts [:from :into :scope]))))))

(defn cmd-entity-duplicates [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/entity-duplicates s)))))

(defn cmd-predicates [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/list-predicates s (select-keys opts [:category :status :usage]))))))

(defn cmd-predicate-register [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/register-predicate
                        s (select-keys opts [:id :label :category :object-kind
                                             :cardinality :definition :default-epistemic]))))))

(defn cmd-episode-open [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/open-episode s (select-keys opts [:source-type :ref]))))))

(defn cmd-episode-close [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/close-episode s (select-keys opts [:episode :summary]))))))

(defn cmd-episode-list [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (store/-list-episodes s)))))

(defn cmd-ingest [{:keys [opts]}]
  (let [lines (if-let [f (:file opts)]
                (str/split-lines (slurp f))
                (line-seq (java.io.BufferedReader. *in*)))
        facts (into []
                    (comp (remove str/blank?)
                          (map #(logic/normalize-keys (json/parse-string % true))))
                    lines)]
    (with-store opts
      (fn [s]
        (emit opts (core/ingest s (select-keys opts [:episode :source-type :ref]) facts))))))

(defn cmd-ingest-code [{:keys [opts]}]
  (let [ingest-code (requiring-resolve 'memgraph.ingest.clj-code/ingest!)]
    (with-store opts
      (fn [s] (emit opts (ingest-code s (select-keys opts [:dir :scope])))))))

(defn cmd-session-extract [{:keys [opts]}]
  (let [extract (requiring-resolve 'memgraph.ingest.session/extract!)]
    (with-store opts
      (fn [s]
        (emit opts (extract s (select-keys opts [:file :ref :extractor :dry-run])))))))

(defn cmd-ingest-notes [{:keys [opts]}]
  (let [ingest-notes (requiring-resolve 'memgraph.ingest.notes/ingest!)]
    (with-store opts
      (fn [s]
        (emit opts (ingest-notes s (select-keys opts [:harness :dir :project
                                                      :extractor :dry-run])))))))

(defn cmd-compile-context [{:keys [opts]}]
  (let [compile-context (requiring-resolve 'memgraph.context/compile!)]
    (with-store opts
      (fn [s]
        (emit opts (compile-context s (select-keys opts [:harness :dir :project
                                                         :budget :dry-run])))))))

(defn cmd-hooks-run [{:keys [opts]}]
  (let [run (requiring-resolve 'memgraph.hooks/run!)]
    (with-store opts
      (fn [s]
        (emit opts (run s (assoc (select-keys opts [:harness :project :dir :extractor
                                                    :consolidate-days :command
                                                    :resolve :min-confidence])
                                 :db (db-path opts))))))))

(defn cmd-hooks-install [{:keys [opts]}]
  (let [install (requiring-resolve 'memgraph.hooks/install!)]
    (emit opts (install (select-keys opts [:project :harness :consolidate-days :bin])))))

(defn cmd-dump [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (let [records (core/dump s)
            out (map #(json/generate-string %) records)]
        (if-let [f (:out opts)]
          (do (spit f (str (str/join "\n" out) "\n"))
              (emit opts {:status "dumped" :records (count records) :out f}))
          (doseq [line out] (println line)))))))

(defn cmd-stats [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/stats s)))))

(defn cmd-consolidate [{:keys [opts]}]
  (let [consolidate (requiring-resolve 'memgraph.consolidate/consolidate!)]
    (with-store opts
      (fn [s]
        (emit opts (consolidate s (select-keys opts [:command :resolve :min-confidence
                                                     :min-usage])))))))

(def help-text "memgraph — bi-temporal, epistemically-typed knowledge graph for coding-agent memory

Usage: memgraph <command> [options]

All commands accept --db PATH (default $MEMGRAPH_DB or ./.memgraph/db) and --pretty.
All output is JSON on stdout; errors are JSON on stderr with exit code 1.

Commands:
  init                Create the store and seed the predicate vocabulary
  assert              Assert a fact through validation + conflict resolution
                        --subject S --predicate P --object O
                        [--subject-type T] [--object-type T] [--object-kind entity|literal]
                        [--class observation|commitment|preference] [--scope SCOPE]
                        [--confidence 0.9] [--source-type code|user-assertion|inferred|decision-record|session-log]
                        [--episode ID] [--on-conflict supersede|flag|ignore]
                        [--valid-from ISO] [--valid-until ISO]
                        Valid time is first-class: --valid-from/--valid-until
                        record when a fact was true (a closed past interval is
                        one assertion). Superseding closes the predecessor at
                        the successor's valid-from; a successor starting
                        at-or-before its predecessor flags as backdated-overlap
                        instead of inverting an interval.
  facts               Facts about an entity: --entity E [--predicate P] [--scope S]
                        [--as-of ISO] [--direction out|in|both] [--include-invalidated]
                        [--min-confidence 0.5]
                        Results carry effective-confidence: the stored base
                        halved per 90-day half-life since last reinforcement
                        (re-assertion resets the clock; commitments and
                        decision-records never fade). --min-confidence
                        filters on the effective value.
  neighbor            BFS neighborhood: --entity E [--depth 2] [--as-of ISO] [--min-confidence 0.5]
  history             All versions of (subject, predicate): --subject S --predicate P
  search              Full-text search: memgraph search \"redis migration\"
  invalidate          Close a fact's validity interval: --fact-id F [--reason R]
                        [--at ISO] (when it stopped being true; default now)
  conflicts           List open conflicts (flagged facts with still-valid candidates)
  judge               LLM-judge open conflicts: relation contradicts|duplicate|
                        supersedes|compatible per pair. Reports only, unless
                        --resolve, which acts on verdicts at/above
                        --min-confidence (0.8): invalidates duplicates and
                        superseded facts, unlinks compatible pairs. A
                        contradicts verdict is never auto-resolved.
                        [--command \"claude -p\"] (default $MEMGRAPH_LLM_CMD)
                        --sweep generates candidates the write path can't
                        see (exclusive-value pairs, decision-category facts
                        sharing an object across predicates), judges them,
                        and links genuine hits into the same pipeline.
  entity ensure       --name N [--type T] [--scope S]
  entity list         [--type T] [--scope S]
  entity rename       --from X --to Y [--scope S]  (old name kept as alias;
                        facts and history untouched)
  entity alias        --name X --alias Y [--scope S]
  entity merge        --from X --into Y [--scope S]  (repoints facts, carries
                        names as aliases, invalidates exposed duplicates)
  entity split        --from X --into \"A,B\" [--scope S]  (records derived-from
                        lineage; facts stay on the source for review)
  entity duplicates   Report likely-duplicate entity clusters

  Entity lookups everywhere resolve exact names, then aliases, then a unique
  case/separator-insensitive match (\"auth-service\" finds \"AuthService\");
  near-match resolutions self-heal by recording the queried name as an alias.
  predicates          List the vocabulary [--category C] [--status S] [--usage]
  predicate register  Coin an :x/* predicate: --id x/uses-pattern [--definition ...]
  episode open        --source-type session-log|code|... [--ref REF]
  episode close       --episode ID --summary \"...\"
  episode list
  ingest              Batch assert JSONL (one fact per line): --file F | stdin
                        [--episode ID | --source-type T --ref R]
  ingest-code         Mechanical Clojure code analysis: [--dir src] [--scope code]
  session-extract     LLM-extract durable facts from a session transcript
                        (plain text or Claude Code session JSONL): --file F | stdin
                        [--ref ID] [--dry-run] [--extractor \"claude -p\"]
                        Default extractor: $MEMGRAPH_LLM_CMD or \"claude -p\".
                        Extracted facts are capped at 0.7 confidence, source-type
                        session-log. Use --dry-run to review before ingesting.
  ingest-notes        Ingest the harness's auto-memory notes (the ambient
                        capture tier): delta-detects changed note files and
                        extracts only those, one episode per (file, revision).
                        [--harness claude-code] [--project DIR] [--dir NOTES_DIR]
                        [--dry-run] [--extractor \"claude -p\"]
                        Notes ingest as agent inference: source-type agent-note,
                        confidence capped at 0.65, never commitments (a decision
                        reported by a note is demoted to an observation). No
                        reconciliation: notes the harness compacts away fade by
                        disuse instead of being invalidated. The managed
                        memgraph section of MEMORY.md is never re-consumed.
  compile-context     Compile the graph's current view into the managed
                        section of the file the harness auto-injects
                        (Claude Code: the head of MEMORY.md) — the ambient
                        read path. Deterministic (no LLM), budgeted,
                        idempotent; only the marker-delimited block is
                        rewritten, the harness's own notes stay untouched.
                        Sections in priority order: standing decisions,
                        open conflicts, recent supersessions, top current
                        facts by effective confidence (code-derived facts
                        excluded — the view carries what the code can't say).
                        [--harness claude-code] [--project DIR] [--dir NOTES_DIR]
                        [--budget 25000] [--dry-run]
  hooks install       Wire the ambient loop into the project's Claude Code
                        hooks (.claude/settings.json, SessionEnd): every
                        session ends with `hooks run`. Idempotent; foreign
                        hooks and other settings are preserved.
                        [--project DIR] [--harness claude-code]
                        [--consolidate-days 7] [--bin memgraph]
  hooks run           The SessionEnd pass: ingest-notes, compile-context,
                        and consolidate when due (stamp-gated, default every
                        7 days; 0 = always). Stages report independently —
                        an extractor failure never blocks the deterministic
                        recompile. [--harness claude-code] [--project DIR]
                        [--dir NOTES_DIR] [--consolidate-days 7]
                        [--extractor CMD] [--command CMD] [--resolve]
                        [--min-confidence 0.8]
  dump                Export everything as JSONL [--out FILE]
  stats               Store counts
  consolidate         Offline consolidation pass: LLM-summarize and close open
                        episodes that contain facts (summaries become
                        full-text searchable; mechanical digest if the LLM is
                        unavailable), judge open conflicts (report-only unless
                        --resolve), sweep for conflict candidates the write
                        path can't see, and report x/* predicates earning
                        promotion review.
                        [--resolve] [--min-confidence 0.8] [--min-usage 3]
                        [--command \"claude -p\"] (default $MEMGRAPH_LLM_CMD)
")

(defn cmd-help [_]
  (println help-text))

(def table
  [{:cmds ["init"] :fn cmd-init}
   {:cmds ["assert"] :fn cmd-assert :spec {:confidence {:coerce :double}}}
   {:cmds ["facts"] :fn cmd-facts :spec {:min-confidence {:coerce :double}
                                         :include-invalidated {:coerce :boolean}}}
   {:cmds ["neighbor"] :fn cmd-neighbor :spec {:depth {:coerce :long}
                                               :min-confidence {:coerce :double}}}
   {:cmds ["history"] :fn cmd-history}
   {:cmds ["search"] :fn cmd-search}
   {:cmds ["invalidate"] :fn cmd-invalidate}
   {:cmds ["conflicts"] :fn cmd-conflicts}
   {:cmds ["judge"] :fn cmd-judge :spec {:resolve {:coerce :boolean}
                                         :sweep {:coerce :boolean}
                                         :min-confidence {:coerce :double}}}
   {:cmds ["entity" "ensure"] :fn cmd-entity-ensure}
   {:cmds ["entity" "list"] :fn cmd-entity-list}
   {:cmds ["entity" "rename"] :fn cmd-entity-rename}
   {:cmds ["entity" "alias"] :fn cmd-entity-alias}
   {:cmds ["entity" "merge"] :fn cmd-entity-merge}
   {:cmds ["entity" "split"] :fn cmd-entity-split}
   {:cmds ["entity" "duplicates"] :fn cmd-entity-duplicates}
   {:cmds ["predicates"] :fn cmd-predicates :spec {:usage {:coerce :boolean}}}
   {:cmds ["predicate" "register"] :fn cmd-predicate-register}
   {:cmds ["episode" "open"] :fn cmd-episode-open}
   {:cmds ["episode" "close"] :fn cmd-episode-close}
   {:cmds ["episode" "list"] :fn cmd-episode-list}
   {:cmds ["ingest-code"] :fn cmd-ingest-code}
   {:cmds ["ingest"] :fn cmd-ingest}
   {:cmds ["session-extract"] :fn cmd-session-extract :spec {:dry-run {:coerce :boolean}}}
   {:cmds ["ingest-notes"] :fn cmd-ingest-notes :spec {:dry-run {:coerce :boolean}}}
   {:cmds ["compile-context"] :fn cmd-compile-context
    :spec {:budget {:coerce :long} :dry-run {:coerce :boolean}}}
   {:cmds ["hooks" "run"] :fn cmd-hooks-run
    :spec {:consolidate-days {:coerce :long} :resolve {:coerce :boolean}
           :min-confidence {:coerce :double}}}
   {:cmds ["hooks" "install"] :fn cmd-hooks-install
    :spec {:consolidate-days {:coerce :long}}}
   {:cmds ["dump"] :fn cmd-dump}
   {:cmds ["stats"] :fn cmd-stats}
   {:cmds ["consolidate"] :fn cmd-consolidate
    :spec {:resolve {:coerce :boolean} :min-confidence {:coerce :double}
           :min-usage {:coerce :long}}}
   {:cmds ["help"] :fn cmd-help}
   {:cmds [] :fn cmd-help}])

(defn -main [& args]
  (try
    (cli/dispatch table (vec args) {:spec global-spec})
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (json/generate-string
                  (merge {:error (ex-message e)}
                         (dissoc (ex-data e) :memgraph/error))
                  {:pretty true})))
      (System/exit 1))))
