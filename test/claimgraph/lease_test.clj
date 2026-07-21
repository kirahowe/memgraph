(ns claimgraph.lease-test
  "The write lease: atomic acquisition, token-guarded release, expiry
  breaking, contention errors, and serialization under real concurrency."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.lease :as lease]))

(defn- temp-db []
  (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-lease-test"}) "db")))

(deftest acquire-release-cycle
  (let [db (temp-db)
        t1 (lease/acquire! db {:owner "a"})]
    (is (fs/exists? (lease/lock-file db)))
    (testing "a second writer times out with the holder named"
      (let [e (try (lease/acquire! db {:owner "b" :wait-ms 0})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :store-locked (:type (ex-data e))))
        (is (= "a" (get-in (ex-data e) [:holder :owner])))))
    (lease/release! db t1)
    (is (not (fs/exists? (lease/lock-file db))))
    (testing "free again"
      (let [t2 (lease/acquire! db {:owner "b" :wait-ms 0})]
        (lease/release! db t2)))))

(deftest expired-leases-break
  (let [db (temp-db)]
    (lease/acquire! db {:owner "crashed" :ttl-ms -1})
    (testing "a dead writer's lease never wedges the store"
      (let [t (lease/acquire! db {:owner "next" :wait-ms 1000})]
        (is (string? t))
        (lease/release! db t)))))

(deftest release-is-token-guarded
  (let [db (temp-db)
        stale (lease/acquire! db {:owner "a" :ttl-ms -1})
        fresh (lease/acquire! db {:owner "b" :wait-ms 1000})]
    (testing "the expired holder's late release cannot free b's lease"
      (lease/release! db stale)
      (is (fs/exists? (lease/lock-file db))))
    (lease/release! db fresh)))

(deftest with-lease-serializes-concurrent-writers
  (let [db (temp-db)
        state (atom [])
        job (fn [id]
              (future
                (lease/with-lease db {:owner (str id) :wait-ms 10000}
                  (fn []
                    (swap! state conj [:enter id])
                    (Thread/sleep 30)
                    (swap! state conj [:exit id])))))]
    (run! deref [(job "w1") (job "w2") (job "w3")])
    (testing "critical sections never interleave"
      (is (= 6 (count @state)))
      (is (every? (fn [[[e1 id1] [e2 id2]]] (and (= :enter e1) (= :exit e2) (= id1 id2)))
                  (partition 2 @state))))))
