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

(deftest loose-object-matching
  (is (logic/same-object-loosely?
       {:object-kind :entity :object-ref {:name "GraphQL"}}
       {:object-kind :literal :object-lit "graph-ql"})
      "entity and literal clothes, same object")
  (is (not (logic/same-object-loosely?
            {:object-kind :entity :object-ref {:name "GraphQL"}}
            {:object-kind :literal :object-lit "REST"}))))

(deftest decide-assert-exclusion-antagonists
  (let [pred {:id :core/decided-against :cardinality :many :object-kind :either}
        fact (logic/build-fact {:id "f-new" :now t1 :subject {:id "e1"}
                                :predicate :core/decided-against
                                :object-kind :literal :object "GraphQL"
                                :epistemic :commitment})
        standing {:id "f-pref" :predicate :core/prefers :object-kind :entity
                  :object-ref {:id "e9" :name "GraphQL"} :epistemic :preference
                  :t-valid t0 :confidence 0.8}]
    (testing "a many-cardinality predicate alone never conflicts"
      (is (= :insert (:action (logic/decide-assert
                               {:fact fact :pred pred :existing [] :exclusion []})))))
    (testing "an exclusion antagonist flags via epistemic composition"
      (let [d (logic/decide-assert {:fact fact :pred pred :existing []
                                    :exclusion [standing]})]
        (is (= :flag (:action d)))
        (is (= ["f-pref"] (:link d)))))
    (testing "caller override remains meaningful for a stance change"
      (is (= :supersede (:action (logic/decide-assert
                                  {:fact fact :pred pred :existing []
                                   :exclusion [standing] :on-conflict :supersede})))))))

(deftest conflict-candidate-generation
  (let [preds {:core/prefers {:id :core/prefers :category :decision
                              :exclusion-group :stance :value-exclusivity :exclusive}
               :core/decided-against {:id :core/decided-against :category :decision
                                      :exclusion-group :stance}
               :core/depends-on {:id :core/depends-on :category :structural}}
        f (fn [id pred obj recorded]
            {:id id :subject {:id "e1" :name "S"} :predicate pred
             :object-kind :literal :object-lit obj
             :t-valid t0 :recorded-at recorded :confidence 0.8})
        facts [(f "f-tabs" :core/prefers "tabs" t0)
               (f "f-spaces" :core/prefers "spaces" t1)
               (f "f-dep" :core/depends-on "KuzuDB" t1)
               (f "f-against" :core/decided-against "kuzu-db" t0)
               (f "f-dep2" :core/depends-on "Redis" t1)]
        at #inst "2026-12-01"
        cands (logic/conflict-candidates facts preds at)
        by-reason (group-by :reason cands)]
    (testing "exclusive many-valued pairs are proposed, newer first"
      (is (= [["f-spaces" "f-tabs"]]
             (mapv (juxt (comp :id :fact) (comp :id :candidate))
                   (:exclusive-values by-reason)))))
    (testing "decision facts sharing an object across predicates are proposed (loose match)"
      (is (= [["f-dep" "f-against"]]
             (mapv (juxt (comp :id :fact) (comp :id :candidate))
                   (:cross-predicate by-reason)))))
    (testing "accumulative structural facts never pair with each other"
      (is (= 2 (count cands))))
    (testing "already-linked pairs are skipped"
      (let [linked (mapv #(if (= "f-spaces" (:id %)) (assoc % :conflicts ["f-tabs"]) %)
                         facts)]
        (is (= [:cross-predicate]
               (mapv :reason (logic/conflict-candidates linked preds at))))))
    (testing "different subjects never pair"
      (let [other (mapv #(if (= "f-against" (:id %))
                           (assoc % :subject {:id "e2" :name "T"}) %)
                        facts)]
        (is (empty? (filter #(= :cross-predicate (:reason %))
                            (logic/conflict-candidates other preds at))))))))

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

(deftest entity-name-normalization
  (is (= "authservice"
         (logic/normalize-entity-name "AuthService")
         (logic/normalize-entity-name "auth-service")
         (logic/normalize-entity-name "auth_service")
         (logic/normalize-entity-name "Auth Service")))
  (is (= "memgraphcore" (logic/normalize-entity-name "memgraph.core"))))

(deftest entity-match-precedence
  (let [auth {:id "e1" :name "AuthService" :type :service :aliases ["auth"]}
        other {:id "e2" :name "auth-service" :type :service :aliases []}
        pick (fn [name cands & [type]]
               (logic/pick-entity-match
                {:name name :norm (logic/normalize-entity-name name) :type type}
                cands))]
    (testing "exact name beats everything"
      (is (= [:exact "e2"] ((juxt :via (comp :id :entity))
                            (pick "auth-service" [auth other])))))
    (testing "alias beats normalized"
      (is (= [:alias "e1"] ((juxt :via (comp :id :entity)) (pick "auth" [auth])))))
    (testing "unique normalized match resolves"
      (is (= [:normalized "e1"] ((juxt :via (comp :id :entity)) (pick "auth_service" [auth])))))
    (testing "ambiguous normalized match returns the collision, never a guess"
      (let [r (pick "auth_service" [auth other])]
        (is (= :ambiguous (:via r)))
        (is (= ["e1" "e2"] (mapv :id (:candidates r))))))
    (testing "zero candidates is nil — genuinely new"
      (is (nil? (pick "auth_service" []))))
    (testing "type incompatibility blocks a normalized match"
      (is (nil? (pick "auth_service" [auth] :file)))
      (is (some? (pick "auth_service" [auth] :service))))))

(deftest duplicate-collapse-keeps-the-earliest
  (let [fact (fn [id recorded & {:as over}]
               (merge {:id id :subject {:id "e1"} :predicate :core/depends-on
                       :object-kind :entity :object-ref {:id "e2"}
                       :scope "project" :epistemic :observation
                       :t-valid t0 :recorded-at recorded :confidence 0.8}
                      over))
        facts [(fact "f-early" t0)
               (fact "f-late" t1)
               (fact "f-other-scope" t1 :scope "module:x")
               (fact "f-dead" t0 :t-invalid t1)]]
    (is (= ["f-late"] (logic/collapse-duplicates-plan facts #inst "2026-12-01"))
        "only true duplicates collapse; scope-distinct and invalidated facts don't")))

(deftest duplicate-entity-clusters
  (let [entities [{:id "e1" :name "FooBar" :scope "project"}
                  {:id "e2" :name "foo-bar" :scope "project"}
                  {:id "e3" :name "foo-bar" :scope "other"}]]
    (is (= [{:normalized "foobar" :scope "project"
             :entities [{:id "e1" :name "FooBar"}
                        {:id "e2" :name "foo-bar"}]}]
           (logic/entity-duplicate-clusters entities))
        "clusters are per-scope")))

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
