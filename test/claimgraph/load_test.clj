(ns claimgraph.load-test
  "dump -> JSON -> load round-trip. The fixture store exercises every
  semantic feature the dump carries: a commitment, a supersession chain with
  its invalidation reason, an open conflict with links, aliases, an x/*
  coinage, a closed episode with a summary, and effective-dated valid time.
  The comparison is semantic: entity ids are re-minted by design, so facts
  are compared with subjects/objects projected to names."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(defn- build-fixture []
  (let [s (mem/create)]
    (core/seed! s)
    ;; commitment + a contradicting write -> flagged conflict with links
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    (core/assert-fact s {:subject "api-layer" :predicate :core/prefers
                         :object "GraphQL" :object-kind :literal
                         :epistemic :preference})
    ;; supersession chain (cardinality :one)
    (core/assert-fact s {:subject "AuthService" :subject-type :service
                         :predicate :core/has-version :object "1.0.0"})
    (core/assert-fact s {:subject "AuthService" :predicate :core/has-version
                         :object "2.0.0"})
    ;; effective-dated closed interval, entity object, alias, x/* coinage
    (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                         :object "Heroku" :object-kind :literal
                         :t-valid #inst "2026-01-01" :t-invalid #inst "2026-03-01"})
    (core/assert-fact s {:subject "billing" :predicate :core/depends-on
                         :object "Redis" :object-type :tool :object-kind :entity})
    (core/alias-entity s {:name "AuthService" :alias "auth-svc"})
    (core/assert-fact s {:subject "billing" :predicate :x/rate-limited-by
                         :object "redis-bucket" :object-kind :literal})
    ;; a closed episode with a summary (searchable episodic memory)
    (let [ep (core/open-episode s {:source-type :session-log :ref "sess-42"})]
      (core/close-episode s {:episode (:id ep) :summary "the session where it happened"}))
    s))

(defn- json-round-trip
  "The dump exactly as a file round-trip delivers it: through cheshire."
  [records]
  (mapv #(json/parse-string (json/generate-string %) true) records))

(defn- norm-rec
  "A dump record normalized for comparison at the dump's own granularity:
  nil-valued keys dropped (present-nil vs absent is not a semantic
  difference), entity ids projected away (re-minted by design — facts
  compare by subject/object name, conflict links by count; their exact
  targets are checked via the open-conflicts view)."
  [rec]
  (let [rec (into {} (filter (comp some? val)) rec)]
    (case (:type rec)
      "entity" (dissoc rec :id)
      "fact" (cond-> (dissoc rec :id)
               (:subject rec) (update :subject :name)
               (:object-ref rec) (update :object-ref :name)
               (:conflicts rec) (update :conflicts count))
      rec)))

(deftest dump-load-round-trip
  (let [src (build-fixture)
        dumped (json-round-trip (core/dump src))
        dst (mem/create)
        _ (core/seed! dst)
        r (core/load-dump dst dumped)]

    (testing "counts survive"
      (is (= :loaded (:status r)))
      (is (= 1 (:conflict-links r)))
      (is (= 2 (:invalidated r))
          "the superseded version and the closed Heroku interval")
      (let [ss (store/-stats src) ds (store/-stats dst)]
        (is (= (:facts ss) (:facts ds)))
        (is (= (:entities ss) (:entities ds)))
        (is (= (:episodes ss) (:episodes ds)))))

    (testing "a second dump is identical to the first, modulo re-minted entity ids"
      (is (= (set (map norm-rec dumped))
             (set (map norm-rec (json-round-trip (core/dump dst)))))
          "facts, episodes, predicates (incl. the x/* coinage), aliases — all of it"))

    (testing "fact and episode ids round-trip exactly (conflict links depend on them)"
      (is (= (set (map :id (store/-all-facts src)))
             (set (map :id (store/-all-facts dst)))))
      (is (= (set (map :id (store/-list-episodes src)))
             (set (map :id (store/-list-episodes dst))))))

    (testing "semantics survive: history, conflicts, aliases, as-of"
      (is (= (mapv #(select-keys % [:object-lit :invalidation-reason])
                   (:history (core/get-history src {:subject "AuthService"
                                                    :predicate :core/has-version})))
             (mapv #(select-keys % [:object-lit :invalidation-reason])
                   (:history (core/get-history dst {:subject "AuthService"
                                                    :predicate :core/has-version})))))
      (is (= 1 (:open (core/conflicts dst)))
          "the flagged commitment conflict is still open after restore")
      (is (= "AuthService" (get-in (core/get-facts dst {:entity "auth-svc"})
                                   [:entity :name]))
          "aliases resolve")
      (is (= ["Heroku"]
             (mapv :object-lit
                   (:facts (core/get-facts dst {:entity "svc"
                                                :as-of #inst "2026-02-01"}))))
          "time travel into the closed interval"))

    (testing "loading into a non-empty store refuses"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                            (core/load-dump dst dumped))))))

(deftest rehydration-restores-types
  (let [[t f] (logic/rehydrate-dump-record
               {:type "fact" :id "f-1"
                :subject {:id "e-1" :name "A" :type "service"}
                :predicate "core/has-version" :object-kind "literal"
                :object-lit "1.0" :t-valid "2026-01-01T00:00:00Z"
                :confidence 0.8 :epistemic "observation"
                :source-type "session-log" :conflicts []})]
    (is (= :fact t))
    (is (= :core/has-version (:predicate f)))
    (is (= :service (get-in f [:subject :type])))
    (is (instance? java.util.Date (:t-valid f)))
    (is (= :observation (:epistemic f))))
  (is (= [:unknown {:x 1}] (logic/rehydrate-dump-record {:type "mystery" :x 1}))))
