(ns memgraph.coach-test
  "The gated push: the gate stays shut for ordinary tasks and fires only
  when standing decisions, failure modes, or open conflicts touch the task.
  In-memory store, no LLM."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [memgraph.coach :as coach]
            [memgraph.core :as core]
            [memgraph.store.memory :as mem]))

(deftest the-gate
  (let [s (doto (mem/create) (core/seed!))]
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    (core/assert-fact s {:subject "migration-runner" :predicate :core/failure-mode
                         :object "schema migrations without a backup lose data on rollback"
                         :object-kind :literal :source-type :session-log
                         :confidence 0.7})
    (core/assert-fact s {:subject "cache" :predicate :core/prefers
                         :object "write-through" :object-kind :literal})

    (testing "a task touching a standing decision fires the gate"
      (let [r (coach/consult s "add a graphql endpoint to the api-layer")]
        (is (:push r))
        (is (str/includes? (:briefing r) "standing decision"))
        (is (str/includes? (:briefing r) "GraphQL"))))

    (testing "a task near a known failure mode fires with the lesson"
      (let [r (coach/consult s "run the migration-runner against prod")]
        (is (:push r))
        (is (str/includes? (:briefing r) "known failure mode"))))

    (testing "an ordinary task stays uninterrupted — the gate is the feature"
      (let [r (coach/consult s "rename a local variable in the cache helper")]
        ;; the cache preference is a plain preference: retrievable, not
        ;; interruption-worthy
        (is (not (:push r)))
        (is (nil? (:briefing r)))))

    (testing "an open conflict on a touched entity fires loudly"
      (core/assert-fact s {:subject "api-layer" :predicate :core/prefers
                           :object "GraphQL" :object-kind :literal})
      (let [r (coach/consult s "api-layer query API work")]
        (is (:push r))
        (is (str/includes? (:briefing r) "OPEN CONFLICT"))))))

(deftest hook-adapter-shapes
  (is (= "make it faster" (coach/hook-input->query {:prompt "make it faster"})))
  (is (str/includes? (coach/hook-input->query {:tool_input {:file_path "/x/migrate.sql"
                                                            :count 3}})
                     "migrate.sql"))
  (is (nil? (coach/hook-output {:push false}))
      "silence when the gate holds — nothing reaches the agent's context")
  (is (= "the briefing"
         (get-in (coach/hook-output {:push true :briefing "the briefing"})
                 [:hookSpecificOutput :additionalContext]))))
