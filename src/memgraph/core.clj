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

(declare assert-fact)

(defn resolve-entity
  "Resolve a name to an entity: exact name, exact alias, then a unique
  case/separator-insensitive match (type-guarded). Returns
  {:entity e :via :exact|:alias|:normalized} or nil — ambiguity never
  guesses."
  [s {:keys [name scope type]}]
  (let [scope (or scope logic/default-scope)]
    (logic/pick-entity-match {:name name
                              :norm (logic/normalize-entity-name name)
                              :type (logic/->kw type)}
                             (store/-find-entities s name scope))))

(defn ensure-entity
  "Resolve or create. A resolution through normalization self-heals: the
  queried name is recorded as an alias so the next lookup is exact."
  [s {:keys [name type scope]}]
  (when (str/blank? (str name))
    (logic/fail "Entity name required" {:type :missing-entity-name}))
  (let [name (str/trim (str name))
        scope (or scope logic/default-scope)
        type (logic/->kw type)]
    (if-let [{:keys [entity via]} (resolve-entity s {:name name :scope scope :type type})]
      (do
        (when (= :normalized via)
          (store/-update-entity s (:id entity) {:add-aliases [name]}))
        (when (and type (nil? (:type entity)))
          (store/-update-entity s (:id entity) {:type type}))
        (cond-> entity
          (= :normalized via) (update :aliases (fnil conj []) name)
          (and type (nil? (:type entity))) (assoc :type type)))
      (store/-ensure-entity s {:name name :type type :scope scope}))))

(defn require-entity [s name scope]
  (or (:entity (resolve-entity s {:name name :scope scope}))
      (logic/fail (str "Entity not found: " name)
                  {:type :entity-not-found :entity name
                   :scope (or scope logic/default-scope)})))

(defn rename-entity
  "Rename in place: same entity, same facts, same history; the old name is
  kept as an alias."
  [s {:keys [from to scope]}]
  (when (str/blank? (str to))
    (logic/fail "New name required" {:type :missing-entity-name}))
  (let [scope (or scope logic/default-scope)
        e (require-entity s from scope)
        to (str/trim (str to))]
    (when-let [clash (store/-get-entity s to scope)]
      (when (not= (:id clash) (:id e))
        (logic/fail (str "Entity already exists: " to)
                    {:type :entity-exists :entity to
                     :hint "use `entity merge` to combine them"})))
    (store/-update-entity s (:id e) {:name to :add-aliases [(:name e)]})
    {:status :renamed
     :entity (-> e
                 (assoc :name to)
                 (update :aliases #(vec (distinct (conj (vec %) (:name e))))))}))

(defn alias-entity
  "Record an additional name for an entity."
  [s {:keys [name alias scope]}]
  (when (str/blank? (str alias))
    (logic/fail "Alias required" {:type :missing-alias}))
  (let [scope (or scope logic/default-scope)
        e (require-entity s name scope)
        alias (str/trim (str alias))]
    (when-let [clash (store/-get-entity s alias scope)]
      (when (not= (:id clash) (:id e))
        (logic/fail (str "Another entity is named " alias)
                    {:type :entity-exists :entity alias
                     :hint "use `entity merge` to combine them"})))
    (store/-update-entity s (:id e) {:add-aliases [alias]})
    {:status :aliased
     :entity (update e :aliases #(vec (distinct (conj (vec %) alias))))}))

(defn merge-entities
  "Two entities that turn out to be the same thing: repoint every fact from
  one onto the other, carry the merged-away names as aliases, drop the empty
  husk, and collapse the exact duplicates the repointing exposed (non-lossy —
  they are invalidated, not deleted)."
  [s {:keys [from into scope]}]
  (let [scope (or scope logic/default-scope)
        src (require-entity s from scope)
        dst (require-entity s into scope)]
    (when (= (:id src) (:id dst))
      (logic/fail "Cannot merge an entity into itself"
                  {:type :merge-self :entity (:name src)}))
    (let [t-now (now)
          repointed (store/-repoint-facts s (:id src) (:id dst))
          aliases (vec (distinct (remove #{(:name dst)}
                                         (cons (:name src) (:aliases src)))))]
      (when (seq aliases)
        (store/-update-entity s (:id dst) {:add-aliases aliases}))
      (store/-delete-entity s (:id src))
      (let [dups (logic/collapse-duplicates-plan
                  (store/-get-facts s (:id dst) {:direction :both})
                  t-now)]
        (doseq [id dups]
          (store/-invalidate s id t-now
                             (str "duplicate after merging " (:name src)
                                  " into " (:name dst))))
        {:status :merged
         :from (:name src)
         :into (:name dst)
         :facts-repointed repointed
         :duplicates-invalidated (count dups)
         :aliases-added aliases}))))

(defn split-entity
  "Record that an entity split into successors: derived-from lineage edges
  are asserted for each. Facts on the source stay put — which successor
  inherits a preference or a decision is a judgment call, so they are left
  for review rather than redistributed mechanically."
  [s {:keys [from into scope]}]
  (let [scope (or scope logic/default-scope)
        src (require-entity s from scope)
        names (->> (if (sequential? into) into (str/split (str into) #","))
                   (map str/trim)
                   (remove str/blank?)
                   vec)]
    (when (empty? names)
      (logic/fail "Successor names required, e.g. --into \"UserReadService,UserWriteService\""
                  {:type :missing-successors}))
    {:status :split
     :from (:name src)
     :into names
     :lineage (mapv (fn [n]
                      (get-in (assert-fact s {:subject n
                                              :subject-scope scope
                                              :predicate :core/derived-from
                                              :object (:name src)
                                              :object-scope scope
                                              :object-kind :entity})
                              [:fact :id]))
                    names)
     :note "facts on the source were left in place for review"}))

(defn entity-duplicates
  "Likely-duplicate entity clusters (shared normalized name within a scope) —
  candidates for `entity merge`."
  [s]
  (let [clusters (logic/entity-duplicate-clusters (store/-list-entities s {}))]
    {:clusters (count clusters)
     :candidates clusters}))

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
              (some? (resolve-entity s {:name (str object) :scope obj-scope})))
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

(defn conflicts
  "Open conflicts: flagged facts whose conflicting candidates are still
  valid, awaiting a judge or a human."
  [s]
  (let [open (logic/open-conflicts (store/-all-facts s) (now))]
    {:open (count open)
     :conflicts open}))

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
  machinery. Returns per-status counts plus flagged/error details. Runs
  per-fact transactions: applying conflict policy fact-by-fact takes
  precedence over batch atomicity."
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

(defn stats [s]
  (assoc (store/-stats s)
         :open-conflicts (count (logic/open-conflicts (store/-all-facts s) (now)))))

(defn dump
  "Export everything as a seq of typed records (the portability path)."
  [s]
  (concat
   (map #(assoc % :type "predicate") (store/-list-predicates s {}))
   (map #(assoc % :type "entity") (store/-list-entities s {}))
   (map #(assoc % :type "episode") (store/-list-episodes s))
   (map #(assoc % :type "fact") (store/-all-facts s))))
