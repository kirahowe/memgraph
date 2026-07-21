(ns claimgraph.bench-test
  "The mechanics benchmark is deterministic, so a perfect score is a test
  invariant: any regression in the longitudinal behavior (timeline of
  ingests, curation, conflicts, valid time, forgetting) fails here even if
  every unit test still passes."
  (:require [clojure.test :refer [deftest is]]
            [claimgraph.bench :as bench]
            [claimgraph.core :as core]
            [claimgraph.store.memory :as mem]))

(deftest mechanics-benchmark-is-perfect
  (let [s (mem/create)]
    (core/seed! s)
    (bench/run-timeline! s)
    (let [results (bench/run-questions s)]
      (is (empty? (->> results
                       (remove :pass?)
                       (mapv #(select-keys % [:id :desc :expected :actual]))))))))
