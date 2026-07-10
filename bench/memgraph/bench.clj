(ns memgraph.bench
  "The codebase-memory benchmark. Two layers, split by determinism:

  bb bench      — mechanics: drive the fixture timeline through the real
                  store and ingesters with RECORDED LLM outputs, then score
                  the question set. Deterministic; exits non-zero below a
                  perfect score, so it doubles as a longitudinal regression
                  gate.
  bb bench llm  — quality: build the same graph, then measure a real model
                  (via $MEMGRAPH_LLM_CMD, default claude -p) on extraction
                  precision/recall, judge verdict accuracy, and entity
                  fragmentation. Informational; never in CI.

  MEMGRAPH_BENCH_STORE=memory runs mechanics pod-free."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [memgraph.bench.fixture :as fixture]
            [memgraph.bench.questions :as questions]
            [memgraph.consolidate :as consolidate]
            [memgraph.context :as context]
            [memgraph.core :as core]
            [memgraph.harness :as harness]
            [memgraph.ingest.clj-code :as code]
            [memgraph.ingest.notes :as notes]
            [memgraph.ingest.session :as session]
            [memgraph.judge :as judge]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Timeline execution
;; ---------------------------------------------------------------------------

(defn- transcript [resource]
  (slurp (io/resource resource)))

(defn- write-note!
  "The harness rewriting a note file: fresh content replaces the old, but an
  existing managed section stays where it is — compaction squeezes Claude's
  own notes, not memgraph's compiled block."
  [f content]
  (let [old (when (fs/exists? f) (slurp f))
        begin (some-> old (str/index-of harness/begin-marker))
        end (when begin
              (some-> (str/index-of old harness/end-marker begin)
                      (+ (count harness/end-marker))))]
    (spit f (if end
              (str (subs old begin end) "\n\n" content)
              content))))

(defn- run-step! [s {:keys [code-dir notes-dir]} {:keys [op] :as step}]
  (case op
    :code
    (do (when (fs/exists? code-dir) (fs/delete-tree code-dir))
        (doseq [[path content] (:files step)]
          (let [f (fs/file (str code-dir) path)]
            (io/make-parents f)
            (spit f content)))
        (code/ingest! s {:dir (str code-dir)}))

    :notes
    (do (fs/create-dirs notes-dir)
        (doseq [[path content] (:files step)]
          (write-note! (fs/file (str notes-dir) path) content))
        (notes/ingest! s {:dir (str notes-dir)
                          :extractor-fn fixture/recorded-note-extractor}))

    :compile-context
    (context/compile! s {:dir (str notes-dir)})

    :assert
    (let [a (:args step)]
      (core/assert-fact s (-> a
                              (dissoc :valid-from)
                              (assoc :t-valid (logic/parse-instant (:valid-from a))))))

    :session
    (session/extract! s {:transcript (transcript (:resource step))
                         :ref (:ref step)
                         :extractor-fn (constantly
                                        (get fixture/recorded-extractions (:ref step)))})

    :merge
    (core/merge-entities s {:from (:from step) :into (:into step)})

    :invalidate-object
    (let [{:keys [entity predicate object-lit at reason]} step
          f (->> (:facts (core/get-facts s {:entity entity :predicate predicate}))
                 (filter #(= object-lit (:object-lit %)))
                 first)]
      (core/invalidate s {:fact-id (:id f)
                          :at (logic/parse-instant at)
                          :reason reason}))

    :raw-fact
    (let [{:keys [days-ago fact]} step
          t (java.util.Date. (- (System/currentTimeMillis) (* days-ago 86400000)))]
      (store/-insert-fact s (-> fact
                                (dissoc :subject-name)
                                (assoc :id (str "bench-aged-" (:subject-name fact))
                                       :subject (core/ensure-entity
                                                 s {:name (:subject-name fact)})
                                       :t-valid t
                                       :recorded-at t
                                       :last-reinforced-at t))))

    :consolidate
    (consolidate/consolidate! s {:summarize-fn fixture/recorded-summarizer
                                 :judge-fn fixture/recorded-judge})))

(defn run-timeline!
  "Execute the fixture timeline against a seeded store."
  [s]
  (let [root (fs/create-temp-dir {:prefix "memgraph-bench"})
        dirs {:code-dir (fs/path root "code")
              :notes-dir (fs/path root "notes")}]
    (try
      (doseq [step fixture/steps]
        (run-step! s dirs step))
      (finally (fs/delete-tree root))))
  s)

;; ---------------------------------------------------------------------------
;; Mechanics layer
;; ---------------------------------------------------------------------------

(defn run-questions [s]
  (mapv (fn [{:keys [id capability desc run expect]}]
          (let [actual (try (run s)
                            (catch Exception e {:error (ex-message e)}))]
            {:id id :capability capability :desc desc
             :expected expect :actual actual :pass? (= expect actual)}))
        questions/questions))

(defn scorecard [results]
  {:by-capability (into (sorted-map)
                        (map (fn [[cap rs]]
                               [cap {:passed (count (filter :pass? rs))
                                     :total (count rs)}])
                             (group-by :capability results)))
   :passed (count (filter :pass? results))
   :total (count results)
   :score (double (/ (count (filter :pass? results)) (count results)))})

(defn- open-store []
  (if (= "memory" (System/getenv "MEMGRAPH_BENCH_STORE"))
    ((requiring-resolve 'memgraph.store.memory/create))
    ((requiring-resolve 'memgraph.store.datalevin/open-store)
     (str (fs/path (fs/temp-dir) (str "memgraph-bench-" (random-uuid)))))))

(defn- with-timeline-store [f]
  (let [s (open-store)]
    (try
      (core/seed! s)
      (run-timeline! s)
      (f s)
      (finally (store/-close s)))))

(defn run-mechanics []
  (with-timeline-store
    (fn [s]
      (let [results (run-questions s)]
        {:results results :scorecard (scorecard results)}))))

(defn- print-mechanics [{:keys [results scorecard]}]
  (doseq [{:keys [pass? capability id desc expected actual]} results]
    (println (format "%-4s %-12s %-4s %s"
                     (if pass? "ok" "FAIL") (name capability) (name id) desc))
    (when-not pass?
      (println "     expected:" (pr-str expected))
      (println "     actual:  " (pr-str actual))))
  (println)
  (doseq [[cap {:keys [passed total]}] (:by-capability scorecard)]
    (println (format "  %-12s %d/%d" (name cap) passed total)))
  (println (format "%nmechanics: %d/%d"
                   (:passed scorecard) (:total scorecard))))

;; ---------------------------------------------------------------------------
;; LLM quality layer
;; ---------------------------------------------------------------------------

(defn- norm-pred [p]
  (-> (str p)
      (str/replace #"^:?(core/)?" "")
      (str/replace "_" "-")))

(defn- norm-triple [f]
  [(logic/normalize-entity-name (:subject f))
   (norm-pred (:predicate f))
   (logic/normalize-entity-name (:object f))])

(defn- precision-recall [extracted expected]
  (let [hits (count (filter expected extracted))]
    {:precision (if (seq extracted) (double (/ hits (count extracted))) 0.0)
     :recall (if (seq expected) (double (/ hits (count expected))) 1.0)
     :extracted (count extracted)
     :expected (count expected)}))

(defn- llm-extraction-quality [s]
  (vec (for [{:keys [ref resource]} (filter #(= :session (:op %)) fixture/steps)]
         (try
           (let [r (session/extract! s {:transcript (transcript resource)
                                        :ref (str ref "-llm")
                                        :dry-run true})
                 triples (set (map norm-triple (:facts r)))]
             (merge {:session ref
                     :suspect-names (vec (remove fixture/known-entity-names
                                                 (map first triples)))}
                    (precision-recall triples (get fixture/expected-triples ref))))
           (catch Exception e {:session ref :error (ex-message e)})))))

(defn- llm-judge-accuracy [s]
  (try
    (let [{:keys [results]} (judge/judge-conflicts! s {})
          graded (mapv (fn [{:keys [fact verdict]}]
                         (let [k (logic/normalize-entity-name (:object fact))
                               label (get fixture/conflict-labels k)]
                           {:pair k :label label
                            :verdict (:relation verdict)
                            :correct (= label (:relation verdict))}))
                       results)]
      {:pairs graded
       :accuracy (if (seq graded)
                   (double (/ (count (filter :correct graded)) (count graded)))
                   nil)})
    (catch Exception e {:error (ex-message e)})))

(defn run-llm []
  (with-timeline-store
    (fn [s]
      {:extraction (llm-extraction-quality s)
       :judge (llm-judge-accuracy s)})))

(defn- print-llm [{:keys [extraction judge]}]
  (println "extraction quality (real model vs annotated ground truth):")
  (doseq [{:keys [session precision recall suspect-names error]} extraction]
    (if error
      (println (format "  %-10s ERROR %s" session error))
      (println (format "  %-10s precision %.2f  recall %.2f  suspect names %s"
                       session precision recall (pr-str suspect-names)))))
  (println "\njudge verdict accuracy on labeled conflict pairs:")
  (if (:error judge)
    (println "  ERROR" (:error judge))
    (do (doseq [{:keys [pair label verdict correct]} (:pairs judge)]
          (println (format "  %-10s label %-12s verdict %-12s %s"
                           pair (name label) (name (or verdict :none))
                           (if correct "ok" "WRONG"))))
        (println (format "  accuracy: %s" (pr-str (:accuracy judge)))))))

;; ---------------------------------------------------------------------------

(defn -main [& args]
  (if (= "llm" (first args))
    (print-llm (run-llm))
    (let [{:keys [scorecard] :as report} (run-mechanics)]
      (print-mechanics report)
      (System/exit (if (= 1.0 (:score scorecard)) 0 1)))))
