(ns memgraph.store.memory
  "In-memory Store implementation backed by an atom of plain maps. Exists to
  validate the storage abstraction and to run the test suite without the
  Datalevin pod. Facts are stored directly in the wire shape."
  (:require [clojure.string :as str]
            [memgraph.store :as store]))

(defn- index-key [name scope] [name scope])

(defn- facts-for [state entity-id {:keys [direction predicate]}]
  (let [direction (or direction :out)
        fs (vals (:facts state))
        out? #(= entity-id (get-in % [:subject :id]))
        in? #(= entity-id (get-in % [:object-ref :id]))
        dir-pred (case direction
                   :out out?
                   :in in?
                   :both #(or (out? %) (in? %)))]
    (cond->> (filter dir-pred fs)
      predicate (filter #(= predicate (:predicate %))))))

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
                           (assoc-in [:entities id] {:id id :name name :type type :scope scope})
                           (assoc-in [:by-name-scope k] id))))))
          (as-> st (get-in st [:entities (get-in st [:by-name-scope k])])))))

  (-get-entity [_ name scope]
    (let [st @state]
      (get-in st [:entities (get-in st [:by-name-scope (index-key name scope)])])))

  (-list-entities [_ {:keys [type scope]}]
    (cond->> (vals (:entities @state))
      type (filter #(= type (:type %)))
      scope (filter #(= scope (:scope %)))
      true vec))

  (-insert-fact [_ fact]
    (swap! state assoc-in [:facts (:id fact)] fact)
    fact)

  (-get-facts [_ entity-id opts]
    (vec (facts-for @state entity-id opts)))

  (-get-history [_ entity-id predicate]
    (vec (facts-for @state entity-id {:direction :out :predicate predicate})))

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
    (vec (vals (:facts @state))))

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
    (let [st @state]
      {:entities (vec (filter #(substring-match query (:name %)) (vals (:entities st))))
       :facts (vec (filter #(substring-match query (:object-lit %)) (vals (:facts st))))
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
