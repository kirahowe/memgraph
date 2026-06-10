(ns memgraph.logic-test
  "The payoff of the functional core: assertion decisions, decay plans, and
  BFS folds tested as plain functions over values — no store, no clock, no
  fixtures."
  (:require [clojure.test :refer [deftest is testing]]
            [memgraph.logic :as logic]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def t1 #inst "2026-06-01T00:00:00Z")

(def version-pred {:id :core/has-version :cardinality :one :object-kind :literal})

(defn- candidate [overrides]
  (logic/build-fact (merge {:id "f-new" :now t1
                            :subject {:id "e1" :name "svc"}
                            :predicate :core/has-version
                            :object-kind :literal :object "2.0"
                            :epistemic :observation}
                           overrides)))

(def existing-v1
  {:id "f-old" :predicate :core/has-version :object-kind :literal
   :object-lit "1.0" :epistemic :observation :t-valid t0 :confidence 0.8})

(deftest decide-assert-is-a-pure-function
  (testing "no existing facts -> insert"
    (is (= :insert (:action (logic/decide-assert {:fact (candidate {}) :pred version-pred
                                                  :existing []})))))
  (testing "same object -> noop, returning the existing fact"
    (let [d (logic/decide-assert {:fact (candidate {:object "1.0"}) :pred version-pred
                                  :existing [existing-v1]})]
      (is (= :noop (:action d)))
      (is (= "f-old" (get-in d [:existing :id])))))
  (testing "observation conflict -> supersede plan naming the losers"
    (let [d (logic/decide-assert {:fact (candidate {}) :pred version-pred
                                  :existing [existing-v1]})]
      (is (= :supersede (:action d)))
      (is (= ["f-old"] (:invalidate d)))))
  (testing "commitment on either side -> flag with candidates"
    (is (= :flag (:action (logic/decide-assert
                           {:fact (candidate {:epistemic :commitment}) :pred version-pred
                            :existing [existing-v1]}))))
    (is (= :flag (:action (logic/decide-assert
                           {:fact (candidate {}) :pred version-pred
                            :existing [(assoc existing-v1 :epistemic :commitment)]})))))
  (testing "caller override wins"
    (is (= :supersede (:action (logic/decide-assert
                                {:fact (candidate {:epistemic :commitment}) :pred version-pred
                                 :existing [existing-v1] :on-conflict :supersede}))))
    (is (= :insert (:action (logic/decide-assert
                             {:fact (candidate {}) :pred version-pred
                              :existing [existing-v1] :on-conflict :ignore})))))
  (testing "many-cardinality predicates never conflict"
    (is (= :insert (:action (logic/decide-assert
                             {:fact (candidate {}) :pred (assoc version-pred :cardinality :many)
                              :existing [existing-v1]}))))))

(deftest decay-plan-is-data
  (let [old #inst "2025-06-01T00:00:00Z"
        facts [{:id "stale" :epistemic :observation :source-type :inferred
                :t-valid old :recorded-at old :confidence 0.8}
               {:id "commit" :epistemic :commitment :source-type :user-assertion
                :t-valid old :recorded-at old :confidence 0.9}
               {:id "fresh" :epistemic :observation :source-type :code
                :t-valid t1 :recorded-at t1 :confidence 0.95}]
        plan (logic/decay-plan facts {:now t1 :older-than-days 90 :factor 0.5})]
    (is (= [{:fact-id "stale" :confidence 0.4}] plan)
        "only stale non-commitments decay; the plan is just data")))

(deftest bfs-step-folds-purely
  (let [a {:id "ea" :name "A"} b {:id "eb" :name "B"}
        fact {:id "f1" :subject a :object-ref b :object-kind :entity
              :t-valid t0 :confidence 0.9}
        state {:nodes {"ea" (assoc a :depth 0)} :edges {} :frontier #{"ea"}}
        next-state (logic/bfs-step state [fact] (logic/fact-filter {:at t1}) 1)]
    (is (= #{"eb"} (:frontier next-state)))
    (is (= 1 (get-in next-state [:nodes "eb" :depth])))
    (is (contains? (:edges next-state) "f1"))
    (testing "already-seen facts and nodes are not re-added"
      (let [again (logic/bfs-step next-state [fact] (logic/fact-filter {:at t1}) 2)]
        (is (empty? (:frontier again)))))))

(deftest open-conflicts-pairs-valid-facts
  (let [facts [{:id "f-new" :conflicts ["f-old" "f-dead" "f-missing"]
                :t-valid t0 :confidence 0.8}
               {:id "f-old" :t-valid t0 :confidence 0.8}
               {:id "f-dead" :t-valid t0 :t-invalid t1 :confidence 0.8}]]
    (is (= [{:fact "f-new" :candidate "f-old"}]
           (mapv #(-> % (update :fact :id) (update :candidate :id))
                 (logic/open-conflicts facts #inst "2026-12-01")))
        "invalidated and missing candidates drop out")
    (is (empty? (logic/open-conflicts facts #inst "2025-01-01"))
        "nothing is in conflict before the facts are valid")))

(deftest normalization
  (is (= {:object-kind "entity"} (logic/normalize-keys {:object_kind "entity"})))
  (is (= {:epistemic "preference"}
         (logic/normalize-ingest-fact {:class "preference"}))
      ":class is an accepted alias for :epistemic")
  (is (= {:epistemic :commitment}
         (logic/normalize-ingest-fact {:epistemic :commitment :class "preference"}))
      "explicit :epistemic wins over :class"))
