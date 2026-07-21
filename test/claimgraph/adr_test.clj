(ns claimgraph.adr-test
  "ADR ingester: mechanical parsing as pure functions, and the decision
  semantics end-to-end — status history, revision edges, rejected options
  as commitments. No LLM anywhere, by design."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.ingest.adr :as adr]
            [claimgraph.store.memory :as mem]))

(def madr
  "# ADR-7: Graph cache storage

Status: accepted
Date: 2026-04-02
Supersedes: ADR-3

## Context

We need a graph-shaped cache.

## Considered Options

* kuzu-db
* plain LMDB
* \"postgres with ltree\"

## Decision Outcome

Chosen option: \"plain LMDB\", because the kuzu-db repo is unmaintained.
")

(deftest parses-madr
  (let [p (adr/parse-adr "0007-graph-cache.md" madr)]
    (is (= "ADR-7" (:id p)))
    (is (= "accepted" (:status p)))
    (is (= ["ADR-3"] (:supersedes p)))
    (is (= ["kuzu-db" "postgres with ltree"] (:rejected p))
        "options minus the chosen one, quotes stripped")))

(deftest id-falls-back-to-filename
  (is (= "ADR-12" (:id (adr/parse-adr "0012-something.md" "no title here"))))
  (is (= "notes" (:id (adr/parse-adr "notes.md" "freeform")))))

(deftest facts-carry-decision-authority
  (let [facts (adr/adr->facts (adr/parse-adr "0007-graph-cache.md" madr))]
    (is (every? #(= :decision-record (:source-type %)) facts))
    (is (every? #(= 1.0 (:confidence %)) facts))
    (is (= :supersede (:on-conflict (first (filter #(= :core/has-status (:predicate %)) facts)))))
    (is (= 2 (count (filter #(= :core/decided-against (:predicate %)) facts))))
    (is (every? #(= :commitment (:epistemic %))
                (filter #(= :core/decided-against (:predicate %)) facts)))))

(deftest adr-lifecycle-end-to-end
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-adr-test"}))
        s (mem/create)
        _ (core/seed! s)]
    (spit (str dir "/0007-graph-cache.md") madr)
    (spit (str dir "/0003-old-cache.md")
          "# ADR-3: Ad-hoc caching\n\nStatus: superseded\nSuperseded by: ADR-7\n")

    (testing "one pass, one decision-record episode, everything lands"
      (let [r (adr/ingest! s {:dir dir})]
        (is (= 2 (:adrs r)))
        (is (zero? (count (:errors r)))))
      (is (= ["accepted"]
             (mapv :object-lit (:facts (core/get-facts s {:entity "ADR-7"
                                                          :predicate :core/has-status})))))
      (is (= "ADR-3" (get-in (first (:facts (core/get-facts s {:entity "ADR-7"
                                                               :predicate :core/supersedes})))
                             [:object-ref :name]))))

    (testing "re-ingesting reinforces instead of duplicating"
      (let [r (adr/ingest! s {:dir dir})]
        (is (pos? (get-in r [:counts :reinforced] 0)))
        (is (zero? (get-in r [:counts :created] 0)))))

    (testing "a status change supersedes; the history accumulates bi-temporally"
      (spit (str dir "/0007-graph-cache.md")
            (clojure.string/replace madr "Status: accepted" "Status: deprecated"))
      (adr/ingest! s {:dir dir})
      (is (= ["deprecated"]
             (mapv :object-lit (:facts (core/get-facts s {:entity "ADR-7"
                                                          :predicate :core/has-status})))))
      (let [{:keys [history]} (core/get-history s {:subject "ADR-7"
                                                   :predicate :core/has-status})]
        (is (= ["accepted" "deprecated"] (mapv :object-lit history)))
        (is (some? (:t-invalid (first history))) "the old status closed, not erased")))

    (testing "a rejected option violated later surfaces through the sweep path"
      ;; the standing decided-against kuzu-db commitment is exactly what the
      ;; conflict machinery guards; here we just confirm it stands as one
      (let [f (first (filter #(= "kuzu-db" (:object-lit %))
                             (:facts (core/get-facts s {:entity "ADR-7"
                                                        :predicate :core/decided-against}))))]
        (is (= :commitment (:epistemic f)))
        (is (= 1.0 (:confidence f)))))

    (testing "dry-run parses without writing"
      (let [before (get-in (core/stats s) [:facts :total])
            r (adr/ingest! s {:dir dir :dry-run true})]
        (is (= :dry-run (:status r)))
        (is (= before (get-in (core/stats s) [:facts :total])))))))
