(ns claimgraph.ingest.kotlin-code
  "The Kotlin analyzer adapter: an internal line parse — the deliberate
  exception to \"shell out to the language's own tooling\"
  (docs/language-adapters.md §2), because the grammar is rigid (one
  `package` declaration, `import a.b.C` lines before any code) and no
  maintained import-graph CLI exists in that ecosystem.

  Unit = <package>.<file-stem>, file-grained per spec §3 (the top-level
  class conventionally matches the stem; multi-class files just get a
  stem-named unit — consistent, less pretty, bridgeable with entity
  aliases). Import resolution is best-effort and safe-by-construction:
  exact unit match -> local edge; `import a.b.*` -> an edge to every local
  unit in package a.b; member imports strip trailing segments until a local
  unit matches; anything unmatched -> `external:` — a heuristic miss
  creates an external-scoped fact, never a wrong local edge."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Pure: source text -> package + imports, and import resolution
;; ---------------------------------------------------------------------------

(def ^:private package-re #"\s*package\s+([A-Za-z_][\w.]*)\s*;?\s*")
(def ^:private import-re #"\s*import\s+([A-Za-z_][\w.]*(?:\.\*)?)(?:\s+as\s+\w+)?\s*;?\s*")

(defn analyze-source
  "Pure: one file's text -> {:package \"a.b\"|nil :imports [\"a.b.C\" ...]}.
  Wildcard imports keep their `.*`; `as` aliases are dropped (the target is
  what matters for the dependency edge)."
  [content]
  (reduce (fn [acc line]
            (if-let [[_ pkg] (re-matches package-re line)]
              (update acc :package #(or % pkg))
              (if-let [[_ imp] (re-matches import-re line)]
                (update acc :imports conj imp)
                acc)))
          {:package nil :imports []}
          (str/split-lines (str content))))

(defn resolve-import
  "Pure: one import against the local unit set and the package -> units
  index -> a vector of unit names and/or `external:`-prefixed misses."
  [imp units-by-name units-by-package]
  (if (str/ends-with? imp ".*")
    (let [pkg (subs imp 0 (- (count imp) 2))
          locals (sort (units-by-package pkg))]
      (if (seq locals) (vec locals) [(str "external:" imp)]))
    (loop [candidate imp]
      (cond
        (contains? units-by-name candidate) [candidate]
        (str/includes? candidate ".")
        (recur (subs candidate 0 (str/last-index-of candidate ".")))
        :else [(str "external:" imp)]))))

(defn resolve-units
  "Pure: parsed files (each {:unit :package :file :imports}) -> interchange
  units, imports resolved against the whole set."
  [parsed]
  (let [by-name (set (map :unit parsed))
        by-package (reduce (fn [m {:keys [package unit]}]
                             (update m (or package "") (fnil conj #{}) unit))
                           {} parsed)]
    (mapv (fn [{:keys [unit file imports]}]
            {:unit unit
             :file file
             :language "kotlin"
             :requires (->> imports
                            (mapcat #(resolve-import % by-name by-package))
                            (remove #(= % unit))
                            distinct
                            vec)})
          parsed)))

;; ---------------------------------------------------------------------------
;; Shell: the adapter's :analyze-fn
;; ---------------------------------------------------------------------------

(def ^:private ignore-re #"(^|/)(\.claimgraph|\.git|\.gradle|build|out)/")

(defn analyze-root
  "Walk root for Kotlin source files -> interchange units."
  [root]
  (let [root (fs/canonicalize root)]
    (->> (fs/glob root "**.kt")
         (remove #(re-find ignore-re (str (fs/relativize root %))))
         sort
         (mapv (fn [path]
                 (let [{:keys [package imports]} (analyze-source (slurp (str path)))
                       stem (str (fs/strip-ext (fs/file-name path)))]
                   {:unit (if package (str package "." stem) stem)
                    :package package
                    :file (str (fs/relativize root path))
                    :imports imports})))
         resolve-units)))
