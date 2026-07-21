(ns claimgraph.outcome-test
  "The outcome signal: retrieval logging, mark semantics, and the
  accepted/rejected asymmetry — accepted use resets disuse clocks, rejected
  use only reports."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.outcome :as outcome]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(defn- aged-fact!
  "Insert a fact whose disuse clock is half a year stale."
  [s id]
  (let [t (java.util.Date. (- (System/currentTimeMillis) (* 180 86400000)))]
    (store/-insert-fact s {:id id
                           :subject (core/ensure-entity s {:name "svc"})
                           :predicate :core/prefers
                           :object-kind :literal :object-lit (str "value-" id)
                           :t-valid t :recorded-at t :last-reinforced-at t
                           :confidence 0.8 :epistemic :preference
                           :source-type :session-log :scope "project"})))

(deftest accepted-use-resets-the-clock
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-outcome-test"}))
        db (str dir "/db")
        s (doto (mem/create) (core/seed!))]
    (aged-fact! s "f-used")
    (aged-fact! s "f-unused")
    (outcome/log-reads! db :search ["f-used"])

    (testing "before: both facts are half-a-year cold"
      (is (< (logic/effective-confidence
              (first (store/-select-facts s {:ids ["f-used"]})) (core/now))
             0.3)))

    (testing "accepted reinforces exactly what was retrieved"
      (let [r (outcome/outcome! s db {:valence "accepted"})]
        (is (= 1 (:reinforced r))))
      (let [[used] (store/-select-facts s {:ids ["f-used"]})
            [unused] (store/-select-facts s {:ids ["f-unused"]})]
        (is (> (logic/effective-confidence used (core/now)) 0.75)
            "hot again — the clock reset")
        (is (= 0.8 (:confidence used)) "never a confidence raise")
        (is (< (logic/effective-confidence unused (core/now)) 0.3)
            "unretrieved facts keep fading")))

    (testing "the mark consumed the entries: an immediate second outcome is empty"
      (is (zero? (:facts-in-play (outcome/outcome! s db {:valence "accepted"})))))

    (testing "rejected reports without reinforcing"
      (aged-fact! s "f-suspect")
      (outcome/log-reads! db :recall ["f-suspect"])
      (let [r (outcome/outcome! s db {:valence "rejected"})]
        (is (zero? (:reinforced r)))
        (is (= ["f-suspect"] (mapv :id (:review r)))))
      (is (< (logic/effective-confidence
              (first (store/-select-facts s {:ids ["f-suspect"]})) (core/now))
             0.3)
          "still cold — rejection never reinforces"))

    (testing "a broken or absent log is a no-op, never an error"
      (is (zero? (:reads (outcome/outcome! s (str dir "/nope/db")
                                           {:valence "accepted"})))))))
