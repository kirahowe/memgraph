(ns claimgraph.core-test
  "Core semantics, run against every Store implementation — the same suite
  exercises the in-memory store and the Datalevin pod store, which is the
  test of the storage abstraction itself. Set CLAIMGRAPH_TEST_SKIP_DATALEVIN=1
  to run pod-free."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(def datalevin? (and (not (System/getenv "CLAIMGRAPH_TEST_SKIP_DATALEVIN"))
                     (fs/which (or (System/getenv "CLAIMGRAPH_DTLV") "dtlv"))))

(when datalevin?
  (require '[claimgraph.store.datalevin]))

(defn- make-stores []
  (cond-> {:memory (mem/create)}
    datalevin?
    (assoc :datalevin ((resolve 'claimgraph.store.datalevin/open-store)
                       (str (fs/path (fs/temp-dir) (str "claimgraph-test-" (random-uuid))))))))

(defmacro with-stores [[sym] & body]
  `(doseq [[kind# ~sym] (make-stores)]
     (testing (str "[" (name kind#) "] ")
       (core/seed! ~sym)
       (try ~@body
            (finally (store/-close ~sym))))))

(defn- date [s] (java.util.Date/from (java.time.Instant/parse s)))

;; ---------------------------------------------------------------------------

(deftest seed-vocabulary
  (with-stores [s]
    (is (= 23 (count (store/-list-predicates s {}))))
    (is (= :entity (:object-kind (store/-get-predicate s :core/depends-on))))
    (is (= :commitment (:default-epistemic (store/-get-predicate s :core/decided-against))))
    (testing "seeding is idempotent"
      (core/seed! s)
      (is (= 23 (count (store/-list-predicates s {})))))))

(deftest assert-and-read-basic
  (with-stores [s]
    (let [r (core/assert-fact s {:subject "AuthService" :subject-type :service
                                 :predicate "depends-on" :object "Redis" :object-type :service
                                 :scope "module:auth"})]
      (is (= :created (:status r)))
      (is (= :core/depends-on (get-in r [:fact :predicate])) "bare name resolves to :core/*")
      (is (= :observation (get-in r [:fact :epistemic])) "epistemic defaults from predicate")
      (is (= :entity (get-in r [:fact :object-kind]))))
    (let [{:keys [facts]} (core/get-facts s {:entity "AuthService"})]
      (is (= 1 (count facts)))
      (is (= "Redis" (get-in (first facts) [:object-ref :name]))))))

(deftest duplicate-reinforces
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"
                         :confidence 0.5})
    (let [r (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"
                                 :confidence 0.9})]
      (is (= :reinforced (:status r)))
      (is (= 0.9 (get-in r [:fact :confidence])) "stronger evidence raises the base"))
    (let [facts (:facts (core/get-facts s {:entity "A"}))]
      (is (= 1 (count facts)) "still one fact")
      (is (= 0.9 (:confidence (first facts))))
      (is (some? (:last-reinforced-at (first facts)))))))

(deftest cardinality-many-accumulates
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (let [r (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "C"})]
      (is (= :created (:status r)) "many-cardinality predicates don't conflict on new objects"))
    (is (= 2 (count (:facts (core/get-facts s {:entity "A"})))))))

(deftest observation-supersedes
  (with-stores [s]
    (core/assert-fact s {:subject "auth.core" :predicate :core/defined-in :object "src/auth/core.clj"})
    (let [r (core/assert-fact s {:subject "auth.core" :predicate :core/defined-in
                                 :object "src/auth/main.clj"})]
      (is (= :superseded (:status r)))
      (is (= 1 (count (:superseded r)))))
    (let [{:keys [facts]} (core/get-facts s {:entity "auth.core"})]
      (is (= 1 (count facts)) "only the new fact is currently valid")
      (is (= "src/auth/main.clj" (get-in (first facts) [:object-ref :name]))))
    (let [{:keys [history]} (core/get-history s {:subject "auth.core" :predicate :core/defined-in})]
      (is (= 2 (count history)) "history retains the invalidated version")
      (is (some :t-invalid history)))))

(deftest commitment-flags-not-clobbers
  (with-stores [s]
    (core/assert-fact s {:subject "ADR-7" :subject-type :decision
                         :predicate :core/has-status :object "accepted"})
    (let [r (core/assert-fact s {:subject "ADR-7" :predicate :core/has-status
                                 :object "superseded"})]
      (is (= :flagged (:status r)) "commitments surface conflicts instead of silent overwrite")
      (is (= 1 (count (:candidates r))))
      (is (= "accepted" (:object-lit (first (:candidates r))))))
    (testing "both facts remain valid until a human resolves"
      (is (= 2 (count (:facts (core/get-facts s {:entity "ADR-7"}))))))
    (testing "caller can override to supersede"
      (let [r (core/assert-fact s {:subject "ADR-7" :predicate :core/has-status
                                   :object "deprecated" :on-conflict :supersede})]
        (is (= :superseded (:status r)))
        (is (= 1 (count (:facts (core/get-facts s {:entity "ADR-7"})))))))))

(deftest conflict-links-round-trip
  (with-stores [s]
    (core/assert-fact s {:subject "ADR-9" :predicate :core/has-status :object "accepted"})
    (let [r (core/assert-fact s {:subject "ADR-9" :predicate :core/has-status
                                 :object "rejected"})]
      (is (= :flagged (:status r)))
      (is (= 1 (:open (core/conflicts s))))
      (testing "unlinking closes the conflict without invalidating either side"
        (store/-unlink-conflicts s (get-in r [:fact :id])
                                 (mapv :id (:candidates r)))
        (is (zero? (:open (core/conflicts s))))
        (is (= 2 (count (:facts (core/get-facts s {:entity "ADR-9"})))))))))

(deftest cross-predicate-stance-conflicts
  (with-stores [s]
    (core/assert-fact s {:subject "api-layer" :predicate :core/prefers :object "GraphQL"})
    (let [r (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                                 :object "graph-ql" :object-kind :literal})]
      (testing "an opposed stance toward the same object flags at write time"
        (is (= :flagged (:status r)))
        (is (= :core/prefers (:predicate (first (:candidates r))))
            "the antagonist crosses predicates — and the entity/literal divide")
        (is (= 1 (:open (core/conflicts s))))))
    (testing "unrelated objects in the same group don't collide"
      (is (= :created (:status (core/assert-fact s {:subject "api-layer"
                                                    :predicate :core/decided-against
                                                    :object "SOAP"})))))
    (testing "the reverse order flags too (new preference vs standing decision)"
      (core/assert-fact s {:subject "ui" :predicate :core/decided-against :object "Redux"})
      (is (= :flagged (:status (core/assert-fact s {:subject "ui"
                                                    :predicate :core/prefers
                                                    :object "Redux"})))))))

(deftest unknown-predicate-did-you-mean
  (with-stores [s]
    (let [e (try (core/assert-fact s {:subject "A" :predicate :core/depnds-on :object "B"})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :unknown-predicate (:type (ex-data e))))
      (is (some #{:core/depends-on} (:did-you-mean (ex-data e)))))))

(deftest experimental-predicates-auto-register
  (with-stores [s]
    (let [r (core/assert-fact s {:subject "A" :predicate :x/pairs-well-with :object "B"})]
      (is (= :created (:status r))))
    (is (= :testing (:status (store/-get-predicate s :x/pairs-well-with))))
    (testing "core namespace cannot be coined at runtime"
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/register-predicate s {:id :core/sneaky}))))))

(deftest object-kind-enforcement
  (with-stores [s]
    (testing "literal-only predicate rejects entity kind"
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/assert-fact s {:subject "A" :predicate :core/has-version
                                        :object "1.2.3" :object-kind :entity}))))
    (testing "literal objects don't mint entities"
      (core/assert-fact s {:subject "A" :predicate :core/has-version :object "1.2.3"})
      (is (nil? (store/-get-entity s "1.2.3" "project"))))
    (testing ":either heuristic picks entity when one exists"
      (core/ensure-entity s {:name "Result-types" :scope "project"})
      (let [r (core/assert-fact s {:subject "A" :predicate :core/prefers :object "Result-types"})]
        (is (= :entity (get-in r [:fact :object-kind]))))
      (let [r (core/assert-fact s {:subject "A" :predicate :core/prefers :object "small PRs"})]
        (is (= :literal (get-in r [:fact :object-kind])))))))

(deftest as-of-time-travel
  (with-stores [s]
    (core/assert-fact s {:subject "svc" :predicate :core/depends-on :object "Postgres"
                         :t-valid (date "2026-01-01T00:00:00Z")})
    ;; supersede manually: invalidate then assert the replacement
    (let [fid (get-in (first (:facts (core/get-facts s {:entity "svc"}))) [:id])]
      (store/-invalidate s fid (date "2026-03-01T00:00:00Z") "migrated"))
    (core/assert-fact s {:subject "svc" :predicate :core/depends-on :object "CockroachDB"
                         :t-valid (date "2026-03-01T00:00:00Z")})
    (let [then (:facts (core/get-facts s {:entity "svc" :as-of (date "2026-02-01T00:00:00Z")}))
          now* (:facts (core/get-facts s {:entity "svc"}))]
      (is (= ["Postgres"] (mapv #(get-in % [:object-ref :name]) then)))
      (is (= ["CockroachDB"] (mapv #(get-in % [:object-ref :name]) now*))))))

(deftest valid-time-supersession
  (with-stores [s]
    (core/assert-fact s {:subject "claimgraph" :predicate :core/has-version :object "1.0"
                         :t-valid (date "2026-01-01T00:00:00Z")})
    (is (= :superseded (:status (core/assert-fact s {:subject "claimgraph"
                                                     :predicate :core/has-version
                                                     :object "2.0"
                                                     :t-valid (date "2026-03-01T00:00:00Z")}))))
    (testing "as-of between two versions returns exactly one"
      (is (= ["1.0"] (mapv :object-lit (:facts (core/get-facts s {:entity "claimgraph"
                                                                  :as-of (date "2026-02-01T00:00:00Z")})))))
      (is (= ["2.0"] (mapv :object-lit (:facts (core/get-facts s {:entity "claimgraph"
                                                                  :as-of (date "2026-04-01T00:00:00Z")}))))))
    (testing "the intervals abut: predecessor closes at the successor's valid-from"
      (let [{:keys [history]} (core/get-history s {:subject "claimgraph"
                                                   :predicate :core/has-version})
            v1 (first (filter #(= "1.0" (:object-lit %)) history))]
        (is (= (date "2026-03-01T00:00:00Z") (:t-invalid v1)))))))

(deftest backdated-supersede-flags
  (with-stores [s]
    (core/assert-fact s {:subject "svc" :predicate :core/has-version :object "2.0"
                         :t-valid (date "2026-03-01T00:00:00Z")})
    (let [r (core/assert-fact s {:subject "svc" :predicate :core/has-version :object "1.0"
                                 :t-valid (date "2026-01-01T00:00:00Z")})]
      (is (= :flagged (:status r)))
      (is (= :backdated-overlap (:reason r)))
      (is (= ["2.0"] (mapv :object-lit (:candidates r)))))
    (testing "no interval was inverted"
      (let [{:keys [history]} (core/get-history s {:subject "svc"
                                                   :predicate :core/has-version})]
        (is (every? #(or (nil? (:t-invalid %))
                         (< (.getTime ^java.util.Date (:t-valid %))
                            (.getTime ^java.util.Date (:t-invalid %))))
                    history))))))

(deftest closed-past-intervals
  (with-stores [s]
    (is (= :created (:status (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                                                  :object "Heroku" :object-kind :literal
                                                  :t-valid (date "2026-01-01T00:00:00Z")
                                                  :t-invalid (date "2026-03-01T00:00:00Z")})))
        "a closed past interval is one assertion")
    (is (= ["Heroku"] (mapv :object-lit (:facts (core/get-facts s {:entity "svc"
                                                                   :as-of (date "2026-02-01T00:00:00Z")})))))
    (is (empty? (:facts (core/get-facts s {:entity "svc"}))) "already over by now")
    (testing "inverted intervals are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                                        :object "X" :object-kind :literal
                                        :t-valid (date "2026-03-01T00:00:00Z")
                                        :t-invalid (date "2026-01-01T00:00:00Z")}))))
    (testing "manual invalidate takes an effective date, guarded"
      (core/assert-fact s {:subject "svc" :predicate :core/has-version :object "3.0"
                           :t-valid (date "2026-01-01T00:00:00Z")})
      (let [fid (:id (first (:facts (core/get-facts s {:entity "svc"
                                                       :predicate :core/has-version}))))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (core/invalidate s {:fact-id fid :at (date "2025-12-01T00:00:00Z")}))
            "closing a fact before it starts is rejected")
        (is (thrown? clojure.lang.ExceptionInfo
                     (core/invalidate s {:fact-id "f-missing"})))
        (core/invalidate s {:fact-id fid :at (date "2026-02-01T00:00:00Z") :reason "sunset"})
        (is (empty? (:facts (core/get-facts s {:entity "svc" :predicate :core/has-version
                                               :as-of (date "2026-02-15T00:00:00Z")}))))
        (is (= 1 (count (:facts (core/get-facts s {:entity "svc" :predicate :core/has-version
                                                   :as-of (date "2026-01-15T00:00:00Z")})))))))))

(deftest neighborhood-bfs
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (core/assert-fact s {:subject "B" :predicate :core/depends-on :object "C"})
    (core/assert-fact s {:subject "C" :predicate :core/depends-on :object "D"})
    (let [n1 (core/get-neighborhood s {:entity "A" :depth 1})
          n2 (core/get-neighborhood s {:entity "A" :depth 2})]
      (is (= #{"A" "B"} (set (map :name (:entities n1)))))
      (is (= #{"A" "B" "C"} (set (map :name (:entities n2)))))
      (is (= 2 (count (:facts n2)))))
    (testing "traversal follows inverse direction too (computed, not stored)"
      (let [n (core/get-neighborhood s {:entity "C" :depth 1})]
        (is (= #{"B" "C" "D"} (set (map :name (:entities n)))))))))

(deftest batched-fact-fetch
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (core/assert-fact s {:subject "B" :predicate :core/depends-on :object "C"})
    (core/assert-fact s {:subject "A" :predicate :core/prefers :object "x"})
    (let [eid #(get-in (core/resolve-entity s {:name %}) [:entity :id])
          ids [(eid "A") (eid "B")]]
      (testing "a fact touching two requested ids appears once"
        ;; A->B is outgoing from A and incoming to B
        (is (= 3 (count (store/-get-facts-for s ids {:direction :both})))))
      (testing "direction filters apply to the whole batch"
        (is (= 3 (count (store/-get-facts-for s ids {:direction :out}))))
        (is (= 1 (count (store/-get-facts-for s ids {:direction :in})))))
      (testing "predicate filter applies to the whole batch"
        (is (= 2 (count (store/-get-facts-for s ids {:direction :out
                                                     :predicate :core/depends-on}))))))))

(deftest select-facts-candidate-reads
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"
                         :source-type :code :scope "code"})
    (let [pref (core/assert-fact s {:subject "A" :predicate :core/prefers :object "x"})
          _ (core/assert-fact s {:subject "ADR" :predicate :core/has-status :object "accepted"})
          flag (core/assert-fact s {:subject "ADR" :predicate :core/has-status :object "rejected"})]
      (testing "source-type + scopes narrow the set"
        (is (= [:core/depends-on]
               (mapv :predicate (store/-select-facts s {:source-type :code
                                                        :scopes #{"code" "external"}})))))
      (testing "conflicted + valid-cheap finds exactly the flagged fact"
        (is (= [(get-in flag [:fact :id])]
               (mapv :id (store/-select-facts s {:conflicted true :valid-cheap true})))))
      (testing "exact ids"
        (is (= 1 (count (store/-select-facts s {:ids [(get-in pref [:fact :id])]})))))
      (testing ":predicates narrows by predicate set"
        (is (= 2 (count (store/-select-facts s {:predicates [:core/has-status]})))))
      (testing "-get-facts :predicate accepts a collection"
        (let [aid (get-in (core/resolve-entity s {:name "A"}) [:entity :id])]
          (is (= 2 (count (store/-get-facts s aid {:predicate [:core/depends-on
                                                               :core/prefers]}))))))
      (testing "valid-cheap drops invalidated facts"
        (core/invalidate s {:fact-id (get-in pref [:fact :id])})
        (is (= 3 (count (store/-select-facts s {:valid-cheap true}))))
        (is (= 4 (count (store/-select-facts s {})))))
      (testing "recorded-before excludes fresh facts and includes backdated ones"
        (let [old (java.util.Date. (- (System/currentTimeMillis) (* 30 86400000)))
              cutoff (java.util.Date. (- (System/currentTimeMillis) 86400000))]
          (store/-insert-fact s {:id "f-backdated" :subject (core/ensure-entity s {:name "A"})
                                 :predicate :core/depends-on :object-kind :literal
                                 :object-lit "old" :t-valid old :recorded-at old
                                 :confidence 0.8 :epistemic :observation :scope "project"
                                 :source-type :inferred})
          (is (= ["f-backdated"]
                 (mapv :id (store/-select-facts s {:recorded-before cutoff}))))))
      (testing "episodes narrow to provenance"
        (let [r (core/ingest s {:source-type :session-log :ref "sess"}
                             [{:subject "E" :predicate "prefers" :object "y"}])]
          (is (= 1 (count (store/-select-facts s {:episodes [(:episode r)]}))))))
      (testing "predicate usage aggregates store-side"
        (is (= 2 (get (store/-predicate-usage s) :core/depends-on)))
        (is (= 2 (get (store/-predicate-usage s) :core/prefers)))))))

(deftest literals-are-terminal-in-traversal
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/prefers :object "kebab-case"})
    (let [n (core/get-neighborhood s {:entity "A" :depth 3})]
      (is (= #{"A"} (set (map :name (:entities n)))))
      (is (= 1 (count (:facts n)))))))

(deftest entity-resolution-in-lookups
  (with-stores [s]
    (core/assert-fact s {:subject "AuthService" :subject-type :service
                         :predicate :core/depends-on :object "Redis"})
    (testing "near-match names resolve in reads (without mutating anything)"
      (is (= 1 (count (:facts (core/get-facts s {:entity "auth-service"})))))
      (is (= 1 (count (:facts (core/get-facts s {:entity "redis" :direction :in}))))))
    (testing "asserting against a near-match does not mint a duplicate entity"
      (core/assert-fact s {:subject "auth_service" :predicate :core/prefers :object "x"})
      (is (= 2 (count (:facts (core/get-facts s {:entity "AuthService"}))))))
    (testing "the write path self-heals: the near-match name became an alias"
      (is (some #{"auth_service"}
                (:aliases (:entity (core/resolve-entity s {:name "AuthService"}))))))
    (testing "zero matches is genuinely new — nil, create is correct"
      (is (nil? (core/resolve-entity s {:name "BrandNewThing"}))))))

(deftest ambiguity-surfaces-instead-of-creating
  (with-stores [s]
    (store/-ensure-entity s {:name "FooBar" :scope "project"})
    (store/-ensure-entity s {:name "foo-bar" :scope "project"})
    (testing "a detected collision is distinguished from genuinely-new"
      (let [r (core/resolve-entity s {:name "foo_bar"})]
        (is (= :ambiguous (:via r)))
        (is (= 2 (count (:candidates r))))))
    (testing "the write path throws with candidates instead of minting a third entity"
      (let [e (try (core/ensure-entity s {:name "foo_bar"})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :ambiguous-entity (:type (ex-data e))))
        (is (= #{"FooBar" "foo-bar"}
               (set (map :name (:candidates (ex-data e))))))
        (is (= 2 (count (store/-find-entities s "foo_bar" "project")))
            "no third entity appeared")))
    (testing "reads fail with the candidates, not entity-not-found"
      (let [e (try (core/get-facts s {:entity "foo_bar"})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :ambiguous-entity (:type (ex-data e))))))
    (testing "exact names and aliases still resolve through the collision"
      (is (= :exact (:via (core/resolve-entity s {:name "FooBar"}))))
      (is (some? (core/ensure-entity s {:name "foo-bar"}))))
    (testing "ingest routes ambiguous subjects to the error bucket"
      (let [r (core/ingest s {:source-type :session-log :ref "x"}
                           [{:subject "foo_bar" :predicate "prefers" :object "y"}
                            {:subject "FooBar" :predicate "prefers" :object "z"}])]
        (is (= 1 (count (:errors r))))
        (is (= 1 (get-in r [:counts :created])))))
    (testing "the duplicates report shows the cluster to merge"
      (is (pos? (:clusters (core/entity-duplicates s)))))))

(deftest entity-rename-preserves-everything
  (with-stores [s]
    (core/assert-fact s {:subject "UserService" :predicate :core/depends-on :object "Postgres"})
    (let [r (core/rename-entity s {:from "UserService" :to "AccountService"})]
      (is (= "AccountService" (get-in r [:entity :name])))
      (is (some #{"UserService"} (get-in r [:entity :aliases]))))
    (testing "facts follow the rename in both backends"
      (let [{:keys [facts]} (core/get-facts s {:entity "AccountService"})]
        (is (= 1 (count facts)))
        (is (= "AccountService" (get-in (first facts) [:subject :name])))))
    (testing "the old name still resolves"
      (is (= :alias (:via (core/resolve-entity s {:name "UserService"})))))
    (testing "renaming onto another entity is refused"
      (core/ensure-entity s {:name "Postgres"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/rename-entity s {:from "AccountService" :to "Postgres"}))))))

(deftest entity-merge-repoints-and-collapses
  (with-stores [s]
    (core/assert-fact s {:subject "AuthSvc" :predicate :core/depends-on :object "Redis"})
    (core/assert-fact s {:subject "AuthService" :predicate :core/depends-on :object "Redis"})
    (core/assert-fact s {:subject "AuthSvc" :predicate :core/prefers :object "small functions"})
    (let [r (core/merge-entities s {:from "AuthSvc" :into "AuthService"})]
      (is (= 2 (:facts-repointed r)))
      (is (= 1 (:duplicates-invalidated r)) "the doubled Redis dependency collapses")
      (is (some #{"AuthSvc"} (:aliases-added r))))
    (testing "the survivor carries everything"
      (let [{:keys [facts]} (core/get-facts s {:entity "AuthService"})]
        (is (= #{:core/depends-on :core/prefers} (set (map :predicate facts))))
        (is (= 2 (count facts)))))
    (testing "the husk is gone but its name resolves to the survivor"
      (is (nil? (store/-get-entity s "AuthSvc" "project")))
      (is (= "AuthService" (get-in (core/resolve-entity s {:name "AuthSvc"})
                                   [:entity :name]))))
    (testing "the collapsed duplicate is invalidated, not deleted"
      (let [{:keys [history]} (core/get-history s {:subject "AuthService"
                                                   :predicate :core/depends-on})]
        (is (= 2 (count history)))
        (is (= 1 (count (filter :t-invalid history))))))
    (testing "self-merge is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (core/merge-entities s {:from "AuthSvc" :into "AuthService"}))))))

(deftest entity-split-records-lineage
  (with-stores [s]
    (core/assert-fact s {:subject "UserService" :predicate :core/prefers :object "CQRS"})
    (let [r (core/split-entity s {:from "UserService"
                                  :into "UserReadService, UserWriteService"})]
      (is (= ["UserReadService" "UserWriteService"] (:into r)))
      (is (= 2 (count (:lineage r)))))
    (testing "successors carry derived-from lineage"
      (let [{:keys [facts]} (core/get-facts s {:entity "UserReadService"})]
        (is (= :core/derived-from (:predicate (first facts))))
        (is (= "UserService" (get-in (first facts) [:object-ref :name])))))
    (testing "the source's facts are untouched"
      (is (= 1 (count (:facts (core/get-facts s {:entity "UserService"
                                                 :predicate :core/prefers}))))))))

(deftest episodes-and-ingest
  (with-stores [s]
    (let [r (core/ingest s {:source-type :session-log :ref "session-42"}
                         [{:subject "AuthService" :predicate "depends-on" :object "Redis"}
                          {:subject "AuthService" :predicate "prefers" :object "Result types"}
                          {:subject "AuthService" :predicate "bogus-pred" :object "X"}])]
      (is (= 3 (:total r)))
      (is (= 2 (get-in r [:counts :created])))
      (is (= 1 (count (:errors r))))
      (testing "facts carry the episode as provenance"
        (let [{:keys [facts]} (core/get-facts s {:entity "AuthService"})]
          (is (every? #(= (:episode r) (:episode %)) facts))))
      (testing "close writes the summary"
        (core/close-episode s {:episode (:episode r) :summary "session 42 wrap-up"})
        (is (= "session 42 wrap-up" (:summary (store/-get-episode s (:episode r)))))))))

(deftest search-finds-literals-and-entities
  (with-stores [s]
    (core/assert-fact s {:subject "PaymentService" :predicate :core/prefers
                         :object "idempotency keys on retries"})
    (let [r (core/search s "idempotency" {})]
      (is (= 1 (count (:facts r)))))
    (let [r (core/search s "PaymentService" {})]
      (is (= 1 (count (:entities r)))))))

(deftest disuse-decay-is-computed-at-read
  (with-stores [s]
    (let [old (java.util.Date. (- (System/currentTimeMillis) (* 90 86400000)))]
      ;; a fact last reinforced one half-life ago
      (store/-insert-fact s {:id "f-stale" :subject (core/ensure-entity s {:name "A"})
                             :predicate :core/prefers :object-kind :literal
                             :object-lit "stale style" :t-valid old :recorded-at old
                             :last-reinforced-at old
                             :confidence 0.8 :epistemic :preference :scope "project"
                             :source-type :inferred})
      (let [f (first (:facts (core/get-facts s {:entity "A"})))]
        (is (= 0.8 (:confidence f)) "the stored base never changes")
        (is (< 0.3 (:effective-confidence f) 0.5) "~one half-life gone at read time"))
      (testing "min-confidence filters on effective confidence"
        (is (empty? (:facts (core/get-facts s {:entity "A" :min-confidence 0.6})))))
      (testing "re-assertion reinforces: the disuse clock resets"
        (let [r (core/assert-fact s {:subject "A" :predicate :core/prefers
                                     :object "stale style" :object-kind :literal
                                     :source-type :inferred :confidence 0.8})]
          (is (= :reinforced (:status r))))
        (let [f (first (:facts (core/get-facts s {:entity "A"})))]
          (is (> (:effective-confidence f) 0.75) "hot again")
          (is (= 0.8 (:confidence f)) "repetition alone never grows the base"))))))

(deftest dump-is-complete
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (let [records (core/dump s)
          types (frequencies (map :type records))]
      (is (= 23 (types "predicate")))
      (is (= 2 (types "entity")))
      (is (= 1 (types "fact"))))))

(deftest predicate-promotion
  (with-stores [s]
    ;; coin by first use, accumulate facts under the staging term
    (core/assert-fact s {:subject "billing" :predicate "x/rate-limited-by"
                         :object "redis-bucket" :object-kind :literal})
    (core/assert-fact s {:subject "api" :predicate "x/rate-limited-by"
                         :object "token-bucket" :object-kind :literal})
    (let [r (core/promote-predicate s {:from "x/rate-limited-by"
                                       :to "core/rate-limited-by"
                                       :definition "Subject is rate limited by the object mechanism."
                                       :category :procedural})]
      (is (= :promoted (:status r)))
      (is (= 2 (:facts-rewritten r))))
    (testing "facts moved to the stable term, history untouched"
      (is (= ["redis-bucket"]
             (mapv :object-lit (:facts (core/get-facts s {:entity "billing"
                                                          :predicate :core/rate-limited-by})))))
      (is (empty? (:facts (core/get-facts s {:entity "billing"
                                             :predicate :x/rate-limited-by})))))
    (testing "the stable twin carries the registry row"
      (let [p (store/-get-predicate s :core/rate-limited-by)]
        (is (= :stable (:status p)))
        (is (= :procedural (:category p)))))
    (testing "the staging term is deprecated with a forwarding address"
      (let [p (store/-get-predicate s :x/rate-limited-by)]
        (is (= :deprecated (:status p)))
        (is (= :core/rate-limited-by (:replaced-by p))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deprecated"
                            (core/assert-fact s {:subject "billing"
                                                 :predicate "x/rate-limited-by"
                                                 :object "z" :object-kind :literal}))))
    (testing "guards: only x/* -> fresh core/*"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"staging"
                            (core/promote-predicate s {:from "core/depends-on"
                                                       :to "core/needs"})))
      (core/assert-fact s {:subject "api" :predicate "x/other"
                           :object "y" :object-kind :literal})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                            (core/promote-predicate
                             s {:from "x/other" :to "core/depends-on"}))))))

(deftest trust-defenses
  (with-stores [s]
    ;; a lived-and-died value: Heroku, invalidated by the migration
    (let [dead (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                                    :object "Heroku" :object-kind :literal
                                    :t-valid #inst "2026-01-01"})]
      (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                           :object "Fly.io" :object-kind :literal
                           :t-valid #inst "2026-03-10"})
      (core/invalidate s {:fact-id (get-in dead [:fact :id])
                          :at #inst "2026-03-10" :reason "migrated"}))

    (testing "a low-trust source resurrecting the dead value flags against the live rival"
      (let [r (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                                   :object "Heroku" :object-kind :literal
                                   :source-type :session-log :confidence 0.7})]
        (is (= :flagged (:status r)))
        (is (= :revenant (:reason r)))
        (is (= ["Fly.io"] (mapv :object-lit (:candidates r))))))

    (testing "a trusted source re-adding a dead value passes — the world really did move back"
      (let [r (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                                   :object "Rollback-Heroku" :object-kind :literal
                                   :source-type :code})]
        (is (= :created (:status r))))
      ;; and the actually-dead value from a trusted source also passes
      (let [r (core/assert-fact s {:subject "other" :predicate :core/deployed-via
                                   :object "X" :object-kind :literal
                                   :source-type :user-assertion})]
        (is (= :created (:status r)))))

    (testing "a weaker source never silently supersedes a stronger fact"
      (core/assert-fact s {:subject "svc" :predicate :core/has-version
                           :object "2.0.0" :source-type :code :confidence 0.95})
      (let [r (core/assert-fact s {:subject "svc" :predicate :core/has-version
                                   :object "9.9.9" :source-type :agent-note
                                   :confidence 0.6})]
        (is (= :flagged (:status r)))
        (is (= :outranked (:reason r)))
        (is (= ["2.0.0"]
               (mapv :object-lit (:facts (core/get-facts s {:entity "svc"
                                                            :predicate :core/has-version
                                                            :min-confidence 0.9}))))
            "the stronger truth still stands"))
      (testing "equal-or-stronger sources still supersede cleanly"
        (let [r (core/assert-fact s {:subject "svc" :predicate :core/has-version
                                     :object "3.0.0" :source-type :code})]
          (is (= :superseded (:status r))))))))
