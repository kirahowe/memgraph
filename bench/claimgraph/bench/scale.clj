(ns claimgraph.bench.scale
  "The scale tier (review §3.8, §4.3.6; AMA-Bench-style): seeded synthetic
  history at 10×/100× the fixture, driven through the REAL write path, then
  scored with rule-based QA — generation is deterministic, so ground truth
  is computable from the seed, no LLM anywhere.

  What it reports, per size: build wall-clock through the conflict
  machinery, store shape, read latency (point reads and hybrid search over
  a seeded sample), QA accuracy at scale, and — the metric the field omits —
  maintenance cost per consolidate pass: wall-clock, sweep candidates, and
  the number/volume of LLM prompts a real pass would spend.

  Informational, never in CI: `bb bench scale [factor]` (default 10)."
  (:require [claimgraph.consolidate :as consolidate]
            [claimgraph.core :as core]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Pure-ish: the seeded plan (java.util.Random with a fixed seed)
;; ---------------------------------------------------------------------------

(defn gen-plan
  "factor 10 ≈ 10× the shoply fixture (~50 services), factor 100 ≈ 100×.
  Deterministic for a given factor."
  [factor]
  (let [rnd (java.util.Random. 42)
        n-svc (* 5 (long factor))
        svc (fn [i] (str "svc-" i))
        deps (vec (for [i (range 1 n-svc)
                        :let [k (inc (.nextInt rnd (min i 3)))]
                        d (distinct (repeatedly k #(svc (.nextInt rnd i))))]
                    {:subject (svc i) :predicate :core/depends-on
                     :object d :object-kind :entity
                     :source-type :code :confidence 0.95}))
        versions (vec (mapcat
                       (fn [i]
                         (cond-> [{:subject (svc i) :predicate :core/has-version
                                   :object "1.0.0" :object-kind :literal
                                   :valid-from "2026-01-01"}]
                           (even? i)
                           (conj {:subject (svc i) :predicate :core/has-version
                                  :object "2.0.0" :object-kind :literal
                                  :valid-from "2026-04-01"})))
                       (range n-svc)))
        prefs (vec (for [i (range n-svc)]
                     {:subject (svc i) :predicate :core/prefers
                      :object (str "convention-" (mod i 7)) :object-kind :literal
                      :class "preference" :source-type :session-log :confidence 0.7}))
        ;; planted implicit conflicts: decided-against X while depends-on X
        planted (max 1 (quot n-svc 10))
        conflicts (vec (mapcat
                        (fn [j]
                          (let [i (* j 10)]
                            [{:subject (svc i) :predicate :core/decided-against
                              :object (str "lib-" j) :object-kind :literal
                              :class "commitment" :source-type :decision-record}
                             {:subject (svc i) :predicate :core/depends-on
                              :object (str "lib-" j) :object-kind :entity
                              :source-type :session-log :confidence 0.7}]))
                        (range planted)))]
    {:n-services n-svc
     :planted-conflicts planted
     :facts (vec (concat deps versions prefs conflicts))
     ;; rule-based QA over a seeded sample
     :questions
     (vec (for [i (take 20 (iterate #(mod (+ % 7) n-svc) 3))]
            {:id (str "svc-" i)
             :current-version (if (even? i) ["2.0.0"] ["1.0.0"])
             :feb-version ["1.0.0"]
             :history-len (if (even? i) 2 1)}))}))

;; ---------------------------------------------------------------------------
;; Measurement
;; ---------------------------------------------------------------------------

(defn- ms [f]
  (let [t0 (System/nanoTime) r (f)]
    [(/ (- (System/nanoTime) t0) 1e6) r]))

(defn- median [xs] (let [v (vec (sort xs))] (when (seq v) (nth v (quot (count v) 2)))))

(defn run-scale [s factor]
  (let [{:keys [facts questions planted-conflicts n-services]} (gen-plan factor)
        [build-ms ingest] (ms #(core/ingest s {:source-type :code :ref "scale-gen"}
                                            facts))
        [qa-ms qa] (ms (fn []
                         (vec (for [{:keys [id current-version feb-version history-len]} questions]
                                (let [now-v (mapv :object-lit
                                                  (:facts (core/get-facts s {:entity id :predicate :core/has-version})))
                                      feb-v (mapv :object-lit
                                                  (:facts (core/get-facts s {:entity id :predicate :core/has-version
                                                                             :as-of #inst "2026-02-15"})))
                                      hist (count (:history (core/get-history s {:subject id :predicate :core/has-version})))]
                                  {:id id
                                   :pass? (and (= now-v current-version)
                                               (= feb-v feb-version)
                                               (= hist history-len))})))))
        read-lat (vec (for [{:keys [id]} (take 10 questions)]
                        (first (ms #(core/get-facts s {:entity id})))))
        search-lat (vec (for [q ["convention-3" "svc-7" "lib-0"]]
                          (first (ms #(core/search s q {})))))
        prompts (atom 0)
        chars (atom 0)
        count-llm (fn [p] (swap! prompts inc) (swap! chars + (count (str p))) "")
        [consolidate-ms con] (ms #(consolidate/consolidate!
                                   s {:summarize-fn (fn [p] (count-llm p) "synthetic summary")
                                      :judge-fn (fn [p] (count-llm p)
                                                  "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"planted\"}")
                                      :enrich-fn (fn [_] "[]")}))]
    {:factor factor
     :services n-services
     :facts-planned (count facts)
     :build {:ms (long build-ms)
             :per-write-ms (/ (double build-ms) (count facts))
             :counts (:counts ingest)}
     :qa {:passed (count (filter :pass? qa))
          :total (count qa)
          :ms (long qa-ms)
          :failed (mapv :id (remove :pass? qa))}
     :reads {:point-median-ms (median read-lat)
             :point-max-ms (apply max read-lat)
             :search-median-ms (median search-lat)}
     :conflicts {:planted planted-conflicts
                 :open-after-sweep (:open (core/conflicts s))}
     :maintenance {:consolidate-ms (long consolidate-ms)
                   :sweep-candidates (get-in con [:sweep :candidates])
                   :llm-prompts @prompts
                   :llm-prompt-chars @chars}}))

(defn print-scale [{:keys [factor services facts-planned build qa reads conflicts maintenance]}]
  (println (format "scale %dx: %d services, %d facts through the real write path"
                   factor services facts-planned))
  (println (format "  build:       %dms total, %.2fms/write  %s"
                   (:ms build) (:per-write-ms build) (pr-str (:counts build))))
  (println (format "  rule-QA:     %d/%d correct (%dms)%s"
                   (:passed qa) (:total qa) (:ms qa)
                   (if (seq (:failed qa)) (str "  FAILED " (pr-str (:failed qa))) "")))
  (println (format "  reads:       point median %.1fms, max %.1fms; search median %.1fms"
                   (:point-median-ms reads) (:point-max-ms reads) (:search-median-ms reads)))
  (println (format "  conflicts:   %d planted -> %d open after sweep"
                   (:planted conflicts) (:open-after-sweep conflicts)))
  (println (format "  maintenance: consolidate %dms, %s sweep candidates, %d LLM prompts (%d chars) per pass"
                   (:consolidate-ms maintenance)
                   (str (:sweep-candidates maintenance))
                   (:llm-prompts maintenance)
                   (:llm-prompt-chars maintenance))))
