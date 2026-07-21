(ns claimgraph.bench.ab
  "The headline experiment (review §4.3.1, protocol from the AGENTS.md oral +
  SWE-ContextBench): same tasks, same agent, four memory arms —

    :none         no memory at all
    :static       a hand-written January-era CLAUDE.md (the file people
                  actually write once and let go stale)
    :auto-memory  the incumbent: the fixture's auto-memory pile in its final
                  state — post-compaction MEMORY.md plus the architecture
                  note that still carries the planted KuzuDB 'decision'
    :claimgraph     the ambient loop: compile-context's view over the graph
                  the recorded timeline built (deterministic, no LLM to set up)

  Tasks are memory-dependent questions about shoply answered as JSON, scored
  deterministically: task success, re-litigation of standing decisions,
  confabulation vs abstention, plus tokens and wall-clock per call. Beating
  :none is table stakes; beating :auto-memory is the product claim.

  Informational, never in CI: requires a real agent CLI
  ($CLAIMGRAPH_AB_AGENT, default `claude -p --output-format json`)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.bench.fixture :as fixture]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Arm contexts
;; ---------------------------------------------------------------------------

(def static-claude-md
  "The static arm: written in good faith in January, never maintained —
  which is the realistic failure mode the AGENTS.md study measured. Its
  hosting and dependency claims are stale by June."
  (str "# shoply — project context\n\n"
       "shoply is a small Clojure webshop (namespaces: shoply.api, shoply.auth,\n"
       "shoply.db).\n\n"
       "Decisions:\n"
       "- API style: REST with EDN bodies. GraphQL was proposed and REJECTED\n"
       "  (January) — schema/resolver/caching overhead for a three-person team.\n"
       "- Password hashing: argon2 (lives in shoply.auth).\n\n"
       "Infrastructure:\n"
       "- Deployed on Heroku (since Jan 5).\n"
       "- shoply.api writes straight to shoply.db for checkout; revisit later.\n"))

(defn auto-memory-context
  "The incumbent's final state, straight from the fixture: MEMORY.md after
  Claude compacted it (the Fly.io note is gone) plus the architecture topic
  file that still asserts the planted KuzuDB adoption."
  []
  (str "# MEMORY.md (auto-memory)\n\n"
       (get fixture/notes-pass-2 "MEMORY.md")
       "\n# architecture.md\n\n"
       (get fixture/notes-pass-1 "architecture.md")))

(defn claimgraph-context
  "The ambient loop's artifact: run the recorded timeline (deterministic, no
  LLM anywhere) and compile the graph's current view — exactly what the
  harness would inject at session start."
  []
  (let [s ((requiring-resolve 'claimgraph.store.memory/create))
        run-timeline! (requiring-resolve 'claimgraph.bench/run-timeline!)]
    (core/seed! s)
    (run-timeline! s)
    (context/compiled-view {:facts (store/-all-facts s)
                            :conflicts (:conflicts (core/conflicts s))
                            :now (core/now)})))

(defn arm-contexts []
  {:none nil
   :static static-claude-md
   :auto-memory (auto-memory-context)
   :claimgraph (claimgraph-context)})

;; ---------------------------------------------------------------------------
;; Tasks: memory-dependent, JSON-answered, deterministically scored
;; ---------------------------------------------------------------------------

(defn- mentions? [re s] (boolean (re-find re (str s))))

(def tasks
  [{:id :api-approach
    :desc "decision respect: does the standing GraphQL rejection hold?"
    :prompt (str "The mobile team wants a more flexible query API for the shoply "
                 "storefront and asked you to propose an approach for the API layer. "
                 "Reply with ONLY this JSON: {\"proposal\": \"<one-sentence approach>\", "
                 "\"prior_decisions\": \"<any standing project decisions that bear on this, "
                 "or 'none known'>\"}")
    :score (fn [{:keys [proposal prior_decisions]}]
             {:success (not (mentions? #"(?i)graphql" proposal))
              :relitigated (and (mentions? #"(?i)graphql" proposal)
                                (not (mentions? #"(?i)graphql" prior_decisions)))})}

   {:id :hosting
    :desc "current truth vs stale context: where does shoply deploy today?"
    :prompt (str "Where does shoply deploy today? Reply with ONLY this JSON: "
                 "{\"provider\": \"<name or 'unknown'>\", "
                 "\"confidence\": \"known\"|\"unknown\"}")
    :score (fn [{:keys [provider confidence]}]
             (let [fly? (mentions? #"(?i)fly" provider)
                   unknown? (or (= confidence "unknown")
                                (mentions? #"(?i)unknown" provider))]
               {:success fly?
                :confabulated (and (not fly?) (not unknown?))
                :abstained unknown?}))}

   {:id :hashing
    :desc "rename recovery: where does password hashing live, using what?"
    :prompt (str "In shoply, which namespace owns password hashing and which "
                 "algorithm does it use? Reply with ONLY this JSON: "
                 "{\"namespace\": \"<ns or 'unknown'>\", \"algorithm\": \"<name or 'unknown'>\"}")
    :score (fn [{:keys [namespace algorithm]}]
             {:success (and (mentions? #"(?i)identity" namespace)
                            (mentions? #"(?i)argon2" algorithm))
              :stale-name (mentions? #"(?i)auth\b" namespace)
              :abstained (mentions? #"(?i)unknown" (str namespace algorithm))})}

   {:id :react-lang
    :desc "contamination: React here is an in-house clojure library"
    :prompt (str "In the shoply project there is a dependency called React. "
                 "What language is it written in? Reply with ONLY this JSON: "
                 "{\"language\": \"<language or 'unknown'>\"}")
    :score (fn [{:keys [language]}]
             {:success (mentions? #"(?i)clojure" language)
              :contaminated (mentions? #"(?i)javascript|typescript|jsx" language)
              :abstained (mentions? #"(?i)unknown" language)})}

   {:id :api-db-dep
    :desc "staleness: the api→db dependency was dropped in March"
    :prompt (str "Does shoply.api still depend on shoply.db directly? "
                 "Reply with ONLY this JSON: "
                 "{\"depends\": true|false|\"unknown\"}")
    :score (fn [{:keys [depends]}]
             {:success (false? depends)
              :stale (true? depends)
              :abstained (= depends "unknown")})}

   {:id :kuzu-adoption
    :desc "decision-violation awareness: kuzu-db has a standing rejection"
    :prompt (str "A teammate wants to add kuzu-db as shoply's graph cache and asked "
                 "if there is anything they should know first. Reply with ONLY this "
                 "JSON: {\"concerns\": \"<what they should know, or 'none known'>\"}")
    :score (fn [{:keys [concerns]}]
             {:success (mentions? #"(?i)(reject|decided against|ruled out|unmaintained|archived|decision|history|conflict)"
                                  concerns)
              :relitigated (mentions? #"(?i)none known" concerns)})}

   {:id :catalog-db
    :desc "skill-layer abstention: the catalog database was never recorded"
    :prompt (str "What database does shoply use for its product catalog? "
                 "Reply with ONLY this JSON: "
                 "{\"answer\": \"<name or 'unknown'>\", \"confidence\": \"known\"|\"unknown\"}")
    :score (fn [{:keys [answer confidence]}]
             (let [unknown? (or (= confidence "unknown")
                                (mentions? #"(?i)unknown|not recorded" answer))]
               {:success unknown?
                :confabulated (not unknown?)}))}])

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(def default-agent "claude -p --output-format json")

(defn- agent-command []
  (or (System/getenv "CLAIMGRAPH_AB_AGENT") default-agent))

(defn- build-prompt [context task]
  (str "You are answering questions about a software project called shoply.\n"
       (when context
         (str "\nProject memory available to you:\n<project-memory>\n"
              context "\n</project-memory>\n"))
       "\nAnswer from the project memory above if present; otherwise from what "
       "you know. If you do not know, say unknown rather than guessing.\n\n"
       (:prompt task)))

(defn- parse-agent-json
  "claude -p --output-format json wraps the answer: {:result text :usage {...}}.
  A bare-text agent just returns text. Be tolerant of both."
  [out]
  (let [outer (try (json/parse-string out true) (catch Exception _ nil))]
    (if (and (map? outer) (:result outer))
      {:text (:result outer)
       :tokens (+ (get-in outer [:usage :input_tokens] 0)
                  (get-in outer [:usage :output_tokens] 0))
       :cost (:total_cost_usd outer)}
      {:text out :tokens nil :cost nil})))

(defn- parse-answer-json
  "Fish the task's JSON object out of the agent's reply (tolerates fences
  and prose around it)."
  [text]
  (let [m (re-find #"(?s)\{.*\}" (str text))]
    (when m
      (try (json/parse-string m true) (catch Exception _ nil)))))

(defn run-one [context task]
  (let [prompt (build-prompt context task)
        t0 (System/nanoTime)
        {:keys [exit out err]} @(p/process (p/tokenize (agent-command))
                                           {:in prompt :out :string :err :string})
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (if-not (zero? exit)
      {:task (:id task)
       :error (let [e (str/trim (str err))]
                (if (str/blank? e)
                  (str "agent exit " exit ": " (subs (str out) 0 (min 300 (count (str out)))))
                  e))
       :exit exit :ms ms}
      (let [{:keys [text tokens cost]} (parse-agent-json out)
            answer (parse-answer-json text)
            verdict (when answer
                      (try ((:score task) answer)
                           (catch Exception _ nil)))]
        (merge {:task (:id task) :ms ms :tokens tokens :cost cost
                :answer answer :raw (str/trim (str text))}
               (or verdict {:success false :unparseable true}))))))

;; ---------------------------------------------------------------------------
;; The experiment
;; ---------------------------------------------------------------------------

(defn run-ab
  "arms/tasks selectable for pilots; results land in a JSONL file for later
  analysis and a summary table on stdout."
  [{:keys [arms task-ids]}]
  (let [contexts (arm-contexts)
        arms (or arms (keys contexts))
        selected (if (seq task-ids)
                   (filter (comp (set task-ids) :id) tasks)
                   tasks)]
    (vec (for [arm arms]
           {:arm arm
            :results (mapv #(run-one (get contexts arm) %) selected)}))))

(defn- rate [k rs]
  (let [n (count rs)]
    (if (zero? n) 0.0 (double (/ (count (filter k rs)) n)))))

(defn summarize [arm-results]
  (mapv (fn [{:keys [arm results]}]
          {:arm arm
           :tasks (count results)
           :success (rate :success results)
           :relitigated (count (filter :relitigated results))
           :confabulated (count (filter :confabulated results))
           :abstained (count (filter :abstained results))
           :errors (count (filter :error results))
           :mean-tokens (let [ts (keep :tokens results)]
                          (when (seq ts) (long (/ (reduce + ts) (count ts)))))
           :mean-ms (long (/ (reduce + (map :ms results)) (max 1 (count results))))})
        arm-results))

(defn- results-file []
  (let [dir (fs/path "bench" "results")]
    (fs/create-dirs dir)
    (str (fs/path dir (str "ab-" (System/currentTimeMillis) ".jsonl")))))

(defn print-ab [arm-results]
  (let [file (results-file)]
    (doseq [{:keys [arm results]} arm-results
            r results]
      (spit file (str (json/generate-string (assoc r :arm arm)) "\n") :append true))
    (println (format "%-12s %-6s %-8s %-7s %-7s %-6s %-7s %-8s"
                     "arm" "tasks" "success" "relit" "confab" "abst" "tokens" "mean-ms"))
    (doseq [{:keys [arm tasks success relitigated confabulated abstained
                    mean-tokens mean-ms errors]} (summarize arm-results)]
      (println (format "%-12s %-6d %-8.2f %-7d %-7d %-6d %-7s %-8d%s"
                       (name arm) tasks success relitigated confabulated abstained
                       (or mean-tokens "-") mean-ms
                       (if (pos? errors) (str "  (" errors " errors)") ""))))
    (println "\nper-task grid (ok/FAIL by arm):")
    (let [by-arm (into {} (map (juxt :arm :results)) arm-results)
          arms (map :arm arm-results)]
      (doseq [t (map :task (:results (first arm-results)))]
        (println (format "  %-16s %s" (name t)
                         (str/join "  "
                                   (for [a arms]
                                     (let [r (first (filter #(= t (:task %)) (by-arm a)))]
                                       (format "%s:%s" (name a)
                                               (cond (:error r) "ERR"
                                                     (:success r) "ok"
                                                     :else "FAIL")))))))))
    (println "\nraw results:" file)))
