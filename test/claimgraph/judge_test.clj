(ns claimgraph.judge-test
  "Conflict judge: verdict parsing and resolution planning as pure functions,
  plus the full loop against an in-memory store with an injected judge —
  no LLM, no subprocess."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.judge :as judge]
            [claimgraph.store]
            [claimgraph.store.memory :as mem]))

(deftest verdict-parsing-is-tolerant
  (testing "clean verdict"
    (is (= {:relation :supersedes :confidence 0.9 :rationale "B is outdated"}
           (judge/parse-judgment
            "{\"relation\":\"supersedes\",\"confidence\":0.9,\"rationale\":\"B is outdated\"}"))))
  (testing "prose and fences around the verdict"
    (is (= :duplicate
           (:relation (judge/parse-judgment
                       (str/join "\n" ["Looking at both facts:"
                                       "```json"
                                       "{\"relation\":\"duplicate\",\"confidence\":1}"
                                       "```"]))))))
  (testing "confidence is clamped to [0,1] and non-numbers become 0"
    (is (= 1.0 (:confidence (judge/parse-judgment "{\"relation\":\"compatible\",\"confidence\":7}"))))
    (is (= 0.0 (:confidence (judge/parse-judgment "{\"relation\":\"compatible\",\"confidence\":\"high\"}")))))
  (testing "garbage and unknown relations become an unparseable verdict, not a throw"
    (is (= {:relation :unparseable :confidence 0.0} (judge/parse-judgment "no idea")))
    (is (= {:relation :unparseable :confidence 0.0}
           (judge/parse-judgment "{\"relation\":\"sideways\",\"confidence\":0.9}")))))

(deftest resolution-planning-is-pure
  (let [pair {:fact {:id "f-new"} :candidate {:id "f-old"}}]
    (testing "contradicts is never auto-resolved, regardless of confidence"
      (is (= {:action :none :reason :needs-human}
             (judge/resolution-plan pair {:relation :contradicts :confidence 1.0} 0.8))))
    (testing "low confidence gates action"
      (is (= {:action :none :reason :low-confidence}
             (judge/resolution-plan pair {:relation :duplicate :confidence 0.5} 0.8))))
    (testing "duplicate invalidates the newer fact"
      (is (= {:action :invalidate :fact-id "f-new" :reason "judged duplicate of f-old"}
             (judge/resolution-plan pair {:relation :duplicate :confidence 0.9} 0.8))))
    (testing "supersedes invalidates the established fact"
      (is (= {:action :invalidate :fact-id "f-old" :reason "judged superseded by f-new"}
             (judge/resolution-plan pair {:relation :supersedes :confidence 0.9} 0.8))))
    (testing "compatible unlinks"
      (is (= {:action :unlink}
             (judge/resolution-plan pair {:relation :compatible :confidence 0.9} 0.8))))
    (testing "unparseable verdicts plan nothing"
      (is (= {:action :none :reason :unparseable}
             (judge/resolution-plan pair {:relation :unparseable :confidence 0.0} 0.8))))))

(defn- store-with-conflict
  "A store holding one open commitment conflict: ADR-1 has-status accepted
  (established) vs superseded (newer, flagged)."
  []
  (let [s (mem/create)]
    (core/seed! s)
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "accepted"})
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "superseded"})
    s))

(defn- verdict-fn [relation confidence]
  (fn [_prompt]
    (str "{\"relation\":\"" (name relation) "\",\"confidence\":" confidence "}")))

(deftest judge-enriches-without-resolving-by-default
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :supersedes 0.95)})]
    (is (= 1 (:conflicts r)))
    (is (zero? (:resolved r)))
    (is (= :supersedes (get-in r [:results 0 :verdict :relation])))
    (is (= 1 (:open (core/conflicts s))) "report-only mode leaves the conflict open")))

(deftest judge-resolves-supersedes
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :supersedes 0.95) :resolve true})]
    (is (= 1 (:resolved r)))
    (is (zero? (:open (core/conflicts s))))
    (let [{:keys [facts]} (core/get-facts s {:entity "ADR-1"})]
      (is (= ["superseded"] (mapv :object-lit facts)) "only the newer status survives"))
    (let [{:keys [history]} (core/get-history s {:subject "ADR-1" :predicate :core/has-status})]
      (is (some #(str/includes? (str (:invalidation-reason %)) "judged superseded") history)))))

(deftest judge-resolves-duplicate-against-the-newer-fact
  (let [s (store-with-conflict)]
    (judge/judge-conflicts! s {:judge-fn (verdict-fn :duplicate 0.9) :resolve true})
    (let [{:keys [facts]} (core/get-facts s {:entity "ADR-1"})]
      (is (= ["accepted"] (mapv :object-lit facts)) "the established fact survives"))))

(deftest judge-unlinks-compatible-pairs
  (let [s (store-with-conflict)]
    (judge/judge-conflicts! s {:judge-fn (verdict-fn :compatible 0.9) :resolve true})
    (is (zero? (:open (core/conflicts s))) "conflict closed without invalidating")
    (is (= 2 (count (:facts (core/get-facts s {:entity "ADR-1"})))) "both facts stay valid")))

(deftest judge-leaves-contradictions-for-humans
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :contradicts 1.0) :resolve true})]
    (is (zero? (:resolved r)))
    (is (= :needs-human (get-in r [:results 0 :plan :reason])))
    (is (= 1 (:open (core/conflicts s))))))

(deftest stats-count-open-conflicts
  (let [s (store-with-conflict)]
    (is (= 1 (:open-conflicts (core/stats s))))))

(defn- seeded []
  (doto (mem/create) (core/seed!)))

(deftest sweep-proposes-judges-and-links
  (let [s (seeded)]
    ;; two exclusive preferences: invisible to the write path by design
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "tabs"})
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "spaces"})
    (is (zero? (:open (core/conflicts s))) "value exclusivity is not a write-time concern")
    (testing "compatible verdicts are dropped silently — a noisy generator mutates nothing"
      (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :compatible 0.9)})]
        (is (= 1 (:candidates r)))
        (is (zero? (:linked r)))
        (is (zero? (:open (core/conflicts s))))))
    (testing "a contradicts verdict links into the pipeline for the human"
      (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :contradicts 0.95)})]
        (is (= 1 (:linked r)))
        (is (zero? (:resolved r)) "contradictions are never auto-resolved, even swept ones")
        (is (= 1 (:open (core/conflicts s))))))
    (testing "linked pairs are not proposed again"
      (is (zero? (:candidates (judge/sweep-conflicts!
                               s {:judge-fn (verdict-fn :contradicts 0.95)})))))))

(deftest sweep-catches-decision-vs-structure
  (let [s (seeded)]
    (core/assert-fact s {:subject "claimgraph" :predicate :core/decided-against
                         :object "KuzuDB" :object-kind :literal})
    (core/assert-fact s {:subject "claimgraph" :predicate :core/depends-on :object "kuzu-db"})
    (is (zero? (:open (core/conflicts s)))
        "depends-on is in no exclusion group — conservative groups stay quiet at write")
    (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :contradicts 0.9)})]
      (is (= 1 (:candidates r)))
      (is (= "cross-predicate" (name (get-in r [:results 0 :reason]))))
      (is (= 1 (:open (core/conflicts s)))
          "acting against a standing decision surfaces on the deferred pass"))))

(deftest sweep-resolves-with-the-same-plans
  (let [s (seeded)
        last-week (java.util.Date. (- (System/currentTimeMillis) (* 7 86400000)))]
    ;; backdate the older preference so newer/older is unambiguous
    (claimgraph.store/-insert-fact s {:id "f-tabs" :subject (core/ensure-entity s {:name "fmt"})
                                    :predicate :core/prefers :object-kind :literal
                                    :object-lit "tabs" :t-valid last-week :recorded-at last-week
                                    :confidence 0.8 :epistemic :preference :scope "project"
                                    :source-type :user-assertion})
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "spaces"})
    (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :supersedes 0.9)
                                       :resolve true})]
      (is (= 1 (:resolved r))))
    (let [{:keys [facts]} (core/get-facts s {:entity "fmt"})]
      (is (= ["spaces"] (mapv :object-lit facts))
          "the newer preference superseded the older"))
    (is (zero? (:open (core/conflicts s))))))
