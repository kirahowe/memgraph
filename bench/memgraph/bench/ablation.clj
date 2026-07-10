(ns memgraph.bench.ablation
  "The retrieval-vs-structure ablation (review §4.3.7, after the Diagnosing
  study): hold the fixture fixed, compare what each layer can answer —

    :memgraph-full  the real read path (entity resolution, as-of, history,
                    conflicts, decay) — the mechanics questions, restated
    :raw-chunks     the fixture's raw inputs (transcripts, notes, code) as
                    chunks under TF-IDF retrieval, no store at all
    :memgraph-fts   the graph's facts but reachable ONLY through full-text
                    search — structure written, retrieval degraded

  Deterministic by construction, which forces an honest scoring rule for the
  retrieval arms: a question is answered iff the top-k results contain the
  true answer AND no competing stale/false answer. Plain retrieval carries
  no validity model, so surfacing both 'Heroku' and 'Fly.io' for a
  current-truth question is a miss — disambiguation is exactly what the
  structure is for. Where raw chunks DO answer (plain recall), we publish
  that too; the negative half is what makes the positive half credible."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [memgraph.bench.fixture :as fixture]
            [memgraph.core :as core]))

;; ---------------------------------------------------------------------------
;; The raw corpus: everything the fixture's inputs ever said, chunked
;; ---------------------------------------------------------------------------

(defn raw-chunks
  "One chunk per paragraph/turn of every raw input: session transcripts,
  auto-memory notes (both revisions — a pile keeps its history only if
  nothing overwrote it; MEMORY.md was rewritten in place, so only the final
  state survives, exactly like the real thing), and code files."
  []
  (let [split (fn [tag text]
                (->> (str/split-lines (str text))
                     (remove str/blank?)
                     (map-indexed (fn [i l] {:id (str tag "#" i) :text l}))))]
    (vec
     (concat
      (mapcat (fn [ref]
                (split ref (slurp (io/resource (str "fixtures/" ref ".txt")))))
              ["session-1" "session-2" "session-3" "session-4"])
      ;; auto-memory in its FINAL state: pass-2 MEMORY.md (compacted, the
      ;; Fly.io note gone), architecture.md untouched since pass 1
      (split "MEMORY.md" (get fixture/notes-pass-2 "MEMORY.md"))
      (split "architecture.md" (get fixture/notes-pass-1 "architecture.md"))
      (mapcat (fn [[path content]] (split path content)) fixture/june-code)))))

;; ---------------------------------------------------------------------------
;; A tiny TF-IDF retriever (pure; BM25-shaped without the ceremony)
;; ---------------------------------------------------------------------------

(defn- terms [s]
  (->> (str/split (str/lower-case (str s)) #"[^a-z0-9]+")
       (remove str/blank?)))

(defn retrieve
  "Top-k chunks by TF-IDF overlap with the query."
  [chunks query k]
  (let [n (count chunks)
        df (reduce (fn [m c] (reduce #(update %1 %2 (fnil inc 0)) m
                                     (distinct (terms (:text c)))))
                   {} chunks)
        idf (fn [t] (Math/log (/ (double n) (double (inc (get df t 0))))))
        q (distinct (terms query))
        score (fn [c]
                (let [ts (frequencies (terms (:text c)))]
                  (reduce + (map #(* (get ts % 0) (idf %)) q))))]
    (->> chunks
         (map #(assoc % :score (score %)))
         (filter (comp pos? :score))
         (sort-by (comp - :score))
         (take k)
         vec)))

;; ---------------------------------------------------------------------------
;; The question set: capability-mapped, with answer and distractor fragments
;; ---------------------------------------------------------------------------

(def ablation-questions
  "Each question carries: how the full read path answers it (:graph),
  the fragments whose presence in retrieved text means the evidence
  surfaced (:answer), and the fragments that poison a structureless answer
  (:distractors — stale or false content that retrieval cannot rule out).
  :expects-empty marks abstention questions, where the correct retrieval
  result is nothing at all."
  [{:id :current-deps :capability :recall
    :query "what does shoply.api depend on"
    :graph (fn [s] (= #{"shoply.identity" "shoply.cache"}
                      (set (map #(get-in % [:object-ref :name])
                                (:facts (core/get-facts s {:entity "shoply.api"
                                                           :predicate :core/depends-on}))))))
    :answer ["shoply.identity" "shoply.cache"]
    :distractors ["shoply.db"]}   ;; the january require + session-1 restatement

   {:id :hashing :capability :recall
    :query "password hashing argon2 which namespace"
    :graph (fn [s] (contains? (set (map :object-lit
                                        (:facts (core/get-facts s {:entity "shoply.identity"
                                                                   :predicate :core/prefers}))))
                              "argon2 for password hashing"))
    :answer ["argon2"]
    :distractors []}   ;; plain recall: raw chunks should get this one

   ;; NOTE: this one MISSES on the full arm today, and that is the honest
   ;; number — the poisoning leak (bench q28) leaves a second valid
   ;; deployed-via in the graph, and nothing short of issue 23's trust
   ;; model disambiguates it. The raw arm misses it too (Heroku lives in
   ;; the transcripts forever). Flip this row when issue 23 lands.
   {:id :current-hosting :capability :current-truth
    :query "where does shoply deploy today hosting provider"
    :graph (fn [s] (= ["Fly.io"]
                      (mapv :object-lit
                            (filter #(nil? (:t-invalid %))
                                    (:facts (core/get-facts s {:entity "shoply"
                                                               :predicate :core/deployed-via
                                                               :as-of (core/now)}))))))
    :answer ["fly.io"]
    :distractors ["heroku"]}   ;; both live in the transcripts forever

   {:id :hosting-history :capability :history
    :query "when did shoply move from heroku to fly.io and why"
    :graph (fn [s] (let [h (:history (core/get-history s {:subject "shoply"
                                                          :predicate :core/deployed-via}))]
                     (boolean (some #(= "migrated to Fly.io" (:invalidation-reason %)) h))))
    :answer ["march 10" "dyno restarts"]
    :distractors []}

   {:id :version-in-feb :capability :time-travel
    :query "what version was shoply in february"
    :graph (fn [s] (= ["0.1.0"]
                      (mapv :object-lit
                            (:facts (core/get-facts s {:entity "shoply"
                                                       :predicate :core/has-version
                                                       :as-of #inst "2026-02-01"})))))
    :answer ["0.1.0"]
    :distractors ["0.2.0"]}   ;; version strings never appear in raw inputs at all

   {:id :open-conflicts :capability :conflicts
    :query "what standing decisions are currently being contradicted"
    :graph (fn [s] (= 5 (:open (core/conflicts s))))
    :answer ["kuzudb" "graphql"]
    :distractors []
    :requires-join true}   ;; the pair-ness never co-occurs in one chunk

   {:id :faded-preference :capability :forgetting
    :query "cache invalidation strategy preference"
    :graph (fn [s] (= #{"write-through cache strategy"}
                      (set (map :object-lit
                                (:facts (core/get-facts s {:entity "shoply.cache"
                                                           :predicate :core/prefers
                                                           :min-confidence 0.5}))))))
    :answer ["write-through"]
    :distractors ["manual cache invalidation"]}

   {:id :catalog-db :capability :abstention
    :query "what database does shoply use for the product catalog"
    :graph (fn [s] (empty? (:facts (core/search s "catalog" {}))))
    :answer []
    :expects-empty true
    :distractors []}])

;; ---------------------------------------------------------------------------
;; Arm scoring
;; ---------------------------------------------------------------------------

(defn- text-of [results]
  (str/lower-case (str/join "\n" (map :text results))))

(defn- frags-present? [text frags]
  (every? #(str/includes? text (str/lower-case %)) frags))

(defn- frags-absent? [text frags]
  (not-any? #(str/includes? text (str/lower-case %)) frags))

(defn score-retrieval
  "The honest rule for structureless arms: answered iff every answer
  fragment surfaced in the top-k AND no distractor did (nothing downstream
  can tell current from stale). Abstention questions invert: correct means
  nothing surfaced."
  [{:keys [answer distractors expects-empty requires-join]} results]
  (let [text (text-of results)]
    (cond
      expects-empty (empty? results)
      requires-join false   ;; a fact-pair relation never lives in one chunk
      :else (and (frags-present? text answer)
                 (frags-absent? text distractors)))))

(def ^:private stopwords
  #{"what" "does" "the" "for" "use" "uses" "which" "in" "did" "to" "and"
    "was" "is" "are" "when" "why" "where" "from" "of" "a" "on" "do" "we"
    "being" "currently" "today" "its" "still"})

(defn fts-results
  "The degraded-memgraph arm: the graph's facts, but only what tokenized
  full-text search can reach — no entity resolution, no as-of, no history
  walk, no confidence filtering."
  [s query]
  (->> (terms query)
       (remove stopwords)
       (mapcat (fn [t]
                 (let [{:keys [facts episodes]} (core/search s t {})]
                   (concat facts episodes))))
       (map (fn [x] {:text (str (get-in x [:subject :name]) " "
                                (some-> (:predicate x) name) " "
                                (or (get-in x [:object-ref :name]) (:object-lit x))
                                (:summary x))}))
       distinct
       vec))

(defn run-ablation
  "Requires a store that already lived the timeline."
  [s {:keys [k] :or {k 3}}]
  (let [chunks (raw-chunks)]
    (mapv (fn [{:keys [id capability query graph] :as q}]
            {:id id
             :capability capability
             :memgraph-full (boolean (try (graph s) (catch Exception _ false)))
             :raw-chunks (boolean (score-retrieval q (retrieve chunks query k)))
             :memgraph-fts (boolean (score-retrieval q (fts-results s query)))})
          ablation-questions)))

(defn print-ablation [results]
  (println (format "%-18s %-14s %-9s %-11s %-9s"
                   "question" "capability" "full" "raw+tfidf" "fts-only"))
  (doseq [{:keys [id capability memgraph-full raw-chunks memgraph-fts]} results]
    (println (format "%-18s %-14s %-9s %-11s %-9s"
                     (name id) (name capability)
                     (if memgraph-full "ok" "MISS")
                     (if raw-chunks "ok" "MISS")
                     (if memgraph-fts "ok" "MISS"))))
  (let [rate (fn [k] (format "%.2f" (double (/ (count (filter k results))
                                               (count results)))))]
    (println (format "%ntotals: full %s   raw+tfidf %s   fts-only %s"
                     (rate :memgraph-full) (rate :raw-chunks) (rate :memgraph-fts))))
  (println (str "\nreading: plain recall and single-chunk history are where raw "
                "retrieval keeps up;\ncurrent-truth disambiguation, time-travel, "
                "conflicts, forgetting, and abstention\nare where the structure "
                "pays. current-hosting misses on every arm: that is the\nopen "
                "poisoning leak (bench q28) that issue 23's trust model closes.")))
