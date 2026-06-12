(ns memgraph.store.memory
  "In-memory Store implementation backed by an atom of plain maps. Exists to
  validate the storage abstraction and to run the test suite without the
  Datalevin pod.

  Facts are stored in the wire shape but their :subject/:object-ref entity
  maps are joined against the live entity table at read time — mirroring the
  ref semantics of the Datalevin store, so renames and merges are reflected
  in previously-written facts in both backends."
  (:require [clojure.string :as str]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(defn- index-key [name scope] [name scope])

(defn- hydrate [st f]
  (cond-> f
    (:subject f)
    (assoc :subject (get-in st [:entities (get-in f [:subject :id])] (:subject f)))
    (:object-ref f)
    (assoc :object-ref (get-in st [:entities (get-in f [:object-ref :id])] (:object-ref f)))))

(defn- facts-for [st entity-ids {:keys [direction predicate]}]
  (let [ids (set entity-ids)
        direction (or direction :out)
        preds (when predicate (if (coll? predicate) (set predicate) #{predicate}))
        out? #(ids (get-in % [:subject :id]))
        in? #(ids (get-in % [:object-ref :id]))
        dir-pred (case direction
                   :out out?
                   :in in?
                   :both #(or (out? %) (in? %)))]
    (cond->> (filter dir-pred (vals (:facts st)))
      preds (filter (comp preds :predicate))
      true (map #(hydrate st %)))))

(defn- substring-match [q s]
  (and s (str/includes? (str/lower-case (str s)) (str/lower-case q))))

(defrecord MemStore [state]
  store/Store
  (-ensure-entity [_ {:keys [name type scope]}]
    (let [k (index-key name scope)]
      (-> (swap! state
                 (fn [st]
                   (if-let [id (get-in st [:by-name-scope k])]
                     (cond-> st
                       (and type (nil? (get-in st [:entities id :type])))
                       (assoc-in [:entities id :type] type))
                     (let [id (str "e-" (random-uuid))]
                       (-> st
                           (assoc-in [:entities id]
                                     {:id id :name name :type type :scope scope :aliases []})
                           (assoc-in [:by-name-scope k] id))))))
          (as-> st (get-in st [:entities (get-in st [:by-name-scope k])])))))

  (-get-entity [_ name scope]
    (let [st @state]
      (get-in st [:entities (get-in st [:by-name-scope (index-key name scope)])])))

  (-find-entities [_ name scope]
    (let [norm (logic/normalize-entity-name name)
          matches? (fn [e]
                     (or (= name (:name e))
                         (some #{name} (:aliases e))
                         (= norm (logic/normalize-entity-name (:name e)))
                         (some #(= norm (logic/normalize-entity-name %)) (:aliases e))))]
      (->> (vals (:entities @state))
           (filter #(= scope (:scope %)))
           (filter matches?)
           vec)))

  (-update-entity [_ entity-id {:keys [name type add-aliases]}]
    (swap! state
           (fn [st]
             (let [e (get-in st [:entities entity-id])
                   renamed? (and name (not= name (:name e)))]
               (cond-> (update-in st [:entities entity-id]
                                  (fn [e]
                                    (cond-> e
                                      name (assoc :name name)
                                      type (assoc :type type)
                                      (seq add-aliases)
                                      (update :aliases #(vec (distinct (into (vec %) add-aliases)))))))
                 renamed?
                 (-> (update :by-name-scope dissoc (index-key (:name e) (:scope e)))
                     (assoc-in [:by-name-scope (index-key name (:scope e))] entity-id))))))
    entity-id)

  (-repoint-facts [_ from-id to-id]
    (let [affected (->> (vals (:facts @state))
                        (filter #(or (= from-id (get-in % [:subject :id]))
                                     (= from-id (get-in % [:object-ref :id]))))
                        (mapv :id))]
      (swap! state update :facts
             (fn [facts]
               (reduce (fn [fs id]
                         (update fs id
                                 (fn [f]
                                   (cond-> f
                                     (= from-id (get-in f [:subject :id]))
                                     (assoc :subject {:id to-id})
                                     (= from-id (get-in f [:object-ref :id]))
                                     (assoc :object-ref {:id to-id})))))
                       facts affected)))
      (count affected)))

  (-delete-entity [_ entity-id]
    (swap! state
           (fn [st]
             (let [e (get-in st [:entities entity-id])]
               (-> st
                   (update :entities dissoc entity-id)
                   (update :by-name-scope dissoc (index-key (:name e) (:scope e)))))))
    entity-id)

  (-list-entities [_ {:keys [type scope]}]
    (cond->> (vals (:entities @state))
      type (filter #(= type (:type %)))
      scope (filter #(= scope (:scope %)))
      true vec))

  (-insert-fact [_ fact]
    (swap! state assoc-in [:facts (:id fact)] fact)
    fact)

  (-get-facts [_ entity-id opts]
    (vec (facts-for @state [entity-id] opts)))

  (-get-facts-for [_ entity-ids opts]
    (vec (facts-for @state entity-ids opts)))

  (-get-history [_ entity-id predicate]
    (vec (facts-for @state [entity-id] {:direction :out :predicate predicate})))

  (-invalidate [_ fact-id at reason]
    (swap! state update-in [:facts fact-id]
           assoc :t-invalid at :invalidation-reason reason)
    fact-id)

  (-link-conflicts [_ fact-id conflict-ids]
    (swap! state update-in [:facts fact-id :conflicts]
           (fnil into []) conflict-ids)
    fact-id)

  (-unlink-conflicts [_ fact-id conflict-ids]
    (swap! state update-in [:facts fact-id :conflicts]
           (fn [ids] (vec (remove (set conflict-ids) ids))))
    fact-id)

  (-update-confidence [_ fact-id confidence]
    (swap! state assoc-in [:facts fact-id :confidence] (double confidence))
    fact-id)

  (-all-facts [_]
    (let [st @state]
      (mapv #(hydrate st %) (vals (:facts st)))))

  (-select-facts [_ {:keys [ids source-type predicates scopes episodes recorded-before conflicted valid-cheap]}]
    (let [st @state
          ids (some-> ids set)
          predicates (some-> predicates set)
          scopes (some-> scopes set)
          episodes (some-> episodes set)
          cut (some-> ^java.util.Date recorded-before .getTime)
          ms-of (fn [f] (or (some-> ^java.util.Date (:recorded-at f) .getTime) 0))]
      (->> (vals (:facts st))
           (filter #(and (or (nil? ids) (ids (:id %)))
                         (or (nil? source-type) (= source-type (:source-type %)))
                         (or (nil? predicates) (predicates (:predicate %)))
                         (or (nil? scopes) (scopes (:scope %)))
                         (or (nil? episodes) (episodes (:episode %)))
                         (or (nil? cut) (< (ms-of %) cut))
                         (or (not conflicted) (seq (:conflicts %)))
                         (or (not valid-cheap) (nil? (:t-invalid %)))))
           (mapv #(hydrate st %)))))

  (-predicate-usage [_]
    (frequencies (keep :predicate (vals (:facts @state)))))

  (-entity-usage [_]
    (reduce (fn [acc f]
              (reduce (fn [a id] (if id (update a id (fnil inc 0)) a))
                      acc
                      [(get-in f [:subject :id]) (get-in f [:object-ref :id])]))
            {}
            (vals (:facts @state))))

  (-open-episode [_ ep]
    (swap! state assoc-in [:episodes (:id ep)] ep)
    ep)

  (-close-episode [_ episode-id summary at]
    (swap! state update-in [:episodes episode-id]
           assoc :summary summary :closed-at at)
    episode-id)

  (-get-episode [_ episode-id]
    (get-in @state [:episodes episode-id]))

  (-list-episodes [_]
    (vec (vals (:episodes @state))))

  (-get-predicate [_ pred-id]
    (get-in @state [:predicates pred-id]))

  (-list-predicates [_ {:keys [category status]}]
    (cond->> (vals (:predicates @state))
      category (filter #(= category (:category %)))
      status (filter #(= status (:status %)))
      true (sort-by (comp str :id))
      true vec))

  (-register-predicate [_ pred]
    (swap! state update-in [:predicates (:id pred)] #(merge % pred))
    (get-in @state [:predicates (:id pred)]))

  (-search [_ query _opts]
    (let [st @state
          ent-match? (fn [e] (or (substring-match query (:name e))
                                 (some #(substring-match query %) (:aliases e))))]
      {:entities (vec (filter ent-match? (vals (:entities st))))
       :facts (->> (vals (:facts st))
                   (filter #(substring-match query (:object-lit %)))
                   (mapv #(hydrate st %)))
       :episodes (vec (filter #(substring-match query (:summary %)) (vals (:episodes st))))}))

  (-stats [_]
    (let [st @state
          facts (vals (:facts st))]
      {:entities (count (:entities st))
       :facts {:total (count facts)
               :valid (count (remove :t-invalid facts))
               :invalidated (count (filter :t-invalid facts))}
       :episodes (count (:episodes st))
       :predicates (frequencies (map :status (vals (:predicates st))))}))

  (-close [_] nil))

(defn create []
  (->MemStore (atom {:entities {} :by-name-scope {} :facts {} :episodes {} :predicates {}})))
