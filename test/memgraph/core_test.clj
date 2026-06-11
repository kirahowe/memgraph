(ns memgraph.core-test
  "Core semantics, run against every Store implementation — the same suite
  exercises the in-memory store and the Datalevin pod store, which is the
  test of the storage abstraction itself. Set MEMGRAPH_TEST_SKIP_DATALEVIN=1
  to run pod-free."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [memgraph.core :as core]
            [memgraph.store :as store]
            [memgraph.store.memory :as mem]))

(def datalevin? (and (not (System/getenv "MEMGRAPH_TEST_SKIP_DATALEVIN"))
                     (fs/which (or (System/getenv "MEMGRAPH_DTLV") "dtlv"))))

(when datalevin?
  (require '[memgraph.store.datalevin]))

(defn- make-stores []
  (cond-> {:memory (mem/create)}
    datalevin?
    (assoc :datalevin ((resolve 'memgraph.store.datalevin/open-store)
                       (str (fs/path (fs/temp-dir) (str "memgraph-test-" (random-uuid))))))))

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
    (is (= 22 (count (store/-list-predicates s {}))))
    (is (= :entity (:object-kind (store/-get-predicate s :core/depends-on))))
    (is (= :commitment (:default-epistemic (store/-get-predicate s :core/decided-against))))
    (testing "seeding is idempotent"
      (core/seed! s)
      (is (= 22 (count (store/-list-predicates s {})))))))

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

(deftest duplicate-is-noop
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (let [r (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})]
      (is (= :noop (:status r))))
    (is (= 1 (count (:facts (core/get-facts s {:entity "A"})))))))

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
    (testing "ambiguity refuses to guess"
      (store/-ensure-entity s {:name "FooBar" :scope "project"})
      (store/-ensure-entity s {:name "foo-bar" :scope "project"})
      (is (nil? (core/resolve-entity s {:name "foo_bar"})))
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

(deftest decay-spares-commitments
  (with-stores [s]
    (let [old (java.util.Date. (- (System/currentTimeMillis) (* 200 86400000)))]
      (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
      ;; backdate recorded-at by inserting directly through the store
      (store/-insert-fact s {:id "f-old" :subject (core/ensure-entity s {:name "A"})
                             :predicate :core/depends-on :object-kind :literal
                             :object-lit "stale" :t-valid old :recorded-at old
                             :confidence 0.8 :epistemic :observation :scope "project"
                             :source-type :inferred})
      (store/-insert-fact s {:id "f-old-commit" :subject (core/ensure-entity s {:name "A"})
                             :predicate :core/decided-against :object-kind :literal
                             :object-lit "GraphQL" :t-valid old :recorded-at old
                             :confidence 0.9 :epistemic :commitment :scope "project"
                             :source-type :user-assertion})
      (let [r (core/decay s {:older-than-days 90 :factor 0.5})]
        (is (= 1 (:affected r))))
      (let [facts (store/-all-facts s)
            by-id (into {} (map (juxt :id identity)) facts)]
        (is (= 0.4 (:confidence (by-id "f-old"))))
        (is (= 0.9 (:confidence (by-id "f-old-commit"))) "commitments never decay")))))

(deftest dump-is-complete
  (with-stores [s]
    (core/assert-fact s {:subject "A" :predicate :core/depends-on :object "B"})
    (let [records (core/dump s)
          types (frequencies (map :type records))]
      (is (= 22 (types "predicate")))
      (is (= 2 (types "entity")))
      (is (= 1 (types "fact"))))))
