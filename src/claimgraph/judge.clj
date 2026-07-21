(ns claimgraph.judge
  "LLM judge for the semantic-conflict path. Mechanical conflict detection
  (in assert-fact) flags; the judge classifies what the flag actually means:

    contradicts — genuinely incompatible; a human must decide
    duplicate   — the newer fact restates the established one
    supersedes  — the newer fact is the legitimate successor
    compatible  — both can hold; not actually in conflict

  Functional core / imperative shell: prompt construction, verdict parsing,
  and the resolution plan are pure; `judge-conflicts!` iterates the store's
  open conflicts through the pluggable LLM (same subscription-as-judge
  mechanism as the session extractor; tests inject :judge-fn).

  By default the judge only enriches — it reports verdicts and acts on
  nothing. With :resolve it executes the plan for verdicts at or above
  :min-confidence, and even then a contradicts verdict is never auto-resolved:
  surfacing those to the human is the point of the flag machinery."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def relations #{:contradicts :duplicate :supersedes :compatible})
(def default-min-confidence 0.8)

;; ---------------------------------------------------------------------------
;; Pure: prompt
;; ---------------------------------------------------------------------------

(defn fact->summary
  "Compact, prompt- and report-friendly view of a fact."
  [f]
  {:id (:id f)
   :subject (get-in f [:subject :name])
   :predicate (:predicate f)
   :object (if (= :entity (:object-kind f))
             (get-in f [:object-ref :name])
             (:object-lit f))
   :epistemic (:epistemic f)
   :source-type (:source-type f)
   :scope (:scope f)
   :confidence (:confidence f)
   :recorded-at (:recorded-at f)})

(defn judgment-prompt
  "Prompt for one conflict pair. pred-a/pred-b are the registry rows for the
  two facts' predicates — equal for mechanically-flagged pairs, possibly
  different for swept cross-predicate candidates."
  [{:keys [fact candidate]} pred-a pred-b]
  (let [pred-line (fn [p] (str "Predicate " (subs (str (:id p)) 1) ": " (:definition p)))]
    (str
     "Two facts about the same subject in a project knowledge graph were\n"
     "proposed as a possible conflict. Fact A is the newer assertion; fact B\n"
     "is the established one. Judge the semantic relationship of A to B.\n\n"
     "Respond with a single JSON object and nothing else — no prose, no fences:\n"
     "{\"relation\": \"contradicts\"|\"duplicate\"|\"supersedes\"|\"compatible\",\n"
     " \"confidence\": 0.0-1.0,\n"
     " \"rationale\": \"one sentence\"}\n\n"
     "Definitions:\n"
     "- contradicts: genuinely incompatible claims; a human must decide.\n"
     "- duplicate: A restates B in different words; A adds nothing.\n"
     "- supersedes: A is the legitimate successor of B; B is outdated.\n"
     "- compatible: both can be true at once; not actually in conflict.\n\n"
     (->> (distinct (keep #(when % (pred-line %)) [pred-a pred-b]))
          (str/join "\n"))
     "\n\nFact A (newer):\n" (json/generate-string (fact->summary fact))
     "\n\nFact B (established):\n" (json/generate-string (fact->summary candidate)) "\n")))

;; ---------------------------------------------------------------------------
;; Pure: verdict parsing & resolution plan
;; ---------------------------------------------------------------------------

(defn parse-judgment
  "Tolerant parse of the judge's response: first JSON object with a known
  relation wins. Unparseable responses become a zero-confidence verdict
  rather than an exception — one bad judgment must not kill the batch."
  [response]
  (or (->> (str/split-lines (or response ""))
           (map str/trim)
           (remove #(or (str/blank? %) (str/starts-with? % "```")))
           (keep #(try (let [m (json/parse-string % true)
                             relation (logic/->kw (:relation m))]
                         (when (relations relation)
                           {:relation relation
                            :confidence (let [c (:confidence m)]
                                          (if (number? c)
                                            (-> c double (max 0.0) (min 1.0))
                                            0.0))
                            :rationale (:rationale m)}))
                       (catch Exception _ nil)))
           first)
      {:relation :unparseable :confidence 0.0}))

(defn resolution-plan
  "Pure: verdict -> effect plan for one conflict pair.

    {:action :invalidate :fact-id id :reason str}
    {:action :unlink}
    {:action :none :reason :needs-human|:low-confidence|:unparseable}"
  [{:keys [fact candidate]} {:keys [relation confidence]} min-confidence]
  (cond
    (= :contradicts relation) {:action :none :reason :needs-human}
    (not (relations relation)) {:action :none :reason :unparseable}
    (< confidence min-confidence) {:action :none :reason :low-confidence}
    :else (case relation
            :duplicate {:action :invalidate :fact-id (:id fact)
                        :reason (str "judged duplicate of " (:id candidate))}
            :supersedes {:action :invalidate :fact-id (:id candidate)
                         :reason (str "judged superseded by " (:id fact))}
            :compatible {:action :unlink})))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn- execute-resolution! [s at {:keys [fact candidate]} plan]
  (case (:action plan)
    :invalidate (store/-invalidate s (:fact-id plan) at (:reason plan))
    :unlink (store/-unlink-conflicts s (:id fact) [(:id candidate)])
    nil))

(defn judge-conflicts!
  "Run the judge over every open conflict.
  opts: :command (LLM command string; default $CLAIMGRAPH_LLM_CMD or claude -p)
        :judge-fn (prompt -> response; injectable, used by tests)
        :resolve (execute resolution plans; default false = enrich only)
        :min-confidence (gate for acting on a verdict; default 0.8)"
  [s {:keys [command judge-fn resolve min-confidence]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        min-confidence (double (or min-confidence default-min-confidence))
        results
        (mapv (fn [{:keys [fact candidate] :as pair}]
                (let [verdict (parse-judgment
                               (run (judgment-prompt pair
                                                     (store/-get-predicate s (:predicate fact))
                                                     (store/-get-predicate s (:predicate candidate)))))
                      plan (resolution-plan pair verdict min-confidence)]
                  (when resolve
                    (execute-resolution! s at pair plan))
                  (cond-> {:fact (fact->summary fact)
                           :candidate (fact->summary candidate)
                           :verdict verdict
                           :plan plan}
                    resolve (assoc :executed (not= :none (:action plan))))))
              (:conflicts (core/conflicts s)))]
    {:conflicts (count results)
     :resolved (count (filter :executed results))
     :results results}))

(defn sweep-conflicts!
  "Deferred candidate generation: propose judgeable pairs the write path
  can't see (pure, per-subject bounded — logic/conflict-candidates), run each
  through the LLM verdict once, and link genuine hits into the same conflict
  pipeline. Compatible and unparseable verdicts are dropped silently — a
  noisy generator can't mutate anything. Linked contradictions surface in
  `conflicts` for the human; with :resolve, duplicate/supersedes verdicts at
  or above :min-confidence are executed immediately (same resolution plans
  as judge-conflicts!)."
  [s {:keys [command judge-fn resolve min-confidence]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        min-confidence (double (or min-confidence default-min-confidence))
        preds (store/-list-predicates s {})
        preds-by-id (into {} (map (juxt :id identity)) preds)
        watched (->> preds
                     (filter #(or (:exclusion-group %)
                                  (= :exclusive (:value-exclusivity %))
                                  (= :decision (:category %))))
                     (mapv :id))
        ;; two-step candidate fetch: watched facts find the interesting
        ;; subjects; those subjects' FULL out-fact sets (any predicate) give
        ;; the cross-predicate clause its partners. Bounded by subjects
        ;; holding decision-shaped facts, never the graph.
        seeds (if (seq watched)
                (store/-select-facts s {:valid-cheap true :predicates watched})
                [])
        subject-ids (distinct (map (comp :id :subject) seeds))
        facts (if (seq subject-ids)
                (store/-get-facts-for s subject-ids {:direction :out})
                [])
        results
        (mapv (fn [{:keys [fact candidate reason] :as pair}]
                (let [verdict (parse-judgment
                               (run (judgment-prompt pair
                                                     (preds-by-id (:predicate fact))
                                                     (preds-by-id (:predicate candidate)))))
                      hit? (#{:contradicts :duplicate :supersedes} (:relation verdict))
                      plan (when hit? (resolution-plan pair verdict min-confidence))]
                  (when hit?
                    (store/-link-conflicts s (:id fact) [(:id candidate)])
                    (when resolve
                      (execute-resolution! s at pair plan)))
                  (cond-> {:fact (fact->summary fact)
                           :candidate (fact->summary candidate)
                           :reason reason
                           :verdict verdict}
                    hit? (assoc :linked true :plan plan)
                    (and hit? resolve) (assoc :executed (not= :none (:action plan))))))
              (logic/conflict-candidates facts preds-by-id at))]
    {:candidates (count results)
     :linked (count (filter :linked results))
     :resolved (count (filter :executed results))
     :results results}))
