(ns memgraph.ingest.clj-code
  "Mechanical (no-LLM) code-analysis ingester for Clojure codebases. Walks
  .clj/.cljc/.cljs/.bb files, parses the ns form with edamame, and emits
  high-confidence :observation facts under a :code episode:

    <namespace> core/defined-in <file>
    <namespace> core/depends-on <required namespace>
    <file>      core/written-in \"clojure\"

  This is the v0 of the ingestion tier that replaces the bulk of what people
  stuff into CLAUDE.md. Re-running is idempotent (duplicates no-op); a ns
  moving files supersedes cleanly via defined-in's :one cardinality."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as e]))

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

(defn analyze-file
  "Returns {:ns sym :file str :requires [sym]} or nil if no parseable ns form."
  [path root]
  (let [content (slurp (str path))
        ns-form (parse-ns-form content)]
    (when ns-form
      {:ns (second ns-form)
       :file (str (fs/relativize root path))
       :requires (vec (ns-requires ns-form))})))

(defn- git-sha [dir]
  (try
    (let [{:keys [exit out]} (p/sh {:dir (str dir)} "git" "rev-parse" "HEAD")]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn analyze
  "Walk dir for Clojure source files and produce fact maps ready for
  memgraph.core/ingest, plus the episode ref (git SHA when available)."
  [{:keys [dir scope]}]
  (let [root (fs/canonicalize (or dir "."))
        scope (or scope "code")
        files (->> (fs/glob root "**.{clj,cljc,cljs,bb}")
                   (remove #(re-find #"(^|/)(\.memgraph|node_modules|\.git|target)/"
                                     (str (fs/relativize root %))))
                   sort)
        analyses (keep #(analyze-file % root) files)
        local-nss (set (map :ns analyses))
        base {:scope scope :source-type :code :confidence 0.95 :epistemic :observation}
        facts (mapcat
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
                                 ;; external deps are real edges too, but mark them
                                 :scope (if (local-nss r) scope "external")}))))
               analyses)]
    {:ref (or (git-sha root) (str root))
     :files (count analyses)
     :facts (vec facts)}))
