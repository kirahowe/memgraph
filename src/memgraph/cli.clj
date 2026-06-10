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
            [memgraph.store :as store]))

(def ^:private global-spec
  {:db {:desc "Database path (default: $MEMGRAPH_DB or ./.memgraph/db)"}
   :pretty {:coerce :boolean :desc "Pretty-print JSON output"}})

(defn- db-path [opts]
  (or (:db opts) (System/getenv "MEMGRAPH_DB") ".memgraph/db"))

(defn- emit [opts data]
  (println (json/generate-string data {:pretty (boolean (:pretty opts))})))

(defn- parse-time [s]
  (when s
    (let [s (str s)
          iso (if (re-matches #"\d{4}-\d{2}-\d{2}" s) (str s "T00:00:00Z") s)]
      (java.util.Date/from (java.time.Instant/parse iso)))))

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
                                                :t-valid (parse-time (:valid-from opts)))))))))

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
      (core/fail "search requires a query" {:type :missing-query}))
    (with-store opts
      (fn [s] (emit opts (core/search s (str query) {}))))))

(defn cmd-invalidate [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/invalidate s (select-keys opts [:fact-id :reason]))))))

(defn cmd-entity-ensure [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/ensure-entity s {:name (:name opts)
                                              :type (:type opts)
                                              :scope (:scope opts)})))))

(defn cmd-entity-list [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (store/-list-entities s {:type (core/->kw (:type opts))
                                                :scope (:scope opts)})))))

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

(defn- normalize-keys
  "Accept snake_case or kebab-case keys in ingest JSON lines."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) m))

(defn cmd-ingest [{:keys [opts]}]
  (let [lines (if-let [f (:file opts)]
                (str/split-lines (slurp f))
                (line-seq (java.io.BufferedReader. *in*)))
        facts (into []
                    (comp (remove str/blank?)
                          (map #(normalize-keys (json/parse-string % true))))
                    lines)]
    (with-store opts
      (fn [s]
        (emit opts (core/ingest s (select-keys opts [:episode :source-type :ref]) facts))))))

(defn cmd-ingest-code [{:keys [opts]}]
  (let [analyze (requiring-resolve 'memgraph.ingest.clj-code/analyze)
        {:keys [ref files facts]} (analyze (select-keys opts [:dir :scope]))]
    (with-store opts
      (fn [s]
        (let [result (core/ingest s {:source-type :code :ref ref} facts)
              _ (core/close-episode s {:episode (:episode result)
                                       :summary (str "code-analysis pass: " files " files, "
                                                     (count facts) " facts from " ref)})]
          (emit opts (assoc result :files files :ref ref)))))))

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

(defn cmd-decay [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/decay s (select-keys opts [:older-than-days :factor]))))))

(defn cmd-consolidate [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/consolidate s {})))))

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
                        [--episode ID] [--on-conflict supersede|flag|ignore] [--valid-from ISO]
  facts               Facts about an entity: --entity E [--predicate P] [--scope S]
                        [--as-of ISO] [--direction out|in|both] [--include-invalidated]
                        [--min-confidence 0.5]
  neighbor            BFS neighborhood: --entity E [--depth 2] [--as-of ISO] [--min-confidence 0.5]
  history             All versions of (subject, predicate): --subject S --predicate P
  search              Full-text search: memgraph search \"redis migration\"
  invalidate          Close a fact's validity interval: --fact-id F [--reason R]
  entity ensure       --name N [--type T] [--scope S]
  entity list         [--type T] [--scope S]
  predicates          List the vocabulary [--category C] [--status S] [--usage]
  predicate register  Coin an :x/* predicate: --id x/uses-pattern [--definition ...]
  episode open        --source-type session-log|code|... [--ref REF]
  episode close       --episode ID --summary \"...\"
  episode list
  ingest              Batch assert JSONL (one fact per line): --file F | stdin
                        [--episode ID | --source-type T --ref R]
  ingest-code         Mechanical Clojure code analysis: [--dir src] [--scope code]
  dump                Export everything as JSONL [--out FILE]
  stats               Store counts
  decay               Soft forgetting: [--older-than-days 90] [--factor 0.9]
  consolidate         (stub) Dreaming-style consolidation
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
   {:cmds ["entity" "ensure"] :fn cmd-entity-ensure}
   {:cmds ["entity" "list"] :fn cmd-entity-list}
   {:cmds ["predicates"] :fn cmd-predicates :spec {:usage {:coerce :boolean}}}
   {:cmds ["predicate" "register"] :fn cmd-predicate-register}
   {:cmds ["episode" "open"] :fn cmd-episode-open}
   {:cmds ["episode" "close"] :fn cmd-episode-close}
   {:cmds ["episode" "list"] :fn cmd-episode-list}
   {:cmds ["ingest-code"] :fn cmd-ingest-code}
   {:cmds ["ingest"] :fn cmd-ingest}
   {:cmds ["dump"] :fn cmd-dump}
   {:cmds ["stats"] :fn cmd-stats}
   {:cmds ["decay"] :fn cmd-decay :spec {:older-than-days {:coerce :long}
                                         :factor {:coerce :double}}}
   {:cmds ["consolidate"] :fn cmd-consolidate}
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
