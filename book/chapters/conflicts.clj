;; # Conflicts, trust, and forgetting
;;
;; The write path is where claimgraph earns its keep. Every assertion passes
;; through a pure decision function that weighs the incoming fact against
;; what already stands: same-value duplicates reinforce, contradicted
;; observations supersede, threatened commitments flag, and two trust
;; defenses catch the writes that look like poisoning. This chapter walks
;; each path with real writes.

(ns conflicts
  (:require [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store.memory :as mem]))

(def store (doto (mem/create) (core/seed!)))

(defn brief [f]
  {:subject (get-in f [:subject :name])
   :predicate (:predicate f)
   :object (or (some-> (:object-ref f) :name) (:object-lit f))
   :class (:epistemic f)})

;; ## A commitment flags; it never loses quietly
;;
;; The team decided against GraphQL, and the decision was recorded as a
;; commitment from a decision record:

(core/assert-fact store
                  {:subject "api-layer"
                   :predicate :core/decided-against
                   :object "GraphQL" :object-kind :literal
                   :epistemic :commitment
                   :source-type :decision-record})

;; Months later a session produces "api-layer prefers GraphQL". `prefers`
;; and `decided-against` share the `:stance` exclusion group, so the write
;; path detects the collision even though the predicates differ, and because
;; one side is a commitment, the policy is flag, not supersede:

(def stance-clash
  (core/assert-fact store
                    {:subject "api-layer"
                     :predicate :core/prefers
                     :object "GraphQL" :object-kind :literal
                     :source-type :session-log}))

{:status (:status stance-clash)
 :candidates (mapv brief (:candidates stance-clash))}

;; Both facts are now valid and linked. The conflict stays open until
;; someone rules:

(:open (core/conflicts store))

;; The skill's instruction at this point is explicit: do not pick a winner.
;; Show the human the candidates. Here the human says the decision stands,
;; so the newcomer's interval closes:

(core/invalidate store {:fact-id (get-in stance-clash [:fact :id])
                        :reason "the ADR stands; session was exploratory"})

(:open (core/conflicts store))

;; For conflict classes that do not need a human (duplicates, clean
;; supersessions), `claim judge` classifies pairs offline with an LLM and
;; `--resolve` acts only on high-confidence verdicts. A `contradicts`
;; verdict is never auto-resolved, no matter how confident the judge is.
;;
;; ## Reinforcement: a high-water mark with a ceiling
;;
;; Re-asserting an existing fact is not an error and not a new fact. It is
;; the world confirming the old one. The disuse clock resets, and base
;; confidence behaves as a ceiling-capped high-water mark:

(core/assert-fact store
                  {:subject "team"
                   :predicate :core/prefers
                   :object "trunk-based development" :object-kind :literal
                   :source-type :user-assertion
                   :confidence 0.8})

;; Weaker evidence never lowers it:

(-> (core/assert-fact store
                      {:subject "team"
                       :predicate :core/prefers
                       :object "trunk-based development" :object-kind :literal
                       :source-type :user-assertion
                       :confidence 0.6})
    ((juxt :status (comp :confidence :fact))))

;; Stronger evidence raises it, but only to the source's ceiling
;; (0.9 for user assertions, so 0.95 in does not mean 0.95 out):

(-> (core/assert-fact store
                      {:subject "team"
                       :predicate :core/prefers
                       :object "trunk-based development" :object-kind :literal
                       :source-type :user-assertion
                       :confidence 0.95})
    ((juxt :status (comp :confidence :fact))))

;; ## Trust defense one: outranked writes cannot supersede
;;
;; The code ingester established the current version at trust rank 3:

(core/assert-fact store
                  {:subject "AuthService" :predicate :core/has-version
                   :object "2.0.0" :object-kind :literal
                   :source-type :code :confidence 0.95})

;; An agent note (trust rank 1) claims a different version. On a
;; single-valued predicate that would normally supersede, but a lower-trust
;; source never silently closes a higher-trust fact's interval:

(-> (core/assert-fact store
                      {:subject "AuthService" :predicate :core/has-version
                       :object "3.0.0" :object-kind :literal
                       :source-type :agent-note})
    (select-keys [:status :reason]))

;; ## Trust defense two: revenants
;;
;; This is the write pattern memory poisoning actually takes: resurrect a
;; dead value from a low-trust source. The project deployed via Heroku, then
;; migrated:

(def heroku
  (:fact (core/assert-fact store
                           {:subject "shoply" :predicate :core/deployed-via
                            :object "Heroku" :object-kind :literal
                            :source-type :user-assertion})))

(core/invalidate store {:fact-id (:id heroku)
                        :reason "migrated to Fly"})

(core/assert-fact store
                  {:subject "shoply" :predicate :core/deployed-via
                   :object "Fly" :object-kind :literal
                   :source-type :user-assertion})

;; Now a planted note re-asserts Heroku. `deployed-via` is multi-valued, so
;; without a defense the store would happily hold both and a compiled view
;; could cite either. Instead: this (subject, predicate) already lived
;; through Heroku and invalidated it, a live rival exists, and the writer is
;; low-trust. The resurrection flags against the rival:

(def planted
  (core/assert-fact store
                    {:subject "shoply" :predicate :core/deployed-via
                     :object "Heroku" :object-kind :literal
                     :source-type :agent-note}))

{:status (:status planted)
 :reason (:reason planted)
 :rivals (mapv brief (:candidates planted))}

;; The disputed pair sits in the open-conflict queue, and (as the ambient
;; chapter shows) disputed facts are excluded from the compiled current-truth
;; view. The poison can still be seen; it can no longer be believed by
;; default. The benchmark chapter measures what this defense is worth on an
;; end task.
;;
;; ## Forgetting: decay is computed, never stored
;;
;; Effective confidence is a pure function of the fact and the clock, so it
;; can be shown without waiting 180 days. A session-derived fact at base
;; 0.7, read at increasing distances from its last reinforcement:

(let [fact {:confidence 0.7
            :epistemic :observation
            :source-type :session-log
            :recorded-at #inst "2026-01-01"
            :last-reinforced-at #inst "2026-01-01"}
      at (fn [t] (logic/effective-confidence fact t))]
  {:fresh          (at #inst "2026-01-01")
   :after-90-days  (at #inst "2026-04-01")
   :after-180-days (at #inst "2026-06-30")
   :after-a-year   (at #inst "2027-01-01")})

;; One half-life per 90 days, with a floor at 0.05. A commitment with the
;; same timestamps does not move at all:

(logic/effective-confidence {:confidence 0.9
                             :epistemic :commitment
                             :source-type :decision-record
                             :recorded-at #inst "2026-01-01"
                             :last-reinforced-at #inst "2026-01-01"}
                            #inst "2027-01-01")

;; Disuse is not falsity. Falsity is handled by invalidation (mechanical,
;; from the code ingester's reconciliation, or explicit); disuse only sinks
;; a fact's rank until something restates it, retrieves it in accepted work
;; (`claim outcome accepted`), or lets it rest at the floor.
