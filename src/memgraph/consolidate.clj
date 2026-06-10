(ns memgraph.consolidate
  "Dreaming-style offline consolidation: one pass that summarizes and closes
  open episodes (LLM, with a mechanical fallback), judges open conflicts,
  decays stale confidence, and reports :x/* predicates that have earned
  promotion review.

  Functional core / imperative shell: episode planning, prompt construction,
  summary parsing, the mechanical fallback, and promotion-candidate selection
  are pure; `consolidate!` executes the stages. The LLM is pluggable as
  everywhere ($MEMGRAPH_LLM_CMD, default claude -p; tests inject
  :summarize-fn / :judge-fn).

  Closing an episode with a summary is what turns bulky episodic memory into
  something retrievable: summaries are full-text indexed, so \"why did we do
  X\" becomes a search."
  (:require [clojure.string :as str]
            [memgraph.core :as core]
            [memgraph.judge :as judge]
            [memgraph.llm :as llm]
            [memgraph.store :as store]))

(def default-min-usage 3)
(def max-summary-chars 600)

;; ---------------------------------------------------------------------------
;; Pure: episode planning
;; ---------------------------------------------------------------------------

(defn plan-episodes
  "Which episodes the pass should close: open ones that contain facts.
  Open-but-empty episodes are left alone (they may belong to a session still
  in flight) and reported."
  [episodes facts]
  (let [by-ep (group-by :episode facts)
        open (remove :closed-at episodes)]
    {:to-close (vec (for [ep open
                          :let [efacts (by-ep (:id ep))]
                          :when (seq efacts)]
                      {:episode ep :facts (vec efacts)}))
     :skipped-empty (vec (map :id (filter #(empty? (by-ep (:id %))) open)))}))

;; ---------------------------------------------------------------------------
;; Pure: prompt, parsing, fallback
;; ---------------------------------------------------------------------------

(defn- fact-line [f]
  (str "- " (get-in f [:subject :name])
       " " (subs (str (:predicate f)) 1)
       " " (or (get-in f [:object-ref :name]) (:object-lit f))
       " (" (name (:epistemic f :observation))
       ", " (name (:source-type f :user-assertion)) ")"))

(defn summary-prompt [episode facts]
  (str
   "Summarize what this episode added to a project's knowledge graph, in 1-3\n"
   "sentences of plain text. Write for a developer skimming project history:\n"
   "lead with decisions and preferences, mention structural facts only in\n"
   "aggregate. Output the summary only — no preamble, no quotes, no markdown.\n\n"
   "Episode: source " (name (:source-type episode :unknown))
   ", ref \"" (:ref episode) "\""
   (when-let [t (:opened-at episode)] (str ", opened " t)) "\n"
   "Facts:\n"
   (str/join "\n" (map fact-line facts)) "\n"))

(defn parse-summary
  "Tolerant cleanup of the LLM's summary: drop fences, collapse to one
  paragraph, cap length. Blank responses become nil so the caller can fall
  back."
  [response]
  (let [text (->> (str/split-lines (or response ""))
                  (map str/trim)
                  (remove #(str/starts-with? % "```"))
                  (remove str/blank?)
                  (str/join " "))]
    (when-not (str/blank? text)
      (if (> (count text) max-summary-chars)
        (str (subs text 0 max-summary-chars) "…")
        text))))

(defn mechanical-summary
  "The no-LLM fallback: a digest of what the episode recorded."
  [episode facts]
  (str (name (:source-type episode :unknown))
       " episode (" (:ref episode) "): " (count facts) " facts — "
       (str/join ", " (for [[p n] (sort-by (comp - val)
                                           (frequencies (map :predicate facts)))]
                        (str n " " (subs (str p) 1))))))

;; ---------------------------------------------------------------------------
;; Pure: promotion candidates
;; ---------------------------------------------------------------------------

(defn promotion-candidates
  "Staging (:testing) predicates used at least min-usage times — the ones
  worth promoting to :core/* (or pruning, if the usage is junk)."
  [predicates facts min-usage]
  (let [counts (frequencies (map :predicate facts))]
    (->> predicates
         (filter #(= :testing (:status %)))
         (keep (fn [p]
                 (let [n (get counts (:id p) 0)]
                   (when (>= n min-usage)
                     {:id (:id p) :usage n :definition (:definition p)}))))
         (sort-by (comp - :usage))
         vec)))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn- summarize-episodes! [s run {:keys [to-close skipped-empty]}]
  {:closed (mapv (fn [{:keys [episode facts]}]
                   (let [summary (or (parse-summary
                                      (try (run (summary-prompt episode facts))
                                           (catch Exception _ nil)))
                                     (mechanical-summary episode facts))]
                     (store/-close-episode s (:id episode) summary (core/now))
                     {:episode (:id episode)
                      :facts (count facts)
                      :summary summary}))
                 to-close)
   :skipped-empty skipped-empty})

(defn consolidate!
  "Run the full consolidation pass.
  opts: :command (LLM command; default $MEMGRAPH_LLM_CMD or claude -p)
        :summarize-fn :judge-fn (prompt -> response; injectable, used by tests)
        :resolve :min-confidence (forwarded to the conflict judge)
        :older-than-days :factor (forwarded to decay)
        :min-usage (promotion-candidate threshold, default 3)"
  [s {:keys [command summarize-fn judge-fn resolve min-confidence
             older-than-days factor min-usage]}]
  (let [facts (store/-all-facts s)
        run (or summarize-fn (partial llm/complete! (llm/command command)))
        episodes (summarize-episodes! s run
                                      (plan-episodes (store/-list-episodes s) facts))
        conflicts (try (judge/judge-conflicts! s {:command command
                                                  :judge-fn judge-fn
                                                  :resolve resolve
                                                  :min-confidence min-confidence})
                       (catch Exception e {:error (ex-message e)}))
        decay (core/decay s {:older-than-days older-than-days :factor factor})]
    {:status :consolidated
     :episodes episodes
     :conflicts conflicts
     :decay decay
     :promotion-candidates (promotion-candidates
                            (store/-list-predicates s {:status :testing})
                            facts
                            (or min-usage default-min-usage))}))
