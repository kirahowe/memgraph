(ns memgraph.logic
  "The functional core: pure decision logic over plain values. Nothing in
  this namespace touches a store, the clock, or a random-number generator —
  time and fresh ids arrive as arguments, store reads arrive as values, and
  writes leave as effect plans for the shell (memgraph.core) to execute.
  Every function here is referentially transparent (throws are deterministic)."
  (:require [clojure.string :as str]
            [memgraph.predicates :as preds]))

(def default-scope "project")

(defn fail [msg data]
  (throw (ex-info msg (assoc data :memgraph/error true))))

;; ---------------------------------------------------------------------------
;; Time (values in, booleans out)
;; ---------------------------------------------------------------------------

(defn ms ^long [^java.util.Date d] (.getTime d))

(defn fact-valid-at?
  "Valid-time check: t-valid <= at < t-invalid (open interval when no t-invalid)."
  [fact ^java.util.Date at]
  (let [tv (:t-valid fact) ti (:t-invalid fact)]
    (boolean (and tv
                  (<= (ms tv) (ms at))
                  (or (nil? ti) (> (ms ti) (ms at)))))))

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(defn ->kw
  "Normalize a CLI/JSON value to a keyword: \"core/depends-on\", \":core/depends-on\"
  and :core/depends-on all become :core/depends-on."
  [v]
  (cond
    (keyword? v) v
    (nil? v) nil
    :else (let [s (str/replace (str/trim (str v)) #"^:" "")]
            (when (seq s) (keyword s)))))

(defn normalize-keys
  "snake_case or kebab-case JSON keys -> kebab-case keywords."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) m))

(defn normalize-ingest-fact
  "Ingest payloads may say :class where the API says :epistemic."
  [m]
  (-> m
      (update :epistemic #(or % (:class m)))
      (dissoc :class)))

;; ---------------------------------------------------------------------------
;; Assertion decisions
;; ---------------------------------------------------------------------------

(def epistemic-classes #{:observation :commitment :preference})

(defn resolve-object-kind
  "Decide entity vs literal for the object. The :either heuristic needs to
  know whether a matching entity exists — the shell supplies that as a value
  so no store read happens in here."
  [pred explicit object-entity-exists?]
  (let [pk (:object-kind pred)]
    (when (and explicit (not= pk :either) (not= explicit pk))
      (fail (str "Predicate " (:id pred) " requires object-kind " (name pk))
            {:type :object-kind-mismatch :predicate (:id pred)
             :required pk :given explicit}))
    (case pk
      :entity :entity
      :literal :literal
      :either (or explicit (if object-entity-exists? :entity :literal)))))

(defn resolve-epistemic
  "Caller > predicate default > :observation."
  [pred explicit]
  (let [e (or explicit (:default-epistemic pred) :observation)]
    (if (epistemic-classes e)
      e
      (fail (str "Unknown epistemic class " e)
            {:type :invalid-epistemic :given e :allowed epistemic-classes}))))

(defn build-fact
  "Assemble the candidate fact. :id and :now are supplied by the shell so
  this stays deterministic."
  [{:keys [id now subject predicate object-kind object-ref object
           t-valid confidence epistemic scope source-type episode]}]
  {:id id
   :subject subject
   :predicate predicate
   :object-kind object-kind
   :object-ref object-ref
   :object-lit (when (= :literal object-kind) (str object))
   :t-valid (or t-valid now)
   :t-invalid nil
   :recorded-at now
   :confidence (double (or confidence 0.8))
   :epistemic epistemic
   :scope (or scope default-scope)
   :source-type (or source-type :user-assertion)
   :episode episode})

(defn- same-object-pred [fact]
  (if (= :entity (:object-kind fact))
    #(= (get-in % [:object-ref :id]) (get-in fact [:object-ref :id]))
    #(= (:object-lit %) (:object-lit fact))))

(defn conflict-policy
  "Default policy from epistemic class: a commitment on either side of the
  conflict flags (never silently overwrite a human decision); observations
  and preferences supersede cleanly with history retained."
  [new-epistemic conflicting override]
  (or override
      (if (or (= new-epistemic :commitment)
              (some #(= :commitment (:epistemic %)) conflicting))
        :flag
        :supersede)))

(defn decide-assert
  "The assertion decision, as data. Given the candidate fact, its predicate
  registry row, and the currently-valid facts for (subject, predicate),
  return an effect plan for the shell:

    {:action :noop      :existing fact}
    {:action :insert    :fact fact}
    {:action :supersede :fact fact :invalidate [fact-ids]}
    {:action :flag      :fact fact :link [fact-ids] :candidates [facts]}"
  [{:keys [fact pred existing on-conflict]}]
  (let [same? (same-object-pred fact)
        duplicate (first (filter same? existing))
        conflicting (when (= :one (:cardinality pred))
                      (vec (remove same? existing)))]
    (cond
      duplicate
      {:action :noop :existing duplicate}

      (seq conflicting)
      (case (conflict-policy (:epistemic fact) conflicting on-conflict)
        :supersede {:action :supersede :fact fact :invalidate (mapv :id conflicting)}
        :flag {:action :flag :fact fact
               :link (mapv :id conflicting) :candidates conflicting}
        :ignore {:action :insert :fact fact})

      :else
      {:action :insert :fact fact})))

;; ---------------------------------------------------------------------------
;; Predicate registration
;; ---------------------------------------------------------------------------

(defn prepare-registration
  "Normalize and validate a runtime predicate coinage. Only :x/* may be
  coined at runtime; :core/* is curated in the seed vocabulary."
  [pred]
  (let [id (->kw (:id pred))]
    (when-not (and id (namespace id))
      (fail "Predicate id must be namespaced, e.g. x/uses-pattern"
            {:type :invalid-predicate-id}))
    (when-not (preds/experimental? id)
      (fail "Only :x/* predicates may be registered at runtime; :core/* is curated"
            {:type :reserved-namespace :predicate id}))
    (merge (preds/auto-registration id)
           (->> (-> pred
                    (assoc :id id)
                    (update :object-kind ->kw)
                    (update :cardinality ->kw)
                    (update :default-epistemic ->kw))
                (filter (comp some? val))
                (into {})))))

;; ---------------------------------------------------------------------------
;; Read filters & traversal
;; ---------------------------------------------------------------------------

(defn fact-filter
  "Predicate over facts for reads: validity at :at, plus optional
  confidence/scope/predicate filters."
  [{:keys [at include-invalidated min-confidence scope predicate]}]
  (fn [f]
    (and (or include-invalidated (fact-valid-at? f at))
         (or (nil? min-confidence) (>= (:confidence f) (double min-confidence)))
         (or (nil? scope) (= scope (:scope f)))
         (or (nil? predicate) (= predicate (:predicate f))))))

(defn bfs-step
  "One BFS level, purely: fold this level's facts into the accumulated
  {:nodes :edges} and compute the next frontier. Only entity-kind objects
  are traversable; inverse direction comes from the shell fetching :both."
  [{:keys [nodes edges]} facts keep? next-depth]
  (let [fresh (->> facts (filter keep?) (remove (comp edges :id)))
        neighbors (->> fresh
                       (mapcat (juxt :subject :object-ref))
                       (remove nil?)
                       (remove (comp nodes :id))
                       (map #(assoc % :depth next-depth)))]
    {:nodes (into nodes (map (juxt :id identity)) neighbors)
     :edges (into edges (map (juxt :id identity)) fresh)
     :frontier (set (map :id neighbors))}))

(defn neighborhood-result [root {:keys [nodes edges]} depth]
  {:root root
   :depth depth
   :entities (vec (sort-by :depth (vals nodes)))
   :facts (vec (vals edges))})

;; ---------------------------------------------------------------------------
;; Entity resolution
;; ---------------------------------------------------------------------------

(defn normalize-entity-name
  "Equivalence class for near-match entity lookup: lowercase, separators
  stripped. \"AuthService\", \"auth-service\" and \"auth_service\" all
  normalize to \"authservice\"."
  [s]
  (-> (str s) str/lower-case (str/replace #"[\s\-_./]+" "")))

(defn pick-entity-match
  "Resolution order over candidate entities: exact name, exact alias, then a
  UNIQUE normalized match guarded by type compatibility (a namespace must not
  silently match a class). Ambiguity resolves to nothing — never guess.
  Returns {:entity e :via :exact|:alias|:normalized} or nil."
  [{:keys [name norm type]} candidates]
  (let [type-ok? (fn [e] (or (nil? type) (nil? (:type e)) (= type (:type e))))
        norm-of (fn [e] (cons (normalize-entity-name (:name e))
                              (map normalize-entity-name (:aliases e))))
        exact (first (filter #(= name (:name %)) candidates))
        alias-hit (first (filter #(some #{name} (:aliases %)) candidates))
        norm-hits (filterv #(and (type-ok? %) (some #{norm} (norm-of %)))
                           candidates)]
    (cond
      exact {:entity exact :via :exact}
      alias-hit {:entity alias-hit :via :alias}
      (= 1 (count norm-hits)) {:entity (first norm-hits) :via :normalized}
      :else nil)))

(defn entity-duplicate-clusters
  "Entities sharing a normalized name within a scope — merge candidates for
  human review."
  [entities]
  (->> entities
       (group-by (fn [e] [(:scope e) (normalize-entity-name (:name e))]))
       (keep (fn [[[scope norm] es]]
               (when (> (count es) 1)
                 {:normalized norm
                  :scope scope
                  :entities (mapv #(select-keys % [:id :name :type]) es)})))
       vec))

(defn collapse-duplicates-plan
  "After a merge repoints facts, the same claim can exist twice. Plan the
  collapse: among currently-valid facts identical in subject, predicate,
  object, scope and epistemic class, keep the earliest-recorded and
  invalidate the rest."
  [facts at]
  (->> facts
       (filter #(fact-valid-at? % at))
       (group-by (fn [f] [(get-in f [:subject :id])
                          (:predicate f)
                          (:object-kind f)
                          (or (get-in f [:object-ref :id]) (:object-lit f))
                          (:scope f)
                          (:epistemic f)]))
       vals
       (mapcat (fn [group]
                 (when (> (count group) 1)
                   (rest (sort-by (comp ms :recorded-at) group)))))
       (mapv :id)))

;; ---------------------------------------------------------------------------
;; Conflicts
;; ---------------------------------------------------------------------------

(defn open-conflicts
  "Conflict pairs still awaiting resolution: a flagged fact and the candidate
  it conflicts with, where both are valid at `at`. (Conflict links live on
  the newer fact, so :fact is always the newer side.)"
  [facts at]
  (let [by-id (into {} (map (juxt :id identity)) facts)]
    (vec (for [f facts
               :when (fact-valid-at? f at)
               cid (:conflicts f)
               :let [c (by-id cid)]
               :when (and c (fact-valid-at? c at))]
           {:fact f :candidate c}))))

;; ---------------------------------------------------------------------------
;; Maintenance plans
;; ---------------------------------------------------------------------------

(defn decay-plan
  "Soft forgetting, as a plan: which facts get which new confidence.
  Commitments and decision-record facts never decay."
  [facts {:keys [now older-than-days factor]}]
  (let [cutoff (- (ms now) (* 86400000 (long (or older-than-days 90))))
        factor (double (or factor 0.9))]
    (->> facts
         (filter #(fact-valid-at? % now))
         (remove #(= :commitment (:epistemic %)))
         (remove #(= :decision-record (:source-type %)))
         (filter #(< (ms (:recorded-at %)) cutoff))
         (mapv (fn [f] {:fact-id (:id f)
                        :confidence (max 0.05 (* factor (:confidence f)))})))))
