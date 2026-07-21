(ns claimgraph.lease
  "Write lease: multi-writer safety for the v0 single-store world
  (review §3.7). The conflict machinery is a read-decide-write cycle —
  two concurrent writers can both read 'no conflict' and insert a
  contradiction LMDB will happily hold. The lease serializes whole write
  operations at the CLI boundary instead: one lease file next to the db,
  atomically created, token-guarded, TTL'd so a crashed writer never
  wedges the store.

  Deliberately a lease, not a queue: coding-agent writers are short-lived
  CLI invocations, so waiting a few seconds covers real contention and
  anything longer deserves a loud :store-locked error naming the holder."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(def default-ttl-ms 30000)
(def default-wait-ms 5000)
(def retry-sleep-ms 100)

(defn lock-file [db] (str db ".lock"))

(defn- read-lease [db]
  (try (json/parse-string (slurp (lock-file db)) true)
       (catch Exception _ nil)))

(defn- try-acquire!
  "One atomic attempt: create-if-absent, or break an expired lease."
  [db lease now-ms]
  (let [f (lock-file db)]
    (or (try
          (fs/create-dirs (fs/parent (fs/absolutize f)))
          (fs/create-file f)
          (spit f (json/generate-string lease))
          true
          (catch java.nio.file.FileAlreadyExistsException _ false)
          (catch Exception _ false))
        (when-let [held (read-lease db)]
          (when (and (:expires-at held) (> now-ms (:expires-at held)))
            ;; expired: break it and retry from scratch next round
            (try (fs/delete-if-exists f) (catch Exception _ nil))
            false))
        false)))

(defn acquire!
  "Take the write lease or throw :store-locked naming the holder.
  opts: :owner (label for errors) :ttl-ms :wait-ms"
  [db {:keys [owner ttl-ms wait-ms]}]
  (let [ttl (long (or ttl-ms default-ttl-ms))
        deadline (+ (System/currentTimeMillis) (long (or wait-ms default-wait-ms)))
        token (str (random-uuid))]
    (loop []
      (let [now (System/currentTimeMillis)
            lease {:token token
                   :owner (str (or owner "claimgraph"))
                   :acquired-at now
                   :expires-at (+ now ttl)}]
        (cond
          (try-acquire! db lease now) token

          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep retry-sleep-ms) (recur))

          :else
          (let [held (read-lease db)]
            (throw (ex-info (str "Store is write-locked by " (:owner held "unknown"))
                            {:type :store-locked
                             :claimgraph/error true
                             :holder (dissoc held :token)
                             :hint "another writer holds the lease; it expires on its TTL, or delete <db>.lock if the holder is dead"}))))))))

(defn release!
  "Give the lease back — only if it is still ours (an expired-and-broken
  lease may have been reacquired by someone else)."
  [db token]
  (when (= token (:token (read-lease db)))
    (try (fs/delete-if-exists (lock-file db)) (catch Exception _ nil))))

(defn with-lease
  "Run f under the write lease."
  [db opts f]
  (let [token (acquire! db opts)]
    (try (f)
         (finally (release! db token)))))
