(ns memgraph.store.datalevin
  "Datalevin Store implementation, accessed as a Babashka pod. The pod binary
  is `dtlv` (a GraalVM native binary that speaks the pod protocol); override
  its location with the MEMGRAPH_DTLV env var. This is the only namespace that
  knows about datoms, Datalog, or :db/* anything.

  Pod discipline: the pod is a serialization boundary — push whole queries
  across and get result sets back, never loop chattily."
  (:require [babashka.pods :as pods]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(pods/load-pod (or (System/getenv "MEMGRAPH_DTLV") "dtlv"))

(require '[pod.huahaiy.datalevin :as d])

(def schema
  {;; ---- Entity ----
   :entity/id        {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :entity/name      {:db/valueType :db.type/string :db/fulltext true}
   :entity/type      {:db/valueType :db.type/keyword}
   :entity/scope     {:db/valueType :db.type/string}
   :entity/aliases   {:db/valueType :db.type/string :db/cardinality :db.cardinality/many
                      :db/fulltext true}
   ;; derived lookup fields for near-match resolution, maintained on write
   :entity/norm-name    {:db/valueType :db.type/string}
   :entity/norm-aliases {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}

   ;; ---- Fact (reified edge + metadata bundle) ----
   :fact/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :fact/subject     {:db/valueType :db.type/ref}
   :fact/predicate   {:db/valueType :db.type/keyword}
   :fact/object-ref  {:db/valueType :db.type/ref}
   :fact/object-lit  {:db/valueType :db.type/string :db/fulltext true}
   :fact/object-kind {:db/valueType :db.type/keyword}
   :fact/t-valid     {:db/valueType :db.type/instant}
   :fact/t-invalid   {:db/valueType :db.type/instant}
   :fact/recorded-at {:db/valueType :db.type/instant}
   :fact/confidence  {:db/valueType :db.type/double}
   :fact/source      {:db/valueType :db.type/ref}
   :fact/source-type {:db/valueType :db.type/keyword}
   :fact/epistemic   {:db/valueType :db.type/keyword}
   :fact/scope       {:db/valueType :db.type/string}
   :fact/conflicts   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :fact/invalidation-reason {:db/valueType :db.type/string}
   ;; reserved-but-unused: retrofitting an ACL dimension later is far more
   ;; painful than carrying nullable fields now
   :fact/read-acl    {:db/valueType :db.type/string}
   :fact/write-acl   {:db/valueType :db.type/string}

   ;; ---- Episode (provenance anchor) ----
   :episode/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :episode/source-type {:db/valueType :db.type/keyword}
   :episode/ref         {:db/valueType :db.type/string}
   :episode/summary     {:db/valueType :db.type/string :db/fulltext true}
   :episode/opened-at   {:db/valueType :db.type/instant}
   :episode/closed-at   {:db/valueType :db.type/instant}

   ;; ---- Predicate registry (self-describing vocabulary) ----
   :predicate/id          {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :predicate/label       {:db/valueType :db.type/string}
   :predicate/category    {:db/valueType :db.type/keyword}
   :predicate/object-kind {:db/valueType :db.type/keyword}
   :predicate/cardinality {:db/valueType :db.type/keyword}
   :predicate/inverse-of  {:db/valueType :db.type/keyword}
   :predicate/status      {:db/valueType :db.type/keyword}
   :predicate/replaced-by {:db/valueType :db.type/keyword}
   :predicate/definition  {:db/valueType :db.type/string}
   :predicate/maps-to     {:db/valueType :db.type/string}
   :predicate/default-epistemic {:db/valueType :db.type/keyword}
   :predicate/alt-labels  {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}})

;; ---- wire <-> datom translation -------------------------------------------

(def ^:private entity-pull
  [:entity/id :entity/name :entity/type :entity/scope :entity/aliases])

(def ^:private fact-pull
  [:fact/id :fact/predicate :fact/object-kind :fact/object-lit
   :fact/t-valid :fact/t-invalid :fact/recorded-at :fact/confidence
   :fact/epistemic :fact/scope :fact/source-type :fact/invalidation-reason
   {:fact/subject entity-pull}
   {:fact/object-ref entity-pull}
   {:fact/source [:episode/id]}
   {:fact/conflicts [:fact/id]}])

(defn- ent->wire [m]
  (when m
    {:id (:entity/id m) :name (:entity/name m)
     :type (:entity/type m) :scope (:entity/scope m)
     :aliases (vec (:entity/aliases m))}))

(defn- fact->wire [m]
  {:id (:fact/id m)
   :subject (ent->wire (:fact/subject m))
   :predicate (:fact/predicate m)
   :object-kind (:fact/object-kind m)
   :object-ref (ent->wire (:fact/object-ref m))
   :object-lit (:fact/object-lit m)
   :t-valid (:fact/t-valid m)
   :t-invalid (:fact/t-invalid m)
   :recorded-at (:fact/recorded-at m)
   :confidence (:fact/confidence m)
   :epistemic (:fact/epistemic m)
   :scope (:fact/scope m)
   :source-type (:fact/source-type m)
   :episode (get-in m [:fact/source :episode/id])
   :conflicts (mapv :fact/id (:fact/conflicts m))
   :invalidation-reason (:fact/invalidation-reason m)})

(defn- episode->wire [m]
  {:id (:episode/id m) :source-type (:episode/source-type m)
   :ref (:episode/ref m) :summary (:episode/summary m)
   :opened-at (:episode/opened-at m) :closed-at (:episode/closed-at m)})

(defn- pred->wire [m]
  (into {} (filter (comp some? val))
        {:id (:predicate/id m) :label (:predicate/label m)
         :category (:predicate/category m) :object-kind (:predicate/object-kind m)
         :cardinality (:predicate/cardinality m) :inverse-of (:predicate/inverse-of m)
         :status (:predicate/status m) :replaced-by (:predicate/replaced-by m)
         :definition (:predicate/definition m) :maps-to (:predicate/maps-to m)
         :default-epistemic (:predicate/default-epistemic m)}))

(defn- strip-nils [m] (into {} (filter (comp some? val)) m))

(defn- fact->tx [f]
  (strip-nils
   {:fact/id (:id f)
    :fact/subject [:entity/id (get-in f [:subject :id])]
    :fact/predicate (:predicate f)
    :fact/object-kind (:object-kind f)
    :fact/object-ref (when-let [o (:object-ref f)] [:entity/id (:id o)])
    :fact/object-lit (:object-lit f)
    :fact/t-valid (:t-valid f)
    :fact/t-invalid (:t-invalid f)
    :fact/recorded-at (:recorded-at f)
    :fact/confidence (:confidence f)
    :fact/epistemic (:epistemic f)
    :fact/scope (:scope f)
    :fact/source-type (:source-type f)
    :fact/source (when-let [ep (:episode f)] [:episode/id ep])}))

;; ---- queries ---------------------------------------------------------------

(defn- q-facts [db entity-id direction predicate]
  (let [out '[?f :fact/subject ?e]
        in '[?f :fact/object-ref ?e]
        runner (fn [clause]
                 (if predicate
                   (d/q [:find [(list 'pull '?f fact-pull) '...]
                         :in '$ '?eid '?pred
                         :where '[?e :entity/id ?eid] clause '[?f :fact/predicate ?pred]]
                        db entity-id predicate)
                   (d/q [:find [(list 'pull '?f fact-pull) '...]
                         :in '$ '?eid
                         :where '[?e :entity/id ?eid] clause]
                        db entity-id)))]
    (case direction
      :out (runner out)
      :in (runner in)
      :both (->> (concat (runner out) (runner in))
                 (reduce (fn [acc m] (assoc acc (:fact/id m) m)) {})
                 vals))))

(defrecord DatalevinStore [conn]
  store/Store
  (-ensure-entity [_ {:keys [name type scope]}]
    (let [db (d/db conn)
          eid (d/q '[:find ?e . :in $ ?n ?s
                     :where [?e :entity/name ?n] [?e :entity/scope ?s]]
                   db name scope)]
      (if eid
        (let [existing (d/pull db entity-pull eid)]
          (when (and type (nil? (:entity/type existing)))
            (d/transact! conn [{:db/id eid :entity/type type}]))
          (ent->wire (cond-> existing
                       (and type (nil? (:entity/type existing))) (assoc :entity/type type))))
        (let [ent (strip-nils {:entity/id (str "e-" (random-uuid))
                               :entity/name name :entity/type type :entity/scope scope
                               :entity/norm-name (logic/normalize-entity-name name)})]
          (d/transact! conn [ent])
          (ent->wire ent)))))

  (-get-entity [_ name scope]
    (some-> (d/q (into [:find (list 'pull '?e entity-pull) '.]
                       '[:in $ ?n ?s
                         :where [?e :entity/name ?n] [?e :entity/scope ?s]])
                 (d/db conn) name scope)
            ent->wire))

  (-find-entities [_ name scope]
    (let [db (d/db conn)
          norm (logic/normalize-entity-name name)
          q-attr (fn [attr v]
                   (d/q [:find '[?e ...] :in '$ '?v '?s
                         :where ['?e attr '?v] '[?e :entity/scope ?s]]
                        db v scope))]
      (->> (distinct (concat (q-attr :entity/name name)
                             (q-attr :entity/aliases name)
                             (q-attr :entity/norm-name norm)
                             (q-attr :entity/norm-aliases norm)))
           (mapv #(ent->wire (d/pull db entity-pull %))))))

  (-update-entity [_ entity-id {:keys [name type add-aliases]}]
    (d/transact! conn [(cond-> {:db/id [:entity/id entity-id]}
                         name (assoc :entity/name name
                                     :entity/norm-name (logic/normalize-entity-name name))
                         type (assoc :entity/type type)
                         (seq add-aliases)
                         (assoc :entity/aliases (vec add-aliases)
                                :entity/norm-aliases (mapv logic/normalize-entity-name
                                                           add-aliases)))])
    entity-id)

  (-repoint-facts [_ from-id to-id]
    (let [db (d/db conn)
          eid (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :entity/id ?id]] db id))
          from-eid (eid from-id)
          to-eid (eid to-id)
          subj (d/q '[:find [?f ...] :in $ ?e :where [?f :fact/subject ?e]] db from-eid)
          obj (d/q '[:find [?f ...] :in $ ?e :where [?f :fact/object-ref ?e]] db from-eid)]
      (d/transact! conn
                   (concat (map (fn [f] {:db/id f :fact/subject to-eid}) subj)
                           (map (fn [f] {:db/id f :fact/object-ref to-eid}) obj)))
      (count (distinct (concat subj obj)))))

  (-delete-entity [_ entity-id]
    (d/transact! conn [[:db/retractEntity [:entity/id entity-id]]])
    entity-id)

  (-list-entities [_ {:keys [type scope]}]
    (cond->> (map ent->wire
                  (d/q '[:find [(pull ?e [:entity/id :entity/name :entity/type :entity/scope]) ...]
                         :where [?e :entity/id _]]
                       (d/db conn)))
      type (filter #(= type (:type %)))
      scope (filter #(= scope (:scope %)))
      true vec))

  (-insert-fact [_ fact]
    (d/transact! conn [(fact->tx fact)])
    fact)

  (-get-facts [_ entity-id opts]
    (mapv fact->wire
          (q-facts (d/db conn) entity-id (or (:direction opts) :out) (:predicate opts))))

  (-get-history [_ entity-id predicate]
    (mapv fact->wire (q-facts (d/db conn) entity-id :out predicate)))

  (-invalidate [_ fact-id at reason]
    (d/transact! conn [{:fact/id fact-id
                        :fact/t-invalid at
                        :fact/invalidation-reason reason}])
    fact-id)

  (-link-conflicts [_ fact-id conflict-ids]
    (d/transact! conn [{:fact/id fact-id
                        :fact/conflicts (mapv (fn [cid] [:fact/id cid]) conflict-ids)}])
    fact-id)

  (-unlink-conflicts [_ fact-id conflict-ids]
    (d/transact! conn (mapv (fn [cid]
                              [:db/retract [:fact/id fact-id]
                               :fact/conflicts [:fact/id cid]])
                            conflict-ids))
    fact-id)

  (-update-confidence [_ fact-id confidence]
    (d/transact! conn [{:fact/id fact-id :fact/confidence (double confidence)}])
    fact-id)

  (-all-facts [_]
    (mapv fact->wire
          (d/q (into [:find [(list 'pull '?f fact-pull) '...]]
                     [:where '[?f :fact/id _]])
               (d/db conn))))

  (-open-episode [_ ep]
    (d/transact! conn [(strip-nils {:episode/id (:id ep)
                                    :episode/source-type (:source-type ep)
                                    :episode/ref (:ref ep)
                                    :episode/opened-at (:opened-at ep)})])
    ep)

  (-close-episode [_ episode-id summary at]
    (d/transact! conn [{:episode/id episode-id
                        :episode/summary summary
                        :episode/closed-at at}])
    episode-id)

  (-get-episode [_ episode-id]
    (some-> (d/q '[:find (pull ?ep [*]) . :in $ ?id :where [?ep :episode/id ?id]]
                 (d/db conn) episode-id)
            episode->wire))

  (-list-episodes [_]
    (mapv episode->wire
          (d/q '[:find [(pull ?ep [*]) ...] :where [?ep :episode/id _]] (d/db conn))))

  (-get-predicate [_ pred-id]
    (some-> (d/q '[:find (pull ?p [*]) . :in $ ?id :where [?p :predicate/id ?id]]
                 (d/db conn) pred-id)
            pred->wire))

  (-list-predicates [_ {:keys [category status]}]
    (cond->> (map pred->wire
                  (d/q '[:find [(pull ?p [*]) ...] :where [?p :predicate/id _]] (d/db conn)))
      category (filter #(= category (:category %)))
      status (filter #(= status (:status %)))
      true (sort-by (comp str :id))
      true vec))

  (-register-predicate [_ pred]
    (d/transact! conn [(strip-nils
                        {:predicate/id (:id pred)
                         :predicate/label (:label pred)
                         :predicate/category (:category pred)
                         :predicate/object-kind (:object-kind pred)
                         :predicate/cardinality (:cardinality pred)
                         :predicate/inverse-of (:inverse-of pred)
                         :predicate/status (:status pred)
                         :predicate/replaced-by (:replaced-by pred)
                         :predicate/definition (:definition pred)
                         :predicate/maps-to (:maps-to pred)
                         :predicate/default-epistemic (:default-epistemic pred)})])
    pred)

  (-search [this query _opts]
    (let [db (d/db conn)
          eids (distinct (map first
                              (d/q '[:find ?e ?a ?v :in $ ?q
                                     :where [(fulltext $ ?q) [[?e ?a ?v]]]]
                                   db query)))
          pulled (map #(d/pull db '[*] %) eids)]
      {:entities (->> pulled (filter :entity/id) (mapv ent->wire))
       :facts (->> pulled
                   (filter :fact/id)
                   (mapv #(fact->wire (d/pull db fact-pull (:db/id %)))))
       :episodes (->> pulled (filter :episode/id) (mapv episode->wire))}))

  (-stats [_]
    (let [db (d/db conn)
          cnt (fn [attr] (or (d/q (into [:find '(count ?e) '.]
                                        [:where ['?e attr '_]]) db) 0))
          invalidated (or (d/q '[:find (count ?f) . :where [?f :fact/t-invalid _]] db) 0)
          total (cnt :fact/id)]
      {:entities (cnt :entity/id)
       :facts {:total total :valid (- total invalidated) :invalidated invalidated}
       :episodes (cnt :episode/id)
       :predicates (frequencies
                    (map second
                         (d/q '[:find ?p ?s :where [?p :predicate/id _] [?p :predicate/status ?s]] db)))}))

  (-close [_] (d/close conn)))

(defn open-store
  "Open (creating if needed) a Datalevin-backed store at path."
  [path]
  (->DatalevinStore (d/get-conn path schema)))
