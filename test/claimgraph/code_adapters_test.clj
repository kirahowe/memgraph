(ns claimgraph.code-adapters-test
  "The language-adapter registry and the ambient code-freshness stage
  (docs/language-adapters.md): interchange parsing, the Kotlin line parse
  and its resolution heuristics, the TypeScript parse over canned
  dependency-cruiser output (no npx anywhere near the suite — the command
  seam is injected), registry merging with the code-analyzers config,
  language-guarded reconciliation, and the pure delta gate."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.ingest.code :as code]
            [claimgraph.ingest.kotlin-code :as kotlin]
            [claimgraph.ingest.ts-code :as ts]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(defn- temp-dir [] (fs/create-temp-dir {:prefix "claimgraph-code-adapters-test"}))

;; ---------------------------------------------------------------------------
;; Pure: the interchange format
;; ---------------------------------------------------------------------------

(deftest interchange-accepts-jsonl-and-array
  (let [expected [{:unit "a" :file "a.rs" :requires ["b" "external:serde"]}
                  {:unit "b" :file "b.rs"}]]
    (is (= expected
           (code/parse-interchange
            "{\"unit\":\"a\",\"file\":\"a.rs\",\"requires\":[\"b\",\"external:serde\"]}\n\n{\"unit\":\"b\",\"file\":\"b.rs\"}")))
    (is (= expected
           (code/parse-interchange
            "[{\"unit\":\"a\",\"file\":\"a.rs\",\"requires\":[\"b\",\"external:serde\"]},{\"unit\":\"b\",\"file\":\"b.rs\"}]")))
    (is (= [] (code/parse-interchange "   ")))))

(deftest units->facts-resolves-and-scopes
  (let [facts (code/units->facts
               [{:unit "src/index" :file "src/index.ts" :language "typescript"
                 :unit-type :module
                 :requires ["src/auth" "external:react" "src/vanished"]}
                {:unit "src/auth" :file "src/auth.ts" :language "typescript"
                 :unit-type :module :requires []}]
               "code")
        dep-scopes (->> facts
                        (filter #(= :core/depends-on (:predicate %)))
                        (map (juxt :object :scope))
                        (into {}))]
    (is (= "code" (dep-scopes "src/auth")) "deps inside the emitted unit set keep the scope")
    (is (= "external" (dep-scopes "react")) "the external: prefix is stripped and scoped")
    (is (= "external" (dep-scopes "src/vanished"))
        "an unmatched unprefixed require is external — never a wrong local edge")
    (is (= #{:module :file} (set (map :subject-type facts))))
    (is (= ["typescript" "typescript"]
           (map :object (filter #(= :core/written-in (:predicate %)) facts))))))

;; ---------------------------------------------------------------------------
;; Kotlin: line parse + resolution heuristics, inline source
;; ---------------------------------------------------------------------------

(deftest kotlin-line-parse-is-pure
  (is (= {:package "com.app.auth"
          :imports ["com.app.db.Db" "com.app.util.*" "java.time.Instant"]}
         (kotlin/analyze-source
          (str "// auth\npackage com.app.auth\n\n"
               "import com.app.db.Db\n"
               "import com.app.util.*\n"
               "import java.time.Instant as I\n\n"
               "class Auth {}\n")))
      "package decl, plain/wildcard/aliased imports")
  (is (= {:package nil :imports []} (kotlin/analyze-source "class Bare {}"))
      "the default package: no decl, no imports")
  (is (= {:package "a.b" :imports []} (kotlin/analyze-source "package a.b;\nclass X"))
      "an optional semicolon is tolerated"))

(deftest kotlin-resolution-is-safe-by-construction
  (let [parsed [{:unit "com.app.Auth" :package "com.app" :file "Auth.kt"
                 :imports ["com.app.db.Db"           ; exact unit match
                           "com.app.util.*"          ; wildcard -> whole package
                           "com.app.db.Db.Companion" ; member import, stripped
                           "org.junit.Test"]}        ; unresolvable
                {:unit "com.app.db.Db" :package "com.app.db" :file "Db.kt" :imports []}
                {:unit "com.app.util.Strings" :package "com.app.util" :file "S.kt" :imports []}
                {:unit "com.app.util.Nums" :package "com.app.util" :file "N.kt" :imports []}]
        [auth] (kotlin/resolve-units parsed)]
    (is (= ["com.app.db.Db" "com.app.util.Nums" "com.app.util.Strings"
            "external:org.junit.Test"]
           (:requires auth))
        "exact -> local; wildcard -> every unit in the package; member import strips to the unit; a miss is external-scoped"))
  (is (= ["external:x.y.*"]
         (:requires (first (kotlin/resolve-units
                            [{:unit "a.A" :package "a" :file "A.kt"
                              :imports ["x.y.*"]}]))))
      "a wildcard over no local package is an external fact, not silence"))

;; ---------------------------------------------------------------------------
;; TypeScript: the pinned parse over canned dependency-cruiser output
;; ---------------------------------------------------------------------------

(def depcruise-json
  "A canned dependency-cruiser@16 --output-type json payload: one local
  edge, an npm package, a core module, an unresolvable import, and a
  node_modules module entry that must not become a unit."
  (json/generate-string
   {:modules [{:source "src/index.ts"
               :dependencies [{:resolved "src/auth/login.ts" :module "./auth/login"
                               :coreModule false :couldNotResolve false
                               :dependencyTypes ["local" "import"]}
                              {:resolved "node_modules/react/index.js" :module "react"
                               :coreModule false :couldNotResolve false
                               :dependencyTypes ["npm"]}
                              {:resolved "fs" :module "fs"
                               :coreModule true :couldNotResolve false
                               :dependencyTypes ["core"]}
                              {:resolved "./missing" :module "./missing"
                               :coreModule false :couldNotResolve true
                               :dependencyTypes ["unknown"]}]}
              {:source "src/auth/login.ts" :dependencies []}
              {:source "node_modules/react/index.js" :dependencies []}]}))

(deftest ts-parse-maps-dependency-cruiser-output
  (let [units (ts/parse depcruise-json)]
    (is (= ["src/auth/login" "src/index"] (sort (map :unit units)))
        "units are repo-relative paths sans extension; node_modules never become units")
    (is (= ["src/auth/login" "external:react" "external:fs" "external:./missing"]
           (:requires (first (filter #(= "src/index" (:unit %)) units))))
        "npm / core / unresolvable all map to external:, keeping the written specifier")))

;; ---------------------------------------------------------------------------
;; The registry and the code-analyzers config seam
;; ---------------------------------------------------------------------------

(deftest registry-honors-the-code-analyzers-config
  (let [r (code/registry {:typescript false
                          :kotlin {:detect "**.{kt,kts}"}
                          :rust {:command "cargo run -q --bin deps <roots>"
                                 :detect "**.rs"}})
        by-id (into {} (map (juxt :id identity)) r)]
    (testing "a built-in can be disabled outright"
      (is (nil? (by-id :typescript))))
    (testing "overriding one field keeps the rest of the built-in"
      (is (= "**.{kt,kts}" (:detect (by-id :kotlin))))
      (is (fn? (:analyze-fn (by-id :kotlin)))))
    (testing "a new language defaults to interchange parsing"
      (let [rust (by-id :rust)]
        (is (= "rust" (:language rust)))
        (is (= :module (:unit-type rust)))
        (is (= [{:unit "m" :file "m.rs"}]
               ((:parse rust) "{\"unit\":\"m\",\"file\":\"m.rs\"}"))))))
  (testing "overriding a built-in with a :command replaces its internal analyzer"
    (let [clj (first (filter #(= :clojure (:id %))
                             (code/registry {:clojure {:command "my-analyzer <roots>"}})))]
      (is (nil? (:analyze-fn clj)))
      (is (= "my-analyzer <roots>" (:command clj))))))

(deftest detection-walks-the-root-and-honors-ignores
  (let [dir (temp-dir)]
    (try
      (fs/create-dirs (fs/path dir "node_modules" "pkg"))
      (fs/create-dirs (fs/path dir "src" "main" "kotlin"))
      (fs/create-dirs (fs/path dir "deep" "nested"))
      (spit (str (fs/path dir "node_modules" "pkg" "index.ts")) "export {};")
      (spit (str (fs/path dir "src" "main" "kotlin" "App.kt")) "package app\nclass App")
      (spit (str (fs/path dir "deep" "nested" "core.clj")) "(ns deep.core)")
      (is (= [:clojure :kotlin] (mapv :id (code/detect dir)))
          "kotlin found under src/main/kotlin (no hardcoded src/); node_modules never triggers typescript")
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; The pass: multi-language, injectable command seam, degradation
;; ---------------------------------------------------------------------------

(deftest multi-language-pass-is-one-episode
  (let [dir (temp-dir)
        s (mem/create)]
    (try
      (core/seed! s)
      (fs/create-dirs (fs/path dir "src" "main" "kotlin"))
      (spit (str (fs/path dir "app.clj")) "(ns app.core)")
      (spit (str (fs/path dir "src" "main" "kotlin" "Auth.kt"))
            "package com.app\n\nimport com.app.Db\n\nclass Auth {}\n")
      (spit (str (fs/path dir "src" "main" "kotlin" "Db.kt"))
            "package com.app\n\nclass Db {}\n")
      (let [r (code/ingest! s {:dir (str dir)})]
        (is (= :ok (:status r)))
        (is (= 3 (:files r)))
        (is (= [:clojure :kotlin] (mapv :id (:analyzers r))))
        (is (= 1 (count (filter #(= :code (:source-type %))
                                (store/-list-episodes s))))
            "every detected adapter runs in ONE pass under ONE episode"))
      (testing "facts are distinguished by written-in and entity types"
        (is (= "clojure" (:object-lit (first (:facts (core/get-facts
                                                      s {:entity "app.clj"
                                                         :predicate :core/written-in}))))))
        (is (= "kotlin" (:object-lit (first (:facts (core/get-facts
                                                     s {:entity "src/main/kotlin/Auth.kt"
                                                        :predicate :core/written-in}))))))
        (is (= "com.app.Db"
               (get-in (first (:facts (core/get-facts s {:entity "com.app.Auth"
                                                         :predicate :core/depends-on})))
                       [:object-ref :name]))))
      (finally (fs/delete-tree dir)))))

(defn- spit-ts-project! [dir]
  (fs/create-dirs (fs/path dir "src" "auth"))
  (spit (str (fs/path dir "src" "index.ts")) "import {login} from './auth/login';")
  (spit (str (fs/path dir "src" "auth" "login.ts")) "export const login = () => {};"))

(deftest ts-adapter-runs-through-the-injectable-command-seam
  (let [dir (temp-dir)
        s (mem/create)
        calls (atom [])]
    (try
      (core/seed! s)
      (spit-ts-project! dir)
      (let [r (code/ingest! s {:dir (str dir)
                               :which (fn [_] "npx")
                               :command-fn (fn [call] (swap! calls conj call) depcruise-json)})]
        (is (= :ok (:status r)))
        (is (= 1 (count @calls)))
        (is (str/includes? (:command (first @calls)) "dependency-cruiser@16")
            "the pinned command is what shells out")
        (is (str/ends-with? (:command (first @calls)) " src")
            "<roots> is replaced with the detected top-level source roots")
        (let [scopes (->> (:facts (core/get-facts s {:entity "src/index"
                                                     :predicate :core/depends-on}))
                          (map (juxt #(get-in % [:object-ref :name]) :scope))
                          (into {}))]
          (is (= "code" (scopes "src/auth/login")))
          (is (= "external" (scopes "react")))))
      (finally (fs/delete-tree dir)))))

(deftest missing-tooling-skips-and-never-invalidates
  (let [dir (temp-dir)
        s (mem/create)]
    (try
      (core/seed! s)
      (spit-ts-project! dir)
      (spit (str (fs/path dir "app.clj")) "(ns app.core (:require [app.other]))")
      (spit (str (fs/path dir "other.clj")) "(ns app.other)")
      ;; first pass: npx "present" (canned), both languages land
      (code/ingest! s {:dir (str dir)
                       :which (fn [_] "npx")
                       :command-fn (fn [_] depcruise-json)})
      ;; second pass: npx gone, and the clj require was dropped
      (spit (str (fs/path dir "app.clj")) "(ns app.core)")
      (let [r (code/ingest! s {:dir (str dir) :which (fn [_] nil)})
            ts-run (first (filter #(= :typescript (:id %)) (:analyzers r)))]
        (is (= :ok (:status r)) "a skipped adapter degrades the pass, never errors it")
        (is (= :skipped (:status ts-run)))
        (is (str/includes? (:hint ts-run) "npx") "the skip carries the fix")
        (is (seq (:facts (core/get-facts s {:entity "src/index"
                                            :predicate :core/depends-on})))
            "facts of the language that was NOT analyzed survive reconciliation")
        (is (empty? (:facts (core/get-facts s {:entity "app.core"
                                               :predicate :core/depends-on})))
            "the language that did run still reconciles"))
      (finally (fs/delete-tree dir)))))

(deftest analyzer-failure-is-partial-and-isolated
  (let [dir (temp-dir)
        s (mem/create)]
    (try
      (core/seed! s)
      (spit-ts-project! dir)
      (spit (str (fs/path dir "app.clj")) "(ns app.core)")
      (let [r (code/ingest! s {:dir (str dir)
                               :which (fn [_] "npx")
                               :command-fn (fn [_] (throw (ex-info "boom" {})))})]
        (is (= :partial (:status r)))
        (is (str/ends-with? (:ref r) "+partial")
            "a partial pass never satisfies the delta gate — it retries next run")
        (is (= :error (:status (first (filter #(= :typescript (:id %)) (:analyzers r))))))
        (is (seq (:facts (core/get-facts s {:entity "app.core"
                                            :predicate :core/defined-in})))
            "the healthy analyzer's facts still land"))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; The delta gate: pure, plus one real git round-trip
;; ---------------------------------------------------------------------------

(deftest the-delta-gate-is-pure
  (let [sha-a (apply str (repeat 40 "a"))
        sha-b (apply str (repeat 40 "b"))
        ep (fn [ref t] {:source-type :code :ref ref :opened-at (java.util.Date. (long t))})]
    (is (false? (code/code-stale? sha-a [(ep sha-a 0)])) "same ref -> skip")
    (is (true? (code/code-stale? sha-a [(ep sha-b 0)])) "moved HEAD -> stale")
    (is (true? (code/code-stale? (str sha-a "+abcdef123456") [(ep sha-a 0)]))
        "uncommitted edits move the dirty digest -> stale")
    (is (false? (code/code-stale? (str sha-a "+abcdef123456")
                                  [(ep (str sha-a "+abcdef123456") 0)])))
    (is (true? (code/code-stale? "/tmp/project" [(ep "/tmp/project" 0)]))
        "a non-git path ref is ALWAYS stale (manual semantics)")
    (is (true? (code/code-stale? sha-a [(ep (str sha-a "+partial") 0)]))
        "a partial pass never holds the gate")
    (is (true? (code/code-stale? sha-a [])) "no :code episode yet -> stale")
    (is (false? (code/code-stale? sha-a [(ep sha-b 0) (ep sha-a 1000)]))
        "the NEWEST :code episode is the state")))

(defn- git! [dir & args]
  (let [{:keys [exit err]} (apply p/sh {:dir (str dir)}
                                  "git" "-c" "user.email=t@test" "-c" "user.name=t" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (first args) " failed: " err) {})))))

(deftest gate-round-trip-against-a-real-git-repo
  (let [dir (temp-dir)
        s (mem/create)]
    (try
      (core/seed! s)
      (spit (str (fs/path dir "a.clj")) "(ns app.a)")
      (git! dir "init" "-q")
      (git! dir "add" "-A")
      (git! dir "commit" "-q" "-m" "x")
      (let [r1 (code/ingest-if-changed! s {:dir (str dir)})]
        (is (= :ok (:status r1)) "first run reconciles")
        (is (code/git-ref? (:ref r1))))
      (let [r2 (code/ingest-if-changed! s {:dir (str dir)})]
        (is (= :skipped (:status r2)) "same ref skips on the gate")
        (is (= "code unchanged since the last pass" (:reason r2))))
      (spit (str (fs/path dir "a.clj")) "(ns app.a (:require [clojure.set]))")
      (let [r3 (code/ingest-if-changed! s {:dir (str dir)})]
        (is (= :ok (:status r3)) "an uncommitted edit moves the dirty digest and re-runs")
        (is (str/includes? (:ref r3) "+") "the ref carries the dirty digest"))
      (finally (fs/delete-tree dir)))))
