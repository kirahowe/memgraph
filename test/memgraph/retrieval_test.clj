(ns memgraph.retrieval-test
  "Phase-4 retrieval: hybrid search fusion, the evidence-guided walk, and
  sufficiency escalation — in-memory store, no LLM."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [memgraph.core :as core]
            [memgraph.evidence :as evidence]
            [memgraph.store.memory :as mem]))

(defn- seeded []
  (doto (mem/create) (core/seed!)))

(deftest hybrid-search-routes
  (let [s (seeded)]
    (core/assert-fact s {:subject "AuthService" :subject-type :service
                         :predicate :core/prefers
                         :object "argon2 for password hashing" :object-kind :literal})
    (core/alias-entity s {:name "AuthService" :alias "identity-svc"})
    (testing "the entity route answers name queries FTS literals can't"
      (let [r (core/search s "AuthService" {})]
        (is (= "AuthService" (get-in (first (:facts r)) [:subject :name])))
        (is (= ["AuthService"] (mapv :name (:matched-entities r))))))
    (testing "aliases resolve in the entity route"
      (is (seq (:facts (core/search s "identity-svc" {})))))
    (testing "nothing matches, nothing returns"
      (is (empty? (:facts (core/search s "kafka" {})))))))

(deftest guided-walk-follows-the-query
  (let [s (seeded)]
    ;; two branches off the root: a cache branch and a billing branch
    (core/assert-fact s {:subject "api" :predicate :core/depends-on :object "cache"})
    (core/assert-fact s {:subject "api" :predicate :core/depends-on :object "billing"})
    (core/assert-fact s {:subject "cache" :predicate :core/prefers
                         :object "write-through cache strategy" :object-kind :literal})
    (core/assert-fact s {:subject "billing" :predicate :core/depends-on :object "stripe"})
    (let [r (core/guided-walk s {:entity "api" :query "cache strategy" :budget 3 :beam 1})]
      (testing "the walk spends its budget on the query's branch"
        (is (= "cache" (or (get-in (first (:facts r)) [:object-ref :name])
                           (get-in (first (:facts r)) [:subject :name]))))
        (is (some #(= "write-through cache strategy" (:object-lit %)) (:facts r))
            "depth-2 evidence on the relevant branch is reached")
        (is (not-any? #(= "stripe" (get-in % [:object-ref :name])) (:facts r))
            "the irrelevant branch is never expanded under a beam of 1"))
      (testing "facts come back walk-scored, best first"
        (is (apply >= (map :walk-score (:facts r))))))))

(deftest recall-escalates-tier-by-tier
  (let [ev-dir (str (fs/create-temp-dir {:prefix "memgraph-recall-test"}))
        s (seeded)]
    (core/assert-fact s {:subject "api" :predicate :core/prefers
                         :object "REST with EDN bodies" :object-kind :literal})
    (let [ep (core/open-episode s {:source-type :session-log :ref "sess-1"
                                   :evidence (evidence/write!
                                              ev-dir
                                              "kira: the reindexing cron melted the primary at 2am")})]
      (core/close-episode s {:episode (:id ep)
                             :summary "Discussed pagination cursors for the listing endpoints."}))

    (testing "tier 1: the graph answers directly"
      (let [r (core/recall s "REST" {:evidence-dir ev-dir})]
        (is (= :facts (:tier r)))
        (is (seq (:facts r)))))

    (testing "tier 2: no facts, but an episode summary knows"
      (let [r (core/recall s "pagination" {:evidence-dir ev-dir})]
        (is (= :episodes (:tier r)))
        (is (empty? (:facts r)))
        (is (= "sess-1" (:ref (first (:episodes r)))))))

    (testing "tier 3: only the raw evidence remembers"
      (let [r (core/recall s "reindexing" {:evidence-dir ev-dir})]
        (is (= :evidence (:tier r)))
        (is (empty? (:facts r)))
        (is (= "sess-1" (:ref (first (:evidence r)))))
        (is (re-find #"reindexing cron" (first (:lines (first (:evidence r))))))))

    (testing "tier 4: honest nothing"
      (is (= :nothing (:tier (core/recall s "blockchain" {:evidence-dir ev-dir})))))))
