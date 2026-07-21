(ns claimgraph.oplog
  "Append-only effect logs with reconciliation: the multi-device story
  (roadmap #25 v2), shaped by local-first prior art rather than a
  hand-rolled CRDT. Three ideas carried over from that literature:

  1. Per-writer append-only logs. Every store mutation appends one effect
     line to <db>.oplog/<writer-id>.jsonl, stamped with a hybrid logical
     clock. Each device only ever appends to its own file, so any file
     syncer (git, rsync, Syncthing) moves logs between machines without a
     merge conflict existing even in principle.
  2. The store is a materialized view; the logs are the record. `reconcile`
     applies unseen foreign effects in canonical (hlc, writer, seq) order,
     tracked with per-writer high-water marks. Entity identity crosses
     machines by NAME, remapped the same way `load` does it, because ids
     are internal.
  3. Convergence through surfacing, not through merge magic. A CRDT forces
     agreement by construction; claimgraph wants disagreement made visible.
     After applying, reconcile collapses exact duplicate claims non-lossily
     and counts the conflict candidates the sweep should judge. Two
     machines asserting contradictory things end up with an open conflict
     for a human, which is the point of the whole system.

  The write lease (claimgraph.lease) still serializes writers on ONE machine;
  this file is about writers who never shared a machine to begin with."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Writer identity and the clock
;; ---------------------------------------------------------------------------

(defn oplog-dir [db] (str db ".oplog"))

(defn writer-id!
  "A stable id for this machine/agent: $CLAIMGRAPH_WRITER, or a uuid minted
  once and kept in <db>.oplog/writer."
  [db]
  (or (System/getenv "CLAIMGRAPH_WRITER")
      (let [f (fs/path (oplog-dir db) "writer")]
        (if (fs/exists? f)
          (str/trim (slurp (str f)))
          (let [id (str "w-" (random-uuid))]
            (fs/create-dirs (oplog-dir db))
            (spit (str f) id)
            id)))))

(defn- next-hlc!
  "Hybrid logical clock: wall time, but never backwards and never repeating
  within a writer."
  [clock]
  (swap! clock (fn [last] (max (System/currentTimeMillis) (inc last)))))

;; ---------------------------------------------------------------------------
;; Effect encoding (JSON-safe; rehydrated with the same machinery as load)
;; ---------------------------------------------------------------------------

(defn- record-out [type m] (assoc m :type (name type)))

(defn- rehydrate [payload]
  (second (logic/rehydrate-dump-record payload)))

(defn- ms-of [d] (some-> ^java.util.Date d .getTime))

;; ---------------------------------------------------------------------------
;; The logging decorator
;; ---------------------------------------------------------------------------

(defprotocol Logged
  (-inner [s] "The undecorated store, for replay paths that must not re-log."))

(defn inner-store [s]
  (if (satisfies? Logged s) (-inner s) s))

(defn- append! [{:keys [db writer clock counter]} effect]
  (try
    (let [dir (oplog-dir db)]
      (fs/create-dirs dir)
      (spit (str (fs/path dir (str writer ".jsonl")))
            (str (json/generate-string
                  (assoc effect :seq (swap! counter inc) :hlc (next-hlc! clock)))
                 "\n")
            :append true))
    (catch Exception _ nil)))

(defrecord LoggedStore [inner ctx]
  Logged
  (-inner [_] inner)

  store/Store
  (-ensure-entity [_ ent]
    (let [e (store/-ensure-entity inner ent)]
      (append! ctx {:t "ensure-entity" :entity (record-out :entity e)})
      e))
  (-get-entity [_ name scope] (store/-get-entity inner name scope))
  (-find-entities [_ name scope] (store/-find-entities inner name scope))
  (-update-entity [_ id updates]
    (append! ctx {:t "update-entity" :id id :updates updates})
    (store/-update-entity inner id updates))
  (-repoint-facts [_ from to]
    (append! ctx {:t "repoint-facts" :from from :to to})
    (store/-repoint-facts inner from to))
  (-repoint-predicate [_ from to]
    (append! ctx {:t "repoint-predicate" :from (str from) :to (str to)})
    (store/-repoint-predicate inner from to))
  (-delete-entity [_ id]
    (append! ctx {:t "delete-entity" :id id})
    (store/-delete-entity inner id))
  (-list-entities [_ opts] (store/-list-entities inner opts))
  (-insert-fact [_ fact]
    (append! ctx {:t "insert-fact" :fact (record-out :fact fact)})
    (store/-insert-fact inner fact))
  (-get-facts [_ id opts] (store/-get-facts inner id opts))
  (-get-facts-for [_ ids opts] (store/-get-facts-for inner ids opts))
  (-select-facts [_ criteria] (store/-select-facts inner criteria))
  (-predicate-usage [_] (store/-predicate-usage inner))
  (-entity-usage [_] (store/-entity-usage inner))
  (-get-history [_ id pred] (store/-get-history inner id pred))
  (-invalidate [_ fact-id at reason]
    (append! ctx {:t "invalidate" :fact-id fact-id :at (ms-of at) :reason reason})
    (store/-invalidate inner fact-id at reason))
  (-link-conflicts [_ fact-id ids]
    (append! ctx {:t "link-conflicts" :fact-id fact-id :ids (vec ids)})
    (store/-link-conflicts inner fact-id ids))
  (-unlink-conflicts [_ fact-id ids]
    (append! ctx {:t "unlink-conflicts" :fact-id fact-id :ids (vec ids)})
    (store/-unlink-conflicts inner fact-id ids))
  (-reinforce [_ fact-id opts]
    (append! ctx {:t "reinforce" :fact-id fact-id
                  :at (ms-of (:at opts)) :confidence (:confidence opts)})
    (store/-reinforce inner fact-id opts))
  (-all-facts [_] (store/-all-facts inner))
  (-open-episode [_ ep]
    (append! ctx {:t "open-episode" :episode (record-out :episode ep)})
    (store/-open-episode inner ep))
  (-close-episode [_ id summary at]
    (append! ctx {:t "close-episode" :id id :summary summary :at (ms-of at)})
    (store/-close-episode inner id summary at))
  (-get-episode [_ id] (store/-get-episode inner id))
  (-list-episodes [_] (store/-list-episodes inner))
  (-get-predicate [_ id] (store/-get-predicate inner id))
  (-list-predicates [_ opts] (store/-list-predicates inner opts))
  (-register-predicate [_ pred]
    (append! ctx {:t "register-predicate" :predicate (record-out :predicate pred)})
    (store/-register-predicate inner pred))
  (-search [_ q opts] (store/-search inner q opts))
  (-stats [_] (store/-stats inner))
  (-close [_] (store/-close inner)))

(defn logged-store
  "Wrap a store so its mutations append to this writer's log."
  [inner db]
  (->LoggedStore inner {:db db
                        :writer (writer-id! db)
                        :clock (atom 0)
                        :counter (atom (or (try
                                             (some->> (str (fs/path (oplog-dir db)
                                                                    (str (writer-id! db) ".jsonl")))
                                                      slurp
                                                      str/split-lines
                                                      (remove str/blank?)
                                                      count)
                                             (catch Exception _ nil))
                                           0))}))

;; ---------------------------------------------------------------------------
;; Reading logs, tracking what's applied
;; ---------------------------------------------------------------------------

(defn- read-log [file]
  (try
    (->> (str/split-lines (slurp (str file)))
         (remove str/blank?)
         (keep #(try (json/parse-string % true) (catch Exception _ nil)))
         vec)
    (catch Exception _ [])))

(defn- state-file [db] (str (fs/path (oplog-dir db) "applied.json")))

(defn- load-state [db]
  (or (try (json/parse-string (slurp (state-file db)) true)
           (catch Exception _ nil))
      {:high-water {} :entity-map {}}))

(defn- save-state! [db state]
  (spit (state-file db) (json/generate-string state)))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(defn- remap-entity!
  "Foreign entity -> local entity, by name (ids are internal). Cached in the
  persistent entity map so later effects that only carry the foreign id
  still resolve."
  [s emap ent]
  (let [foreign-id (:id ent)]
    (or (some->> (get @emap (keyword foreign-id))
                 (hash-map :id))
        (let [ensure (requiring-resolve 'claimgraph.core/ensure-entity)
              local (ensure s {:name (:name ent) :type (:type ent)
                               :scope (:scope ent)})]
          (swap! emap assoc (keyword foreign-id) (:id local))
          local))))

(defn- fact-exists? [s id]
  (boolean (seq (store/-select-facts s {:ids [id]}))))

(defn- apply-effect!
  "One foreign effect against the raw (unlogged) store. Returns a result
  keyword for the report."
  [s emap {:keys [t] :as e}]
  (case t
    "ensure-entity"
    (do (remap-entity! s emap (rehydrate (:entity e))) :applied)

    "insert-fact"
    (let [f (rehydrate (:fact e))]
      (if (fact-exists? s (:id f))
        :skipped
        (do (store/-insert-fact
             s (cond-> (assoc f :subject (remap-entity! s emap (:subject f)))
                 (:object-ref f)
                 (assoc :object-ref (remap-entity! s emap (:object-ref f)))))
            :applied)))

    "update-entity"
    (if-let [local (get @emap (keyword (:id e)))]
      (do (store/-update-entity s local (-> (:updates e)
                                            (update :type logic/->kw)
                                            (->> (into {} (filter (comp some? val))))))
          :applied)
      :skipped)

    "repoint-facts"
    (let [from (get @emap (keyword (:from e)))
          to (get @emap (keyword (:to e)))]
      (if (and from to)
        (do (store/-repoint-facts s from to) :applied)
        :skipped))

    "delete-entity"
    (if-let [local (get @emap (keyword (:id e)))]
      (do (store/-delete-entity s local) :applied)
      :skipped)

    "invalidate"
    (if (fact-exists? s (:fact-id e))
      (do (store/-invalidate s (:fact-id e)
                             (java.util.Date. (long (:at e)))
                             (:reason e))
          :applied)
      :skipped)

    "link-conflicts"
    (if (fact-exists? s (:fact-id e))
      (do (store/-link-conflicts s (:fact-id e)
                                 (filterv #(fact-exists? s %) (:ids e)))
          :applied)
      :skipped)

    "unlink-conflicts"
    (if (fact-exists? s (:fact-id e))
      (do (store/-unlink-conflicts s (:fact-id e) (:ids e)) :applied)
      :skipped)

    "reinforce"
    (if (fact-exists? s (:fact-id e))
      (do (store/-reinforce s (:fact-id e)
                            {:at (java.util.Date. (long (:at e)))
                             :confidence (:confidence e)})
          :applied)
      :skipped)

    "open-episode"
    (let [ep (rehydrate (:episode e))]
      (if (store/-get-episode s (:id ep))
        :skipped
        (do (store/-open-episode s ep) :applied)))

    "close-episode"
    (if (store/-get-episode s (:id e))
      (do (store/-close-episode s (:id e) (:summary e)
                                (java.util.Date. (long (:at e))))
          :applied)
      :skipped)

    "register-predicate"
    (do (store/-register-predicate s (rehydrate (:predicate e))) :applied)

    "repoint-predicate"
    (do (store/-repoint-predicate s (logic/->kw (:from e)) (logic/->kw (:to e)))
        :applied)

    :unknown))

;; ---------------------------------------------------------------------------
;; Reconcile
;; ---------------------------------------------------------------------------

(defn reconcile!
  "Apply every foreign effect this store hasn't seen, in canonical order,
  then make the seams visible: collapse claims both writers made
  independently, and count the conflict candidates the judge should look at.
  Takes the RAW store (reconciliation must not re-log foreign effects as
  ours)."
  [s db]
  (let [own (writer-id! db)
        state (load-state db)
        emap (atom (or (:entity-map state) {}))
        logs (when (fs/exists? (oplog-dir db))
               (for [f (fs/glob (oplog-dir db) "*.jsonl")
                     :let [writer (str (fs/strip-ext (fs/file-name f)))]
                     :when (not= writer own)]
                 [writer (read-log f)]))
        unapplied (->> logs
                       (mapcat (fn [[writer entries]]
                                 (let [hw (get-in state [:high-water (keyword writer)] 0)]
                                   (->> entries
                                        (filter #(> (:seq % 0) hw))
                                        (map #(assoc % :writer writer))))))
                       (sort-by (juxt #(:hlc % 0) :writer #(:seq % 0)))
                       vec)
        results (mapv (fn [e]
                        (try [(apply-effect! s emap e) e]
                             (catch Exception ex [:error (assoc e :error (ex-message ex))])))
                      unapplied)
        now (java.util.Date.)
        touched (->> unapplied
                     (filter #(= "insert-fact" (:t %)))
                     (keep #(let [id (get-in % [:fact :subject :id])]
                              (get @emap (keyword id))))
                     distinct
                     vec)
        dup-ids (vec (mapcat (fn [subj]
                               (logic/collapse-duplicates-plan
                                (store/-get-facts s subj {:direction :out}) now))
                             touched))
        _ (doseq [id dup-ids]
            (store/-invalidate s id now "duplicate across writers (reconcile)"))
        preds-by-id (into {} (map (juxt :id identity)) (store/-list-predicates s {}))
        candidates (logic/conflict-candidates
                    (store/-select-facts s {:valid-cheap true}) preds-by-id now)]
    (save-state! db {:high-water (reduce (fn [hw {:keys [writer] :as e}]
                                           (update hw (keyword writer)
                                                   (fnil max 0) (:seq e 0)))
                                         (or (:high-water state) {})
                                         unapplied)
                     :entity-map @emap})
    {:status :reconciled
     :writers (mapv first logs)
     :effects {:total (count unapplied)
               :applied (count (filter #(= :applied (first %)) results))
               :skipped (count (filter #(= :skipped (first %)) results))
               :errors (vec (keep #(when (= :error (first %)) (second %)) results))}
     :duplicates-collapsed (count dup-ids)
     :sweep-candidates (count candidates)
     :hint (when (pos? (count candidates))
             "run `claim judge --sweep` (or consolidate) to judge what the writers couldn't see")}))
