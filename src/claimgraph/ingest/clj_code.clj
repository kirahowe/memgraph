(ns claimgraph.ingest.clj-code
  "Mechanical (no-LLM) code-analysis ingester for Clojure codebases.
  Functional core / imperative shell: source-text analysis, fact derivation,
  and the reconciliation plan are pure (`analyze-source`, `analyses->facts`,
  `stale-facts`); `analyze` touches the filesystem and git, `ingest!`
  executes against the store. Emits high-confidence :observation facts:

    <namespace> core/defined-in <file>
    <namespace> core/depends-on <required namespace>
    <file>      core/written-in \"clojure\"

  Each pass reconciles against the previous one: code-sourced facts the new
  analysis no longer produces — deleted files, removed requires, dropped
  namespaces — are invalidated mechanically (non-lossy; history retains
  them). Unchanged facts no-op, so re-running is idempotent."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as e]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Pure: source text -> analysis -> facts
;; ---------------------------------------------------------------------------

(defn- parse-ns-form [content]
  (try
    (let [form (e/parse-string content {:all true
                                        :auto-resolve (fn [alias] (symbol (str alias)))
                                        :readers (fn [_] identity)})]
      (when (and (seq? form) (= 'ns (first form)))
        form))
    (catch Exception _ nil)))

(defn- libspec->ns [spec]
  (cond
    (symbol? spec) spec
    (and (sequential? spec) (symbol? (first spec))) (first spec)
    :else nil))

(defn- ns-requires [ns-form]
  (->> ns-form
       (filter #(and (seq? %) (#{:require :use} (first %))))
       (mapcat rest)
       (keep libspec->ns)
       distinct))

(defn analyze-source
  "Pure: source text -> {:ns sym :requires [sym]}, or nil without a
  parseable ns form."
  [content]
  (when-let [form (parse-ns-form content)]
    {:ns (second form)
     :requires (vec (ns-requires form))}))

(defn analyses->facts
  "Pure: analyses (each {:ns :file :requires}) -> fact maps ready for
  claimgraph.core/ingest. Dependencies on namespaces outside the analyzed set
  are scoped \"external\"."
  [analyses scope]
  (let [local-nss (set (map :ns analyses))
        base {:scope scope :source-type :code :confidence 0.95 :epistemic :observation}]
    (vec (mapcat
          (fn [{:keys [ns file requires]}]
            (concat
             [(merge base {:subject (str ns) :subject-type :namespace
                           :predicate :core/defined-in
                           :object file :object-type :file :object-kind :entity})
              (merge base {:subject file :subject-type :file
                           :predicate :core/written-in
                           :object "clojure" :object-kind :literal})]
             (for [r requires]
               (merge base {:subject (str ns) :subject-type :namespace
                            :predicate :core/depends-on
                            :object (str r) :object-type :namespace :object-kind :entity
                            :scope (if (local-nss r) scope "external")}))))
          analyses))))

(defn- fact-key
  "Identity of a code fact for reconciliation: subject name, predicate,
  object name-or-literal."
  [f]
  [(get-in f [:subject :name])
   (:predicate f)
   (if (= :entity (:object-kind f))
     (get-in f [:object-ref :name])
     (:object-lit f))])

(defn stale-facts
  "Pure reconciliation plan: ids of currently-valid code-sourced facts (in
  this ingest's scopes) that the new analysis no longer produces."
  [facts new-facts {:keys [scope at]}]
  (let [scopes #{scope "external"}
        produced (set (map (fn [m] [(:subject m) (:predicate m) (str (:object m))])
                           new-facts))]
    (->> facts
         (filter #(logic/fact-valid-at? % at))
         (filter #(= :code (:source-type %)))
         (filter #(scopes (:scope %)))
         (remove (comp produced fact-key))
         (mapv :id))))

;; ---------------------------------------------------------------------------
;; Shell: filesystem + git
;; ---------------------------------------------------------------------------

(defn- git-sha [dir]
  (try
    (let [{:keys [exit out]} (p/sh {:dir (str dir)} "git" "rev-parse" "HEAD")]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn analyze
  "Walk dir for Clojure source files and produce the facts plus the episode
  ref (git SHA when available)."
  [{:keys [dir scope]}]
  (let [root (fs/canonicalize (or dir "."))
        scope (or scope "code")
        files (->> (fs/glob root "**.{clj,cljc,cljs,bb}")
                   (remove #(re-find #"(^|/)(\.claimgraph|node_modules|\.git|target)/"
                                     (str (fs/relativize root %))))
                   sort)
        analyses (keep (fn [path]
                         (some-> (analyze-source (slurp (str path)))
                                 (assoc :file (str (fs/relativize root path)))))
                       files)]
    {:ref (or (git-sha root) (str root))
     :files (count analyses)
     :facts (analyses->facts analyses scope)}))

(defn ingest!
  "One code-analysis pass against the store: invalidate what the analysis no
  longer produces, assert what it does (unchanged facts no-op, moved ones
  supersede), all under a :code episode ref'd to the git SHA."
  [s {:keys [scope] :as opts}]
  (let [{:keys [ref files facts]} (analyze opts)
        scope (or scope "code")
        at (core/now)
        candidates (store/-select-facts s {:source-type :code
                                           :scopes #{scope "external"}
                                           :valid-cheap true})
        stale (stale-facts candidates facts {:scope scope :at at})]
    (doseq [id stale]
      (store/-invalidate s id at (str "code-invalidation: absent at " ref)))
    (let [result (core/ingest s {:source-type :code :ref ref} facts)]
      (core/close-episode s {:episode (:episode result)
                             :summary (str "code-analysis pass: " files " files, "
                                           (count facts) " facts, "
                                           (count stale) " invalidated, ref " ref)})
      (assoc result :files files :ref ref :invalidated (count stale)))))
