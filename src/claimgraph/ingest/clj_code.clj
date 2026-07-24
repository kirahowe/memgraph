(ns claimgraph.ingest.clj-code
  "The Clojure analyzer adapter (docs/language-adapters.md): edamame ns-form
  parsing — internal, free, and exact, no external tooling. The
  language-agnostic driver (adapter registry, fact derivation, the
  reconciliation plan, the pass itself) lives in claimgraph.ingest.code;
  this namespace turns source text into interchange units, plus thin
  delegating wrappers preserving the original single-language surface for
  its existing callers (the bench, older scripts)."
  (:require [babashka.fs :as fs]
            [edamame.core :as e]))

;; ---------------------------------------------------------------------------
;; Pure: source text -> analysis
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

;; ---------------------------------------------------------------------------
;; Shell: the adapter's :analyze-fn
;; ---------------------------------------------------------------------------

(def ^:private ignore-re #"(^|/)(\.claimgraph|node_modules|\.git|target)/")

(defn analyze-root
  "Walk root for Clojure source files -> interchange units (spec §3):
  one map per file with a parseable ns form."
  [root]
  (let [root (fs/canonicalize root)]
    (->> (fs/glob root "**.{clj,cljc,cljs,bb}")
         (remove #(re-find ignore-re (str (fs/relativize root %))))
         sort
         (keep (fn [path]
                 (when-let [{:keys [ns requires]} (analyze-source (slurp (str path)))]
                   {:unit (str ns)
                    :file (str (fs/relativize root path))
                    :requires (mapv str requires)
                    :language "clojure"})))
         vec)))

;; ---------------------------------------------------------------------------
;; Back-compat wrappers: the original public surface, delegating to the
;; driver. requiring-resolve avoids a load cycle — the driver requires this
;; namespace for its :analyze-fn.
;; ---------------------------------------------------------------------------

(defn analyses->facts
  "Pure: analyses (each {:ns :file :requires}) -> fact maps ready for
  claimgraph.core/ingest. Dependencies on namespaces outside the analyzed set
  are scoped \"external\". Delegates to the driver's units->facts."
  [analyses scope]
  ((requiring-resolve 'claimgraph.ingest.code/units->facts)
   (mapv (fn [{:keys [ns file requires]}]
           {:unit (str ns) :file file
            :requires (mapv str requires)
            :language "clojure" :unit-type :namespace})
         analyses)
   scope))

(defn stale-facts
  "Pure reconciliation plan: ids of currently-valid code-sourced facts (in
  this ingest's scopes) that the new analysis no longer produces. Delegates
  to the driver's stale-facts."
  [facts new-facts opts]
  ((requiring-resolve 'claimgraph.ingest.code/stale-facts) facts new-facts opts))

(defn ingest!
  "One Clojure-only code-analysis pass — the driver's ingest! filtered to
  this adapter (reconciliation stays scoped to Clojure-attributed facts).
  New callers should use claimgraph.ingest.code/ingest!, which runs every
  detected language in one pass."
  [s opts]
  ((requiring-resolve 'claimgraph.ingest.code/ingest!)
   s (assoc opts :language :clojure)))
