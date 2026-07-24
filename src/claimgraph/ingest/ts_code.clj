(ns claimgraph.ingest.ts-code
  "The TypeScript/JavaScript analyzer adapter: shells out to
  dependency-cruiser — pinned at major 16, run via npx — instead of
  maintaining our own import parser (docs/language-adapters.md §2): the
  syntax surface (static/dynamic/type-only imports, `require`, re-exports,
  tsconfig path aliases, workspaces) is a moving target, every regex miss
  is a silent wrong fact, and the language's own tooling gets exactly the
  hard parts right. The prerequisite (npx, i.e. Node) is one a TS repo has
  by definition; when it's absent the adapter reports skipped with a hint,
  never an error.

  Only `parse` knows dependency-cruiser's output schema — the one pinned
  seam per upstream (the harness-registry rule). modules[].source +
  modules[].dependencies[] map to interchange units; unit name =
  repo-relative path sans extension; coreModule / couldNotResolve /
  node_modules classifications map to `external:` — a resolution miss
  becomes an external-scoped fact, never a wrong local edge."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def command
  "Version-pinned via npx: dependency-cruiser majors change the output
  schema (the expectations live in `parse` and nowhere else), and typescript
  rides along in the same npx prefix — dependency-cruiser only enables .ts
  parsing when a supported typescript (>=2 <6) is resolvable next to it,
  which a bare `npx dependency-cruiser` install is not. <roots> is replaced
  by the detected top-level source roots."
  "npx --yes -p typescript@5 -p dependency-cruiser@16 depcruise --no-config --output-type json <roots>")

(defn- unit-name
  "Repo-relative path sans extension: src/auth/login.ts -> src/auth/login."
  [source]
  (let [slash (or (str/last-index-of source "/") -1)
        dot (str/last-index-of source ".")]
    (if (and dot (> dot (inc slash)))
      (subs source 0 dot)
      source)))

(defn- external-dep? [d]
  (or (:coreModule d)
      (:couldNotResolve d)
      (str/includes? (str (:resolved d)) "node_modules")
      (some #(str/starts-with? (str %) "npm") (:dependencyTypes d))))

(defn- external-module? [m]
  (or (:coreModule m)
      (:couldNotResolve m)
      (str/includes? (str (:source m)) "node_modules")))

(defn parse
  "Pure: dependency-cruiser JSON (stdout) -> interchange units. External
  dependencies keep the written module specifier (\"react\", \"node:fs\")
  behind the external: prefix; local ones become unit names."
  [stdout]
  (let [{:keys [modules]} (json/parse-string (str stdout) true)]
    (->> modules
         (remove external-module?)
         (mapv (fn [m]
                 {:unit (unit-name (:source m))
                  :file (:source m)
                  :requires (->> (:dependencies m)
                                 (map (fn [d]
                                        (if (external-dep? d)
                                          (str "external:" (or (:module d) (:resolved d)))
                                          (unit-name (:resolved d)))))
                                 distinct
                                 vec)})))))
