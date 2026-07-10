(ns memgraph.bench.questions
  "The mechanics question set: what a memory that works answers correctly
  after living through the fixture timeline. Each question runs a real read
  against the store and compares to hand-authored ground truth. Categories
  deliberately map onto the system's load-bearing capabilities."
  (:require [clojure.string :as str]
            [memgraph.context :as context]
            [memgraph.core :as core]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(defn- date [s] (logic/parse-instant s))

(defn- obj [f] (or (get-in f [:object-ref :name]) (:object-lit f)))

(defn- objects [s q] (set (map obj (:facts (core/get-facts s q)))))

(defn- object-seq [s q] (mapv obj (:facts (core/get-facts s q))))

(def questions
  [{:id :q1 :capability :retrieval
    :desc "current dependencies of shoply.api"
    :run (fn [s] (objects s {:entity "shoply.api" :predicate :core/depends-on}))
    :expect #{"shoply.identity" "shoply.cache"}}

   {:id :q2 :capability :retrieval
    :desc "who depends on shoply.db right now (reverse lookup)"
    :run (fn [s] (set (map (comp :name :subject)
                           (:facts (core/get-facts s {:entity "shoply.db"
                                                      :predicate :core/depends-on
                                                      :direction :in})))))
    :expect #{"shoply.identity" "shoply.cache"}}

   {:id :q3 :capability :time-travel
    :desc "version in February"
    :run (fn [s] (object-seq s {:entity "shoply" :predicate :core/has-version
                                :as-of (date "2026-02-01")}))
    :expect ["0.1.0"]}

   {:id :q4 :capability :time-travel
    :desc "version now"
    :run (fn [s] (object-seq s {:entity "shoply" :predicate :core/has-version}))
    :expect ["0.2.0"]}

   {:id :q5 :capability :time-travel
    :desc "deployment target in February vs April"
    :run (fn [s] {:feb (object-seq s {:entity "shoply" :predicate :core/deployed-via
                                      :as-of (date "2026-02-01")})
                  :apr (object-seq s {:entity "shoply" :predicate :core/deployed-via
                                      :as-of (date "2026-04-01")})})
    :expect {:feb ["Heroku"] :apr ["Fly.io"]}}

   {:id :q6 :capability :history
    :desc "version history: two abutting intervals"
    :run (fn [s] (let [{:keys [history]} (core/get-history s {:subject "shoply"
                                                              :predicate :core/has-version})]
                   {:versions (mapv :object-lit history)
                    :first-closed-at (:t-invalid (first history))}))
    :expect {:versions ["0.1.0" "0.2.0"]
             :first-closed-at (date "2026-03-10")}}

   {:id :q7 :capability :identity
    :desc "sloppy casing resolves to the canonical entity"
    :run (fn [s] (get-in (core/resolve-entity s {:name "SHOPLY.API"}) [:entity :name]))
    :expect "shoply.api"}

   {:id :q8 :capability :identity
    :desc "the merged-away name still answers, with its facts carried over"
    :run (fn [s] {:resolves-to (get-in (core/resolve-entity s {:name "shoply.auth"})
                                       [:entity :name])
                  :carried (contains? (objects s {:entity "shoply.auth"
                                                  :predicate :core/prefers})
                                      "argon2 for password hashing")})
    :expect {:resolves-to "shoply.identity" :carried true}}

   {:id :q9 :capability :conflicts
    :desc "four conflicts stand open for the human (two session-era, one code-vs-decision, one planted by notes)"
    :run (fn [s] (:open (core/conflicts s)))
    :expect 4}

   {:id :q10 :capability :conflicts
    :desc "they are the GraphQL stance clash and the KuzuDB and shoply.db decision violations"
    :run (fn [s] (set (map (fn [{:keys [fact candidate]}]
                             (set (map (comp logic/normalize-entity-name obj)
                                       [fact candidate])))
                           (:conflicts (core/conflicts s)))))
    :expect #{#{"graphql"} #{"kuzudb"} #{"shoplydb"}}}

   {:id :q11 :capability :forgetting
    :desc "the unrestated observation faded; the re-derived code fact stayed hot"
    :run (fn [s]
           (let [stale (first (filter #(= "manual cache invalidation everywhere" (obj %))
                                      (:facts (core/get-facts s {:entity "shoply.cache"}))))
                 code (first (:facts (core/get-facts s {:entity "shoply.api"
                                                        :predicate :core/defined-in})))]
             {:stale-faded (< (:effective-confidence stale) 0.3)
              :base-untouched (= 0.8 (:confidence stale))
              :code-hot (>= (:effective-confidence code) 0.9)}))
    :expect {:stale-faded true :base-untouched true :code-hot true}}

   {:id :q12 :capability :forgetting
    :desc "confidence filtering hides the faded fact, not the fresh one"
    :run (fn [s] (objects s {:entity "shoply.cache" :predicate :core/prefers
                             :min-confidence 0.5}))
    :expect #{"write-through cache strategy"}}

   {:id :q13 :capability :forgetting
    :desc "search ranks the fresh cache fact above the faded one"
    :run (fn [s] (obj (first (:facts (core/search s "cache" {})))))
    :expect "write-through cache strategy"}

   {:id :q14 :capability :provenance
    :desc "the argon2 preference traces to session-1, whose summary explains it"
    :run (fn [s]
           (let [f (first (filter #(re-find #"argon2" (str (obj %)))
                                  (:facts (core/get-facts s {:entity "shoply.identity"
                                                             :predicate :core/prefers}))))
                 ep (store/-get-episode s (:episode f))]
             {:ref (:ref ep)
              :summarized (boolean (re-find #"argon2" (str (:summary ep))))}))
    :expect {:ref "session-1" :summarized true}}

   {:id :q15 :capability :provenance
    :desc "the closed Heroku interval says why it ended"
    :run (fn [s]
           (let [{:keys [history]} (core/get-history s {:subject "shoply"
                                                        :predicate :core/deployed-via})]
             (:invalidation-reason (first (filter #(= "Heroku" (:object-lit %)) history)))))
    :expect "migrated to Fly.io"}

   {:id :q16 :capability :ambient
    :desc "a note restating a session fact reinforces it — one copy, session provenance intact"
    :run (fn [s]
           (let [fs (filter #(= "write-through cache strategy" (obj %))
                            (:facts (core/get-facts s {:entity "shoply.cache"
                                                       :predicate :core/prefers})))
                 f (first fs)]
             {:copies (count fs)
              :episode-ref (:ref (store/-get-episode s (:episode f)))}))
    :expect {:copies 1 :episode-ref "session-3"}}

   {:id :q17 :capability :ambient
    :desc "a decision planted in notes lands demoted — observation, capped, agent-note — and flags against the standing rejection"
    :run (fn [s]
           (let [f (first (filter #(= "KuzuDB" (obj %))
                                  (:facts (core/get-facts s {:entity "shoply"
                                                             :predicate :core/prefers}))))]
             {:epistemic (:epistemic f)
              :confidence (:confidence f)
              :source (:source-type f)
              :flagged (boolean (seq (:conflicts f)))}))
    :expect {:epistemic :observation :confidence 0.65 :source :agent-note :flagged true}}

   {:id :q18 :capability :ambient
    :desc "compaction is not falsity: the dropped Fly.io note stays a valid fact; ingestion stayed delta-driven"
    :run (fn [s]
           {:fly-still-valid (object-seq s {:entity "shoply" :predicate :core/deployed-via})
            :note-episodes (count (filter #(= :agent-note (:source-type %))
                                          (store/-list-episodes s)))})
    :expect {:fly-still-valid ["Fly.io"]
             :note-episodes 3}}

   {:id :q19 :capability :ambient
    :desc "the compiled view carries decisions and conflicts, never code facts, inside budget"
    :run (fn [s]
           (let [v (context/compiled-view {:facts (store/-all-facts s)
                                           :conflicts (:conflicts (core/conflicts s))
                                           :now (core/now)})]
             {:standing (boolean (re-find #"decided-against \"GraphQL\"" v))
              :conflict-listed (str/includes? v "KuzuDB")
              :code-free (not (str/includes? v "defined-in"))
              :budgeted (<= (count (.getBytes v "UTF-8")) context/default-budget)}))
    :expect {:standing true :conflict-listed true :code-free true :budgeted true}}

   {:id :q20 :capability :staleness
    :desc "the dependency the code quietly dropped: a session restated it, reconciliation still closed it"
    :run (fn [s]
           (let [{:keys [history]} (core/get-history s {:subject "shoply.api"
                                                        :predicate :core/depends-on})
                 db (first (filter #(= "shoply.db" (get-in % [:object-ref :name]))
                                   history))]
             {:current (objects s {:entity "shoply.api" :predicate :core/depends-on})
              :closed (some? (:t-invalid db))
              :reason-mechanical (str/starts-with? (str (:invalidation-reason db))
                                                   "code-invalidation")}))
    :expect {:current #{"shoply.identity" "shoply.cache"}
             :closed true
             :reason-mechanical true}}

   {:id :q21 :capability :staleness
    :desc "the code quietly violated the April decision; the sweep surfaced it, nobody having said a word"
    :run (fn [s]
           (let [pair (first (filter (fn [{:keys [fact candidate]}]
                                       (= #{:code :decision-record}
                                          (set (map :source-type [fact candidate]))))
                                     (:conflicts (core/conflicts s))))]
             {:found (some? pair)
              :subjects (set (map (comp :name :subject)
                                  ((juxt :fact :candidate) pair)))
              :objects (set (map (comp logic/normalize-entity-name obj)
                                 ((juxt :fact :candidate) pair)))}))
    :expect {:found true
             :subjects #{"shoply.cache"}
             :objects #{"shoplydb"}}}

   {:id :q22 :capability :abstention
    :desc "a near-miss entity name refuses instead of fuzzy-guessing, and nothing gets minted"
    :run (fn [s]
           {:refusal (try (core/get-facts s {:entity "shoply.ap"})
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
            :minted (some? (store/-get-entity s "shoply.ap" logic/default-scope))})
    :expect {:refusal :entity-not-found :minted false}}

   {:id :q23 :capability :abstention
    :desc "a known entity, an unknown aspect: empty, not near-miss garbage"
    :run (fn [s] (object-seq s {:entity "shoply.api" :predicate :core/tested-by}))
    :expect []}

   {:id :q24 :capability :abstention
    :desc "before the project began, the graph knows nothing — as-of abstains"
    :run (fn [s] (object-seq s {:entity "shoply" :predicate :core/has-version
                                :as-of (date "2025-12-01")}))
    :expect []}

   {:id :q25 :capability :abstention
    :desc "search for what was never recorded comes back empty on every axis"
    :run (fn [s] (let [r (core/search s "postgres" {})]
                   (mapv (comp count val) (select-keys r [:entities :facts :episodes]))))
    :expect [0 0 0]}])
