(ns memgraph.core
  "Storage-agnostic core operations. This layer owns all semantics —
  predicate validation, epistemic-class resolution, conflict policy,
  bi-temporal validity filtering, neighborhood traversal — and never sees a
  datom or a Datalog query. It talks to storage exclusively through the
  memgraph.store/Store protocol, in plain maps."
  (:require [clojure.string :as str]
            [memgraph.predicates :as preds]
            [memgraph.store :as store]))

(defn now ^java.util.Date [] (java.util.Date.))

(defn- ms ^long [^java.util.Date d] (.getTime d))

(defn fact-valid-at?
  "Valid-time check: t-valid <= at < t-invalid (open interval when no t-invalid)."
  [fact ^java.util.Date at]
  (let [tv (:t-valid fact) ti (:t-invalid fact)]
    (boolean (and tv
                  (<= (ms tv) (ms at))
                  (or (nil? ti) (> (ms ti) (ms at)))))))

(defn ->kw
  "Normalize a CLI/JSON value to a keyword: \"core/depends-on\", \":core/depends-on\"
  and :core/depends-on all become :core/depends-on."
  [v]
  (cond
    (keyword? v) v
    (nil? v) nil
    :else (let [s (str/replace (str/trim (str v)) #"^:" "")]
            (when (seq s) (keyword s)))))

(defn fail [msg data]
  (throw (ex-info msg (assoc data :memgraph/error true))))

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
  "Normalize and resolve a predicate reference. Bare names (\"depends-on\")
  resolve to :core/* when such a core predicate exists."
  [s pred]
  (let [k (->kw pred)]
    (when-not k (fail "Missing predicate" {:type :missing-predicate}))
    (if (namespace k)
      k
      (let [core-k (keyword "core" (name k))]
        (if (store/-get-predicate s core-k) core-k k)))))

(defn validate-predicate!
  "Return the registry row for pred-id. Unknown :x/* predicates auto-register
  with :testing status; any other unknown predicate throws with :did-you-mean.
  Deprecated predicates throw with the :replaced-by pointer."
  [s pred-id]
  (if-let [p (store/-get-predicate s pred-id)]
    (if (= :deprecated (:status p))
      (fail (str "Predicate " pred-id " is deprecated")
            {:type :deprecated-predicate :predicate pred-id :replaced-by (:replaced-by p)})
      p)
    (if (preds/experimental? pred-id)
      (store/-register-predicate s (preds/auto-registration pred-id))
      (fail (str "Unknown predicate " pred-id)
            {:type :unknown-predicate
             :predicate pred-id
             :did-you-mean (preds/did-you-mean pred-id (map :id (store/-list-predicates s {})))}))))

(defn list-predicates [s opts]
  (let [ps (store/-list-predicates s {:category (->kw (:category opts))
                                      :status (->kw (:status opts))})]
    (if (:usage opts)
      (let [counts (frequencies (map :predicate (store/-all-facts s)))]
        (mapv #(assoc % :usage (get counts (:id %) 0)) ps))
      (vec ps))))

(defn register-predicate
  "Manually coin a predicate. Only :x/* may be coined at runtime; :core/* is
  curated in the seed vocabulary."
  [s pred]
  (let [id (->kw (:id pred))]
    (when-not (and id (namespace id))
      (fail "Predicate id must be namespaced, e.g. x/uses-pattern" {:type :invalid-predicate-id}))
    (when-not (preds/experimental? id)
      (fail "Only :x/* predicates may be registered at runtime; :core/* is curated"
            {:type :reserved-namespace :predicate id}))
    (store/-register-predicate
     s (merge (preds/auto-registration id)
              (-> pred
                  (assoc :id id)
                  (update :object-kind ->kw)
                  (update :cardinality ->kw)
                  (update :default-epistemic ->kw)
                  (->> (into {} (filter (comp some? val)))))))))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(def default-scope "project")

(defn ensure-entity
  "Exact name+scope match or create. Entity resolution (renames, splits,
  aliases) will eventually live behind this seam."
  [s {:keys [name type scope]}]
  (when (str/blank? (str name))
    (fail "Entity name required" {:type :missing-entity-name}))
  (store/-ensure-entity s {:name (str/trim (str name))
                           :type (->kw type)
                           :scope (or scope default-scope)}))

(defn require-entity [s name scope]
  (or (store/-get-entity s name (or scope default-scope))
      (fail (str "Entity not found: " name)
            {:type :entity-not-found :entity name :scope (or scope default-scope)})))

;; ---------------------------------------------------------------------------
;; assert-fact — validation + conflict policy
;; ---------------------------------------------------------------------------

(def epistemic-classes #{:observation :commitment :preference})

(defn- resolve-object-kind [s pred object obj-scope explicit]
  (let [explicit (->kw explicit)
        pk (:object-kind pred)]
    (when (and explicit (not= pk :either) (not= explicit pk))
      (fail (str "Predicate " (:id pred) " requires object-kind " (name pk))
            {:type :object-kind-mismatch :predicate (:id pred)
             :required pk :given explicit}))
    (case pk
      :entity :entity
      :literal :literal
      :either (or explicit
                  (if (store/-get-entity s (str object) obj-scope) :entity :literal)))))

(defn- same-object? [kind obj-ent object]
  (fn [f]
    (if (= kind :entity)
      (= (get-in f [:object-ref :id]) (:id obj-ent))
      (= (:object-lit f) (str object)))))

(defn- conflict-policy
  "Default policy from epistemic class: a commitment on either side of the
  conflict flags (never silently overwrite a human decision); observations and
  preferences supersede cleanly with history retained."
  [new-epistemic conflicting override]
  (or override
      (if (or (= new-epistemic :commitment)
              (some #(= :commitment (:epistemic %)) conflicting))
        :flag
        :supersede)))

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
    (fail "Object required" {:type :missing-object}))
  (let [pred-id (resolve-predicate-id s predicate)
        pred (validate-predicate! s pred-id)
        obj-scope (or object-scope default-scope)
        kind (resolve-object-kind s pred object obj-scope object-kind)
        subj (ensure-entity s {:name subject :type subject-type :scope subject-scope})
        obj-ent (when (= kind :entity)
                  (ensure-entity s {:name object :type object-type :scope obj-scope}))
        epistemic (or (->kw epistemic) (:default-epistemic pred) :observation)
        _ (when-not (epistemic-classes epistemic)
            (fail (str "Unknown epistemic class " epistemic)
                  {:type :invalid-epistemic :given epistemic :allowed epistemic-classes}))
        t-now (now)
        same? (same-object? kind obj-ent object)
        existing (->> (store/-get-facts s (:id subj) {:direction :out :predicate pred-id})
                      (filter #(fact-valid-at? % t-now)))
        duplicate (first (filter same? existing))
        conflicting (when (= :one (:cardinality pred)) (vec (remove same? existing)))
        fact {:id (str "f-" (random-uuid))
              :subject subj
              :predicate pred-id
              :object-kind kind
              :object-ref obj-ent
              :object-lit (when (= kind :literal) (str object))
              :t-valid (or t-valid t-now)
              :t-invalid nil
              :recorded-at t-now
              :confidence (double (or confidence 0.8))
              :epistemic epistemic
              :scope (or scope default-scope)
              :source-type (or (->kw source-type) :user-assertion)
              :episode episode}]
    (cond
      duplicate
      {:status :noop :fact duplicate}

      (seq conflicting)
      (case (conflict-policy epistemic conflicting (->kw on-conflict))
        :supersede
        (do (doseq [c conflicting]
              (store/-invalidate s (:id c) t-now (str "superseded by " (:id fact))))
            {:status :superseded
             :fact (store/-insert-fact s fact)
             :superseded (mapv :id conflicting)})
        :flag
        (let [inserted (store/-insert-fact s fact)
              ids (mapv :id conflicting)]
          (store/-link-conflicts s (:id inserted) ids)
          {:status :flagged
           :fact (assoc inserted :conflicts ids)
           :candidates conflicting})
        :ignore
        {:status :created :fact (store/-insert-fact s fact)})

      :else
      {:status :created :fact (store/-insert-fact s fact)})))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn- fact-filter [{:keys [as-of include-invalidated min-confidence scope predicate]}]
  (let [at (or as-of (now))]
    (fn [f]
      (and (or include-invalidated (fact-valid-at? f at))
           (or (nil? min-confidence) (>= (:confidence f) (double min-confidence)))
           (or (nil? scope) (= scope (:scope f)))
           (or (nil? predicate) (= predicate (:predicate f)))))))

(defn get-facts
  "Currently-valid (or as-of a timestamp) facts about an entity.
  opts: :entity :entity-scope :direction (:out default | :in | :both)
        :predicate :scope :as-of :include-invalidated :min-confidence"
  [s {:keys [entity entity-scope direction predicate] :as opts}]
  (let [e (require-entity s entity entity-scope)
        pred-id (when predicate (resolve-predicate-id s predicate))
        facts (store/-get-facts s (:id e) {:direction (or (->kw direction) :out)
                                           :predicate pred-id})]
    {:entity e
     :facts (->> facts
                 (filter (fact-filter (assoc opts :predicate nil)))
                 (sort-by (comp ms :t-valid))
                 vec)}))

(defn get-neighborhood
  "BFS expansion to :depth, following entity-kind objects in both directions
  (computed inverses, not stored twins). Temporally and confidence-filtered.
  opts: :entity :entity-scope :depth :as-of :scope :min-confidence :predicate"
  [s {:keys [entity entity-scope depth] :as opts}]
  (let [root (require-entity s entity entity-scope)
        keep? (fact-filter (update opts :predicate #(when % (resolve-predicate-id s %))))
        max-depth (long (or depth 1))]
    (loop [frontier #{(:id root)}
           nodes {(:id root) (assoc root :depth 0)}
           edges {}
           d 0]
      (if (or (>= d max-depth) (empty? frontier))
        {:root root
         :depth d
         :entities (vec (sort-by :depth (vals nodes)))
         :facts (vec (vals edges))}
        (let [facts (->> frontier
                         (mapcat #(store/-get-facts s % {:direction :both}))
                         (filter keep?)
                         (remove (comp edges :id)))
              neighbors (->> facts
                             (mapcat (juxt :subject :object-ref))
                             (remove nil?)
                             (remove (comp nodes :id))
                             (map #(assoc % :depth (inc d))))]
          (recur (set (map :id neighbors))
                 (into nodes (map (juxt :id identity)) neighbors)
                 (into edges (map (juxt :id identity)) facts)
                 (inc d)))))))

(defn get-history
  "All versions of (subject, predicate), valid and invalidated, time-ordered.
  The single best demonstration of why this beats markdown."
  [s {:keys [subject subject-scope predicate]}]
  (let [e (require-entity s subject subject-scope)
        pred-id (resolve-predicate-id s predicate)]
    {:entity e
     :predicate pred-id
     :history (->> (store/-get-history s (:id e) pred-id)
                   (sort-by (comp ms :t-valid))
                   vec)}))

(defn search [s query opts]
  (store/-search s query opts))

(defn invalidate [s {:keys [fact-id reason]}]
  (when (str/blank? (str fact-id))
    (fail "fact-id required" {:type :missing-fact-id}))
  (store/-invalidate s fact-id (now) (or reason "manually invalidated"))
  {:status :invalidated :fact-id fact-id})

;; ---------------------------------------------------------------------------
;; Episodes & ingestion
;; ---------------------------------------------------------------------------

(defn open-episode [s {:keys [source-type ref]}]
  (store/-open-episode s {:id (str "ep-" (random-uuid))
                          :source-type (or (->kw source-type) :session-log)
                          :ref (str ref)
                          :opened-at (now)}))

(defn close-episode [s {:keys [episode summary]}]
  (when-not (store/-get-episode s episode)
    (fail (str "Episode not found: " episode) {:type :episode-not-found :episode episode}))
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
                 (fail (str "Episode not found: " episode) {:type :episode-not-found}))
             (open-episode s {:source-type source-type :ref (or ref "ingest")}))
        results (mapv (fn [m]
                        (try
                          (let [r (assert-fact s (assoc m :episode (:id ep)))]
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
  "Soft forgetting: reduce confidence of stale facts. Commitments and
  decision-record facts never decay. opts {:older-than-days N :factor f}."
  [s {:keys [older-than-days factor]}]
  (let [cutoff (- (ms (now)) (* 86400000 (long (or older-than-days 90))))
        factor (double (or factor 0.9))
        targets (->> (store/-all-facts s)
                     (filter #(fact-valid-at? % (now)))
                     (remove #(= :commitment (:epistemic %)))
                     (remove #(= :decision-record (:source-type %)))
                     (filter #(< (ms (:recorded-at %)) cutoff)))]
    (doseq [f targets]
      (store/-update-confidence s (:id f) (max 0.05 (* factor (:confidence f)))))
    {:status :decayed :affected (count targets)}))

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
