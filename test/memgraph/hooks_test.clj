(ns memgraph.hooks-test
  "The ambient loop's automation: install-plan as a pure function over
  settings maps, install! against a temp project, and the hooks-run pass with
  injected extractor/summarizer fns — no LLM, no subprocess, no real ~/.claude."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [memgraph.core :as core]
            [memgraph.harness :as harness]
            [memgraph.hooks :as hooks]
            [memgraph.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Pure: the settings merge
;; ---------------------------------------------------------------------------

(defn- entry [cmd] {:hooks [{:type "command" :command cmd}]})

(deftest install-plan-is-idempotent-and-preserves-neighbors
  (let [foreign (entry "echo bye")
        base {:permissions {:allow ["Bash(bb test)"]}
              :hooks {:SessionEnd [foreign]}}
        v1 (hooks/install-plan base :SessionEnd
                               (entry "memgraph hooks run --harness claude-code")
                               "hooks run")
        v2 (hooks/install-plan v1 :SessionEnd
                               (entry "memgraph hooks run --harness claude-code --consolidate-days 3")
                               "hooks run")]
    (testing "appends alongside foreign hooks, preserving everything else"
      (is (= 2 (count (get-in v1 [:hooks :SessionEnd]))))
      (is (= foreign (first (get-in v1 [:hooks :SessionEnd]))))
      (is (= {:allow ["Bash(bb test)"]} (:permissions v1))))
    (testing "re-install replaces our entry in place, never duplicates"
      (is (= 2 (count (get-in v2 [:hooks :SessionEnd]))))
      (is (= "memgraph hooks run --harness claude-code --consolidate-days 3"
             (-> v2 (get-in [:hooks :SessionEnd]) second :hooks first :command))))
    (testing "events are independent"
      (let [v3 (hooks/install-plan v2 :UserPromptSubmit
                                   (entry "memgraph coach --hook") "coach --hook")]
        (is (= 1 (count (get-in v3 [:hooks :UserPromptSubmit]))))
        (is (= 2 (count (get-in v3 [:hooks :SessionEnd]))))))))

;; ---------------------------------------------------------------------------
;; Shell: install! against a temp project
;; ---------------------------------------------------------------------------

(deftest install-writes-project-settings
  (let [project (str (fs/create-temp-dir {:prefix "memgraph-hooks-test"}))]
    (testing "fresh install creates .claude/settings.json"
      (let [r (hooks/install! {:project project})]
        (is (= :installed (:status r)))
        (is (= "memgraph hooks run --harness claude-code" (:command r)))
        (let [settings (json/parse-string (slurp (:settings r)) true)
              hook (-> settings :hooks :SessionEnd first :hooks first)]
          (is (= "command" (:type hook)))
          (is (= hooks/hook-timeout-seconds (:timeout hook))))))
    (testing "re-install updates in place"
      (let [r (hooks/install! {:project project :consolidate-days 3})]
        (is (= :updated (:status r)))
        (let [settings (json/parse-string (slurp (:settings r)) true)]
          (is (= 1 (count (get-in settings [:hooks :SessionEnd])))))))
    (testing "a repo-local bin/memgraph is auto-detected"
      (fs/create-dirs (fs/path project "bin"))
      (spit (str (fs/path project "bin" "memgraph")) "#!/bin/sh\n")
      (let [r (hooks/install! {:project project})]
        (is (= "bin/memgraph hooks run --harness claude-code" (:command r)))))))

;; ---------------------------------------------------------------------------
;; Shell: the SessionEnd pass
;; ---------------------------------------------------------------------------

(deftest hooks-run-drives-the-whole-loop
  (let [dir (str (fs/create-temp-dir {:prefix "memgraph-hooks-run-test"}))
        db (str dir "/db")
        s (mem/create)
        _ (core/seed! s)
        response "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\",\"confidence\":0.9}"
        base {:db db :dir dir
              :extractor-fn (fn [_] response)
              :summarize-fn (fn [_] "episode summary")
              :judge-fn (fn [_] "")}]
    (spit (str dir "/MEMORY.md") "# Notes\nprefers Result types\n")

    (testing "one pass: capture, recompile, consolidate (stamp absent = due)"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (= 1 (get-in r [:ingest-notes :files-changed])))
        (is (= :compiled (get-in r [:compile-context :status])))
        (is (= :consolidated (get-in r [:consolidate :status])))
        (is (fs/exists? (str db ".last-consolidate")))
        (is (str/includes? (slurp (str dir "/MEMORY.md")) harness/begin-marker)
            "the compiled view landed in the inject file")))

    (testing "within the window, consolidation is skipped"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (zero? (get-in r [:ingest-notes :files-changed])))
        (is (= :skipped (get-in r [:consolidate :status])))))

    (testing "--consolidate-days 0 forces the pass"
      (is (= :consolidated (get-in (hooks/run! s (assoc base :consolidate-days 0))
                                   [:consolidate :status]))))

    (testing "a broken extractor degrades to :partial — the recompile still runs"
      (spit (str dir "/MEMORY.md")
            (str (slurp (str dir "/MEMORY.md")) "\nnew durable note\n"))
      (let [r (hooks/run! s (assoc base :extractor-fn (fn [_] (throw (ex-info "no claude" {})))))]
        (is (= :partial (:status r)))
        (is (= :error (get-in r [:ingest-notes :status])))
        (is (= :compiled (get-in r [:compile-context :status]))
            "capture failing never blocks the deterministic view")))))
