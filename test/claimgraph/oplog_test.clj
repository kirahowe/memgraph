(ns claimgraph.oplog-test
  "The append-only effect log and its reconciliation: two writers on
  separate machines, syncing nothing but log files, converging on one graph
  with their disagreements surfaced rather than merged away."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.oplog :as oplog]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(defn- machine
  "A writer: its own store, db path, and effect log."
  [writer-name]
  (let [db (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-oplog-test"}) "db"))]
    (fs/create-dirs (oplog/oplog-dir db))
    (spit (str (fs/path (oplog/oplog-dir db) "writer")) writer-name)
    (let [s (oplog/logged-store (doto (mem/create) (core/seed!)) db)]
      {:db db :store s})))

(defn- sync-log!
  "What git/rsync/Syncthing would do: copy one writer's log file to the
  other machine's oplog directory."
  [from to]
  (doseq [f (fs/glob (oplog/oplog-dir (:db from)) "*.jsonl")]
    (fs/copy f (fs/path (oplog/oplog-dir (:db to)) (fs/file-name f))
             {:replace-existing true})))

(defn- fact-triples
  "Triples under NORMALIZED names: each machine keeps whichever display name
  it saw first (the other arrives as an alias), so convergence is about
  identity and validity, not labels."
  [s]
  (set (map (fn [f] [(logic/normalize-entity-name (get-in f [:subject :name]))
                     (:predicate f)
                     (or (some-> (get-in f [:object-ref :name])
                                 logic/normalize-entity-name)
                         (:object-lit f))
                     (some? (:t-invalid f))])
            (store/-all-facts (oplog/inner-store s)))))

(deftest writes-append-effects
  (let [{:keys [db store]} (machine "w-a")]
    (core/assert-fact store {:subject "svc" :predicate :core/prefers
                             :object "argon2" :object-kind :literal})
    (let [log (fs/path (oplog/oplog-dir db) "w-a.jsonl")]
      (is (fs/exists? log))
      (let [lines (clojure.string/split-lines (slurp (str log)))]
        (is (some #(clojure.string/includes? % "insert-fact") lines))
        (is (some #(clojure.string/includes? % "ensure-entity") lines))))))

(deftest two-writers-reconcile
  (let [a (machine "w-a")
        b (machine "w-b")]
    ;; machine A: a commitment and a preference, plus an entity rename
    (core/assert-fact (:store a) {:subject "api-layer" :predicate :core/decided-against
                                  :object "GraphQL" :object-kind :literal
                                  :epistemic :commitment :source-type :decision-record})
    (core/assert-fact (:store a) {:subject "AuthService" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    ;; machine B, offline, knowing nothing of A: the same claim under a
    ;; sloppier name, a contradicting stance, and something only B knows
    (core/assert-fact (:store b) {:subject "auth-service" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "api-layer" :predicate :core/prefers
                                  :object "GraphQL" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe"})

    (sync-log! b a)
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (testing "B's effects arrived"
        (is (pos? (get-in r [:effects :applied])))
        (is (empty? (get-in r [:effects :errors])))
        (is (seq (:facts (core/get-facts (:store a) {:entity "billing"})))))

      (testing "the same claim under a different name collapsed by identity, not luck"
        (let [argon (filter #(= "argon2" (:object-lit %))
                            (:facts (core/get-facts (:store a) {:entity "AuthService"
                                                                :include-invalidated true})))]
          (is (= 1 (count (remove :t-invalid argon)))
              "one live copy; B's auth-service resolved to A's AuthService")
          (is (= 1 (count (filter :t-invalid argon)))
              "the duplicate closed, not erased")))

      (testing "the contradiction neither writer could see is queued for the judge"
        (is (pos? (:sweep-candidates r)))))

    (testing "reconcile is idempotent"
      (let [again (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
        (is (zero? (get-in again [:effects :total])))
        (is (zero? (:duplicates-collapsed again)))))

    (testing "syncing the other way converges the two machines"
      (sync-log! a b)
      (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
      (is (= (fact-triples (:store a)) (fact-triples (:store b)))
          "same identities, same validity, on both machines")
      (is (= "auth-service"
             (get-in (core/resolve-entity (:store b) {:name "AuthService"})
                     [:entity :name]))
          "B keeps its own display name; A's name arrived as an alias"))))

(deftest curation-effects-replay
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store a) {:subject "shoply.auth" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "unrelated" :predicate :core/depends-on
                                  :object "x"})
    ;; A renames; the old name survives as an alias
    (core/rename-entity (:store a) {:from "shoply.auth" :to "shoply.identity"})
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (testing "the rename crossed machines"
      (is (= "shoply.identity"
             (get-in (core/resolve-entity (:store b) {:name "shoply.auth"})
                     [:entity :name]))))))
