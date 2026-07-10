(ns memgraph.notes-test
  "Auto-memory notes ingester: the pure parts (managed-section stripping,
  hashing, delta planning, clamping) as plain functions, and the shell
  end-to-end over a temp notes directory with an injected :extractor-fn —
  no LLM, no subprocess, no real ~/.claude."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [memgraph.core :as core]
            [memgraph.harness :as harness]
            [memgraph.ingest.notes :as notes]
            [memgraph.store :as store]
            [memgraph.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Pure: harness expectations
;; ---------------------------------------------------------------------------

(deftest project-path-munging
  (is (= "-home-kira-my-app" (harness/munge-project-path "/home/kira/my_app")))
  (is (= "-Users-k-dev-memory" (harness/munge-project-path "/Users/k/dev/memory"))))

(deftest claude-code-notes-dir
  (let [dir ((get-in harness/harnesses [:claude-code :notes-dir])
             "/home/kira" "/home/kira/dev/memory")]
    (is (= "/home/kira/.claude/projects/-home-kira-dev-memory/memory" dir))))

(deftest unknown-harness-fails-with-supported-list
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown harness"
                        (harness/resolve-harness "codex"))))

(deftest managed-section-stripping
  (testing "content without markers passes through untouched"
    (is (= "# Notes\nplain" (harness/strip-managed-section "# Notes\nplain"))))
  (testing "the delimited section is removed, markers included"
    (is (= "before\nafter"
           (harness/strip-managed-section
            (str "before\n" harness/begin-marker
                 "\ncompiled view\n" harness/end-marker "after")))))
  (testing "an unterminated section strips to EOF — never re-consume our own view"
    (is (= "before\n"
           (harness/strip-managed-section
            (str "before\n" harness/begin-marker "\ncompiled view, no end"))))))

;; ---------------------------------------------------------------------------
;; Pure: delta detection
;; ---------------------------------------------------------------------------

(deftest hash-ignores-the-managed-section
  (let [note "# Notes\nreal content\n"
        with-section (str note harness/begin-marker "\nview\n" harness/end-marker)]
    (is (= (notes/content-hash (harness/strip-managed-section note))
           (notes/content-hash (harness/strip-managed-section with-section)))
        "a compile-context rewrite alone must not re-trigger ingestion")))

(deftest delta-plan-from-episode-refs
  (let [eps [{:source-type :agent-note :ref "claude-code:MEMORY.md@abc123def456"}
             {:source-type :agent-note :ref "claude-code:debugging.md@111111111111"}
             {:source-type :session-log :ref "claude-code:MEMORY.md@zzzzzzzzzzzz"}
             {:source-type :agent-note :ref "codex:MEMORY.md@ffffffffffff"}]
        seen (notes/seen-hashes eps :claude-code)]
    (is (= {"MEMORY.md" #{"abc123def456"}
            "debugging.md" #{"111111111111"}}
           seen)
        "only agent-note episodes of the same harness count")
    (is (= [{:path "MEMORY.md" :hash "new000000000"}]
           (mapv #(select-keys % [:path :hash])
                 (notes/changed-notes
                  [{:path "MEMORY.md" :hash "new000000000"}
                   {:path "debugging.md" :hash "111111111111"}]
                  seen)))
        "unchanged files are skipped, changed ones re-ingest")))

;; ---------------------------------------------------------------------------
;; Pure: clamping
;; ---------------------------------------------------------------------------

(deftest note-facts-are-inference-grade
  (let [{:keys [facts demoted rejected]}
        (notes/prepare-note-facts
         [{:subject "api" :predicate "decided_against" :object "GraphQL"
           :class "commitment" :confidence 0.95}
          {:subject "AuthService" :predicate "prefers" :object "Result types"
           :class "preference" :confidence 0.9}
          {:subject "db" :predicate "depends-on" :object "Redis"}
          {:subject "" :predicate "prefers" :object "x"}])]
    (is (= 3 (count facts)))
    (is (= 1 (count rejected)))
    (testing "notes never mint commitments — reported decisions demote to observation"
      (is (= 1 demoted))
      (is (= :observation (:epistemic (first facts))))
      (is (nil? (:class (first facts))) "the raw class never leaks past the clamp"))
    (testing "preferences survive as preferences"
      (is (= :preference (:epistemic (second facts)))))
    (testing "missing class is an observation, not the predicate's default"
      (is (= :observation (:epistemic (nth facts 2)))))
    (testing "confidence caps at 0.65, defaults below it, source-type forced"
      (is (= [0.65 0.65 0.55] (mapv :confidence facts)))
      (is (every? #(= :agent-note (:source-type %)) facts)))))

;; ---------------------------------------------------------------------------
;; Shell: end-to-end over a temp notes dir
;; ---------------------------------------------------------------------------

(defn- temp-notes-dir []
  (str (fs/create-temp-dir {:prefix "memgraph-notes-test"})))

(deftest ingest-notes-end-to-end
  (let [dir (temp-notes-dir)
        s (mem/create)
        _ (core/seed! s)
        response (str/join "\n"
                           ["{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\",\"confidence\":0.9}"
                            "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\"}"])
        prompts (atom [])
        run! (fn [& [opts]]
               (notes/ingest! s (merge {:dir dir
                                        :extractor-fn (fn [p] (swap! prompts conj p) response)}
                                       opts)))]
    (spit (str dir "/MEMORY.md") "# Notes\nWe prefer Result types. Rejected GraphQL.\n")
    (spit (str dir "/debugging.md") "dev server port 3021 in this worktree\n")

    (testing "dry-run extracts changed files but writes nothing"
      (let [r (run! {:dry-run true})]
        (is (= :dry-run (:status r)))
        (is (= 2 (:files-scanned r)))
        (is (= 2 (:files-changed r)))
        (is (zero? (get-in (core/stats s) [:facts :total])))))

    (testing "first real pass ingests every note under per-file agent-note episodes"
      (reset! prompts [])
      (let [r (run!)]
        (is (= :ok (:status r)))
        (is (= 2 (:files-changed r)))
        (is (= 2 (get-in r [:counts :created])))
        (is (= 2 (get-in r [:counts :reinforced]))
            "the second file restates the same facts — they reinforce, not duplicate")
        (is (= 1 (:demoted (first (:files r))))
            "the reported decision was demoted, not minted as a commitment"))
      (let [{:keys [facts]} (core/get-facts s {:entity "api-layer"})]
        (is (= :core/decided-against (:predicate (first facts))))
        (is (= :observation (:epistemic (first facts)))
            "note-derived decisions land as observations")
        (is (= :agent-note (:source-type (first facts))))
        (is (= 0.55 (:confidence (first facts))) "missing confidence -> notes default"))
      (testing "episodes carry file@hash provenance and close mechanically"
        (let [eps (filter #(= :agent-note (:source-type %))
                          (store/-list-episodes s))]
          (is (= 2 (count eps)))
          (is (every? #(re-matches #"claude-code:[^@]+@[0-9a-f]{12}" (:ref %)) eps))
          (is (every? :closed-at eps)))))

    (testing "an unchanged pass extracts nothing"
      (reset! prompts [])
      (let [r (run!)]
        (is (zero? (:files-changed r)))
        (is (empty? @prompts) "the extractor never runs on unchanged notes")))

    (testing "appending a managed section alone does not re-trigger ingestion"
      (spit (str dir "/MEMORY.md")
            (str "# Notes\nWe prefer Result types. Rejected GraphQL.\n"
                 harness/begin-marker "\ncompiled view\n" harness/end-marker)
            :append false)
      (let [r (run!)]
        (is (zero? (:files-changed r)))))

    (testing "a real edit re-ingests just that file, and restatement reinforces"
      (spit (str dir "/MEMORY.md")
            "# Notes\nWe prefer Result types. Rejected GraphQL. Also: new fact.\n")
      (reset! prompts [])
      (let [r (run!)]
        (is (= 1 (:files-changed r)))
        (is (= 2 (get-in r [:counts :reinforced]))
            "identical facts from the re-extracted note reinforce, not duplicate")
        (is (str/includes? (first @prompts) "MEMORY.md") "prompt names the file")
        (is (str/includes? (first @prompts) "core/decided-against")
            "prompt carries the vocabulary")))

    (testing "missing notes dir degrades gracefully"
      (let [r (notes/ingest! s {:dir (str dir "/nope") :extractor-fn (fn [_] "")})]
        (is (= :no-notes-dir (:status r)))))))
