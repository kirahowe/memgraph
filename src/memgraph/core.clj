(ns memgraph.core
  "The imperative shell around the functional core (memgraph.logic). Each
  operation follows the same shape: gather reads from the Store, hand plain
  values to logic for a pure decision, execute the resulting effect plan.
  Mutation is concentrated here and in the store implementations; everything
  decision-shaped lives in memgraph.logic and memgraph.predicates."
  (:require [clojure.string :as str]
            [memgraph.logic :as logic]
            [memgraph.predicates :as preds]
            [memgraph.store :as store]))

(defn now ^java.util.Date [] (java.util.Date.))

(defn- new-id [prefix] (str prefix "-" (random-uuid)))

;; ---------------------------------------------------------------------------
;; Predicate registry
;; ---------------------------------------------------------------------------

(defn seed!
  "Idempotently install the core vocabulary."
  [s]
  (doseq [p preds/seed]
    (when-not (store/-get-predicate s (:id p))
      (store/-register-predicate s p)))
  {:status :seeded :predicates (count (store/-list-predicates s {}))})

(defn resolve-predicate-id
  "Normalize a predicate reference: underscores become hyphens (LLMs and
  shells both emit them) and bare names resolve to :core/* when such a core
  predicate exists."
  [s pred]
  (let [k (logic/->kw (when pred (str/replace (str pred) "_" "-")))]
    (when-not k (logic/fail "Missing predicate" {:type :missing-predicate}))
    (if (namespace k)
      k
      (let [core-k (keyword "core" (name k))]
        (if (store/-get-predicate s core-k) core-k k)))))

(defn- ensure-predicate!
  "Read the registry row, let the pure check decide, execute its verdict:
  use the row, register the first-use :x/* coinage, or throw (enriching
  unknown-predicate errors with :did-you-mean)."
  [s pred-id]
  (let [{:keys [ok register error]} (preds/check pred-id (store/-get-predicate s pred-id))]
    (cond
      ok ok
      register (store/-register-predicate s register)
      :else (logic/fail (:message error)
                        (cond-> (dissoc error :message)
                          (= :unknown-predicate (:type error))
                          (assoc :did-you-mean
                                 (preds/did-you-mean pred-id
                                                     (map :id (store/-list-predicates s {})))))))))

(defn list-predicates [s opts]
  (let [ps (store/-list-predicates s {:category (logic/->kw (:category opts))
                                      :status (logic/->kw (:status opts))})]
    (if (:usage opts)
      (let [counts (frequencies (map :predicate (store/-all-facts s)))]
        (mapv #(assoc % :usage (get counts (:id %) 0)) ps))
      (vec ps))))

(defn register-predicate [s pred]
  (store/-register-predicate s (logic/prepare-registration pred)))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(defn ensure-entity
  "Exact name+scope match or create. Entity resolution (renames, splits,
  aliases) will eventually live behind this seam."
  [s {:keys [name type scope]}]
  (when (str/blank? (str name))
    (logic/fail "Entity name required" {:type :missing-entity-name}))
  (store/-ensure-entity s {:name (str/trim (str name))
                           :type (logic/->kw type)
                           :scope (or scope logic/default-scope)}))

(defn require-entity [s name scope]
  (or (store/-get-entity s name (or scope logic/default-scope))
      (logic/fail (str "Entity not found: " name)
                  {:type :entity-not-found :entity name
                   :scope (or scope logic/default-scope)})))

;; ---------------------------------------------------------------------------
;; assert-fact: gather -> decide (pure) -> execute
;; ---------------------------------------------------------------------------

(defn- execute-assert! [s at {:keys [action fact existing invalidate link candidates]}]
  (case action
    :noop {:status :noop :fact existing}
    :insert {:status :created :fact (store/-insert-fact s fact)}
    :supersede (do (doseq [id invalidate]
                     (store/-invalidate s id at (str "superseded by " (:id fact))))
                   {:status :superseded
                    :fact (store/-insert-fact s fact)
                    :superseded invalidate})
    :flag (let [inserted (store/-insert-fact s fact)]
            (store/-link-conflicts s (:id inserted) link)
            {:status :flagged
             :fact (assoc inserted :conflicts link)
             :candidates candidates})))

(defn assert-fact
  "Insert a fact with full validation and conflict resolution.

  opts: :subject :subject-type :subject-scope
        :predicate :object :object-type :object-scope :object-kind
        :epistemic :scope :confidence :source-type :episode
        :on-conflict (:supersede | :flag | :ignore) :t-valid

  Returns {:status :created|:noop|:superseded|:flagged
           :fact <fact>
           :superseded [ids] / :candidates [conflicting facts]}"
  [s {:keys [subject subject-type subject-scope predicate
             object object-type object-scope object-kind
             epistemic scope confidence source-type episode on-conflict t-valid]}]
  (when (str/blank? (str object))
    (logic/fail "Object required" {:type :missing-object}))
  (let [pred-id (resolve-predicate-id s predicate)
        pred (ensure-predicate! s pred-id)
        obj-scope (or object-scope logic/default-scope)
        kind (logic/resolve-object-kind
              pred (logic/->kw object-kind)
              (some? (store/-get-entity s (str object) obj-scope)))
        subj (ensure-entity s {:name subject :type subject-type :scope subject-scope})
        obj-ent (when (= :entity kind)
                  (ensure-entity s {:name object :type object-type :scope obj-scope}))
        t-now (now)
        fact (logic/build-fact {:id (new-id "f")
                                :now t-now
                                :subject subj
                                :predicate pred-id
                                :object-kind kind
                                :object-ref obj-ent
                                :object object
                                :t-valid t-valid
                                :confidence confidence
                                :epistemic (logic/resolve-epistemic pred (logic/->kw epistemic))
                                :scope scope
                                :source-type (logic/->kw source-type)
                                :episode episode})
        existing (->> (store/-get-facts s (:id subj) {:direction :out :predicate pred-id})
                      (filterv #(logic/fact-valid-at? % t-now)))]
    (execute-assert! s t-now
                     (logic/decide-assert {:fact fact
                                           :pred pred
                                           :existing existing
                                           :on-conflict (logic/->kw on-conflict)}))))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn get-facts
  "Currently-valid (or as-of a timestamp) facts about an entity.
  opts: :entity :entity-scope :direction (:out default | :in | :both)
        :predicate :scope :as-of :include-invalidated :min-confidence"
  [s {:keys [entity entity-scope direction predicate] :as opts}]
  (let [e (require-entity s entity entity-scope)
        pred-id (when predicate (resolve-predicate-id s predicate))
        keep? (logic/fact-filter {:at (or (:as-of opts) (now))
                                  :include-invalidated (:include-invalidated opts)
                                  :min-confidence (:min-confidence opts)
                                  :scope (:scope opts)})]
    {:entity e
     :facts (->> (store/-get-facts s (:id e) {:direction (or (logic/->kw direction) :out)
                                              :predicate pred-id})
                 (filter keep?)
                 (sort-by (comp logic/ms :t-valid))
                 vec)}))

(defn get-neighborhood
  "BFS expansion to :depth, following entity-kind objects in both directions
  (computed inverses, not stored twins). The shell fetches each level; the
  fold into nodes/edges/frontier is pure (logic/bfs-step).
  opts: :entity :entity-scope :depth :as-of :scope :min-confidence :predicate"
  [s {:keys [entity entity-scope depth predicate] :as opts}]
  (let [root (require-entity s entity entity-scope)
        keep? (logic/fact-filter {:at (or (:as-of opts) (now))
                                  :min-confidence (:min-confidence opts)
                                  :scope (:scope opts)
                                  :predicate (when predicate (resolve-predicate-id s predicate))})
        max-depth (long (or depth 1))]
    (loop [state {:nodes {(:id root) (assoc root :depth 0)}
                  :edges {}
                  :frontier #{(:id root)}}
           d 0]
      (if (or (>= d max-depth) (empty? (:frontier state)))
        (logic/neighborhood-result root state d)
        (let [facts (mapcat #(store/-get-facts s % {:direction :both})
                            (:frontier state))]
          (recur (logic/bfs-step state facts keep? (inc d)) (inc d)))))))

(defn get-history
  "All versions of (subject, predicate), valid and invalidated, time-ordered.
  The single best demonstration of why this beats markdown."
  [s {:keys [subject subject-scope predicate]}]
  (let [e (require-entity s subject subject-scope)
        pred-id (resolve-predicate-id s predicate)]
    {:entity e
     :predicate pred-id
     :history (->> (store/-get-history s (:id e) pred-id)
                   (sort-by (comp logic/ms :t-valid))
                   vec)}))

(defn search [s query opts]
  (store/-search s query opts))

(defn invalidate [s {:keys [fact-id reason]}]
  (when (str/blank? (str fact-id))
    (logic/fail "fact-id required" {:type :missing-fact-id}))
  (store/-invalidate s fact-id (now) (or reason "manually invalidated"))
  {:status :invalidated :fact-id fact-id})

;; ---------------------------------------------------------------------------
;; Episodes & ingestion
;; ---------------------------------------------------------------------------

(defn open-episode [s {:keys [source-type ref]}]
  (store/-open-episode s {:id (new-id "ep")
                          :source-type (or (logic/->kw source-type) :session-log)
                          :ref (str ref)
                          :opened-at (now)}))

(defn close-episode [s {:keys [episode summary]}]
  (when-not (store/-get-episode s episode)
    (logic/fail (str "Episode not found: " episode)
                {:type :episode-not-found :episode episode}))
  (store/-close-episode s episode (or summary "") (now))
  {:status :closed :episode episode})

(defn ingest
  "Batch-assert facts under one episode, each through the full conflict
  machinery. Returns per-status counts plus flagged/error details.
  (v0 deviation: per-fact transactions, not one batch transaction — conflict
  policy per fact takes precedence over batch atomicity for now.)"
  [s {:keys [episode source-type ref]} fact-maps]
  (let [ep (if episode
             (or (store/-get-episode s episode)
                 (logic/fail (str "Episode not found: " episode) {:type :episode-not-found}))
             (open-episode s {:source-type source-type :ref (or ref "ingest")}))
        results (mapv (fn [m]
                        (try
                          (let [r (assert-fact s (-> m
                                                     logic/normalize-ingest-fact
                                                     (assoc :episode (:id ep))))]
                            (-> (select-keys r [:status :superseded :candidates])
                                (assoc :fact-id (get-in r [:fact :id]))))
                          (catch clojure.lang.ExceptionInfo e
                            {:status :error :message (ex-message e) :input m})))
                      fact-maps)]
    {:episode (:id ep)
     :total (count results)
     :counts (frequencies (map :status results))
     :flagged (vec (filter #(= :flagged (:status %)) results))
     :errors (vec (filter #(= :error (:status %)) results))}))

;; ---------------------------------------------------------------------------
;; Maintenance
;; ---------------------------------------------------------------------------

(defn decay
  "Soft forgetting: compute the plan purely over all facts, then apply it.
  opts {:older-than-days N :factor f}."
  [s opts]
  (let [plan (logic/decay-plan (store/-all-facts s) (assoc opts :now (now)))]
    (doseq [{:keys [fact-id confidence]} plan]
      (store/-update-confidence s fact-id confidence))
    {:status :decayed :affected (count plan)}))

(defn consolidate
  "Dreaming-style offline consolidation. Defined in the surface, stubbed for
  MVP — the seam exists so adding an LLM judge changes no API."
  [s _opts]
  {:status :not-implemented
   :hint "Consolidation (episode summarization, pattern promotion) lands with the pluggable LLM judge."
   :open-episodes (->> (store/-list-episodes s) (remove :closed-at) (mapv :id))})

(defn stats [s]
  (store/-stats s))

(defn dump
  "Export everything as a seq of typed records (the portability path)."
  [s]
  (concat
   (map #(assoc % :type "predicate") (store/-list-predicates s {}))
   (map #(assoc % :type "entity") (store/-list-entities s {}))
   (map #(assoc % :type "episode") (store/-list-episodes s))
   (map #(assoc % :type "fact") (store/-all-facts s))))
