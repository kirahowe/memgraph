(ns memgraph.ingest.clj-code
  "Mechanical (no-LLM) code-analysis ingester for Clojure codebases.
  Functional core / imperative shell: source-text analysis and fact
  derivation are pure (`analyze-source`, `analyses->facts`); only `analyze`
  touches the filesystem and git. Emits high-confidence :observation facts:

    <namespace> core/defined-in <file>
    <namespace> core/depends-on <required namespace>
    <file>      core/written-in \"clojure\"

  Re-running is idempotent (duplicates no-op); a ns moving files supersedes
  cleanly via defined-in's :one cardinality."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as e]))

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
  memgraph.core/ingest. Dependencies on namespaces outside the analyzed set
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
                   (remove #(re-find #"(^|/)(\.memgraph|node_modules|\.git|target)/"
                                     (str (fs/relativize root %))))
                   sort)
        analyses (keep (fn [path]
                         (some-> (analyze-source (slurp (str path)))
                                 (assoc :file (str (fs/relativize root path)))))
                       files)]
    {:ref (or (git-sha root) (str root))
     :files (count analyses)
     :facts (analyses->facts analyses scope)}))
