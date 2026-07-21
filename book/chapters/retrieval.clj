;; # Retrieval
;;
;; The 2026 evidence says retrieval is where the accuracy points live: a
;; 20-point spread across retrieval methods against 3 to 8 across write
;; strategies. claimgraph's answer has four layers, each visible below:
;; hybrid search with rank fusion, graph traversal (fixed-depth and
;; evidence-guided), sufficiency escalation across tiers, and honest
;; abstention when the graph does not know.

(ns retrieval
  (:require [babashka.fs :as fs]
            [claimgraph.core :as core]
            [claimgraph.evidence :as evidence]
            [claimgraph.store.memory :as mem]))

(def store (doto (mem/create) (core/seed!)))

(defn brief [f]
  {:subject (get-in f [:subject :name])
   :predicate (:predicate f)
   :object (or (some-> (:object-ref f) :name) (:object-lit f))})

;; A small graph to retrieve from: a payments slice of a project.

(core/assert-fact store {:subject "billing" :predicate :core/depends-on
                         :object "stripe-client" :source-type :code
                         :confidence 0.95})
(core/assert-fact store {:subject "stripe-client" :predicate :core/depends-on
                         :object "http-kit" :source-type :code
                         :confidence 0.95})
(core/assert-fact store {:subject "billing" :predicate :core/prefers
                         :object "idempotency keys on retries"
                         :object-kind :literal
                         :source-type :user-assertion})
(core/assert-fact store {:subject "billing" :predicate :core/decided-against
                         :object "polling for webhook delivery"
                         :object-kind :literal
                         :epistemic :commitment
                         :source-type :decision-record})
(core/assert-fact store {:subject "checkout" :predicate :core/depends-on
                         :object "billing" :source-type :code
                         :confidence 0.95})

;; ## Hybrid search
;;
;; `search` fuses three routes with reciprocal rank fusion, each hit
;; weighted by effective confidence: full-text over literals, names, and
;; episode summaries; entity resolution per query token (so a query naming
;; an entity pulls its facts even when no literal matches); and the one-hop
;; neighborhood of resolved entities at low weight. Rank fusion by
;; reciprocal rank is the classic recipe from Cormack, Clarke, and Buettcher
;; (SIGIR 2009); the confidence weighting is claimgraph's addition.

(->> (core/search store "billing retries" {})
     :facts
     (mapv brief))

;; A query that names an entity but matches no stored literal still lands,
;; through the entity-resolution route:

(->> (core/search store "stripe-client" {})
     :facts
     (mapv brief))

;; ## Traversal
;;
;; Fixed-depth BFS expands the neighborhood in both directions, computing
;; inverse edges at query time:

(let [n (core/get-neighborhood store {:entity "billing" :depth 2})]
  {:entities (sort (map :name (:entities n)))
   :edges (count (:facts n))})

;; The guided walk replaces "everything to depth k" with "follow the edges
;; that look like the query": each round scores the frontier's unseen facts
;; by token overlap times effective confidence and takes a beam of the best.
;; Asking about webhooks from `checkout` walks through `billing` to the
;; standing decision, without dragging in the whole neighborhood:

(->> (core/guided-walk store {:entity "checkout"
                              :query "webhook delivery"
                              :budget 5})
     :facts
     (mapv (fn [f] (assoc (brief f) :walk-score (:walk-score f)))))

;; ## Sufficiency escalation
;;
;; `recall` answers from the cheapest tier that can support the query:
;; graph facts, then episode summaries, then raw evidence. The sufficiency
;; rule is deterministic and deliberately dumb: a tier suffices when it
;; returns at least `:min-hits` results. The caller always learns which
;; tier answered.
;;
;; Facts answer when facts exist:

(:tier (core/recall store "idempotency retries" {}))

;; When the graph has no matching fact but a closed episode's summary
;; mentions the topic, the episode tier answers. Close an episode with a
;; summary the way `consolidate` does:

(def ep (core/open-episode store {:source-type :session-log
                                  :ref "session-42"}))

(core/close-episode store {:episode (:id ep)
                           :summary "Debugged the flaky payout cron; root cause was
a timezone mismatch between the scheduler and the bank API."})

(let [r (core/recall store "payout cron timezone" {})]
  {:tier (:tier r)
   :episodes (mapv :summary (:episodes r))})

;; And when even the summaries are silent, the raw-evidence tier greps the
;; content-addressed artifacts that extraction kept. Store a transcript
;; fragment as evidence, attach it to an episode, and ask about a detail no
;; fact or summary carries:

(def evidence-dir (str (fs/create-temp-dir {:prefix "claimgraph-book"}) "/evidence"))

(def transcript
  "user: the payout cron failed again
agent: the bank sandbox rejects amounts over 10000 cents in test mode
user: right, cap the fixture amounts")

(def ehash (evidence/write! evidence-dir transcript))

(core/open-episode store {:source-type :session-log
                          :ref "session-43"
                          :evidence ehash})

(let [r (core/recall store "sandbox amount limit" {:evidence-dir evidence-dir})]
  {:tier (:tier r)
   :evidence (mapv :lines (:evidence r))})

;; ## Abstention
;;
;; The correct answer to a question the graph cannot support is nothing,
;; not a near-miss. Retrieval returns empty rather than garbage, and
;; `recall` says so explicitly:

(let [r (core/recall store "kafka partitioning strategy" {})]
  {:tier (:tier r) :facts (count (:facts r)) :episodes (count (:episodes r))})

;; The benchmark holds a whole question tier for this: refusal versus
;; confabulation, at the retrieval layer and again at the agent layer.
;;
;; ```bash
;; bin/claim search "billing retries"
;; bin/claim neighbor --entity billing --depth 2
;; bin/claim neighbor --entity checkout --query "webhook delivery"
;; bin/claim recall "sandbox amount limit"
;; ```
