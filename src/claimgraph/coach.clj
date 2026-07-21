(ns claimgraph.coach
  "Gated push — the coach pattern (review §3.2 WebCoach, §3.4 SABER): the
  push-side complement to the skill's pull-side judgment. Given what the
  agent is about to do, decide WHETHER the graph holds something worth
  interrupting with; stay silent otherwise. Always-on injection measurably
  doesn't pay (the AGENTS.md result) — the gate is the feature.

  What clears the gate, in order of authority: standing commitments touching
  the task (the do-not-relitigate list — SABER says the moment before a
  mutating action is exactly when to consult standing decisions), known
  failure modes (the #15 lessons), and open conflicts on the entities in
  play. Everything else stays out of the agent's context.

  Deterministic end to end: hybrid retrieval finds what the task touches;
  the gate and the briefing are pure functions of it."
  (:require [clojure.string :as str]
            [claimgraph.core :as core]))

(def max-per-section 3)

(defn- day [d] (when d (subs (str (.toInstant ^java.util.Date d)) 0 10)))

(defn- obj [f] (or (get-in f [:object-ref :name]) (:object-lit f)))

(defn- line [f]
  (str (get-in f [:subject :name]) " " (name (:predicate f)) " \"" (obj f) "\""))

(defn briefing
  "Pure: gate verdict + compact briefing from what retrieval surfaced.
  push? is true only when something authoritative is at stake."
  [{:keys [facts conflicts]}]
  (let [commitments (->> facts
                         (filter #(= :commitment (:epistemic %)))
                         (take max-per-section)
                         vec)
        hazards (->> facts
                     (filter #(= :core/failure-mode (:predicate %)))
                     (take max-per-section)
                     vec)
        open (vec (take max-per-section conflicts))]
    {:push (boolean (or (seq commitments) (seq hazards) (seq open)))
     :commitments commitments
     :hazards hazards
     :conflicts open
     :briefing
     (when (or (seq commitments) (seq hazards) (seq open))
       (str "Project memory has standing context for this task:\n"
            (str/join "\n"
                      (concat
                       (for [f commitments]
                         (str "- standing decision: " (line f)
                              (when-let [d (day (:t-valid f))] (str " (since " d ")"))))
                       (for [f hazards]
                         (str "- known failure mode: " (line f)))
                       (for [{:keys [fact candidate]} open]
                         (str "- OPEN CONFLICT: " (line fact)
                              " vs " (line candidate)
                              " — unresolved, ask before relying on either"))))
            "\nQuery `bin/claim` for history and provenance before overriding any of these."))}))

(defn consult
  "Retrieve what the task touches, gate, brief."
  [s query]
  (let [sr (core/search s query {})
        facts (:facts sr)
        fact-ids (set (map :id facts))
        ent-ids (set (map :id (:matched-entities sr)))
        touching (filterv (fn [{:keys [fact candidate]}]
                            (or (fact-ids (:id fact)) (fact-ids (:id candidate))
                                (ent-ids (get-in fact [:subject :id]))
                                (ent-ids (get-in candidate [:subject :id]))))
                  (:conflicts (core/conflicts s)))]
    (assoc (briefing {:facts facts :conflicts touching})
           :query query)))

;; ---------------------------------------------------------------------------
;; Claude Code hook adapter (UserPromptSubmit)
;; ---------------------------------------------------------------------------

(defn hook-input->query
  "Pure: the harness's hook JSON (already parsed) -> the text to consult on.
  UserPromptSubmit carries :prompt; PreToolUse carries :tool_input we render
  crudely — the coach only needs tokens to hit entities."
  [m]
  (or (:prompt m)
      (some->> (:tool_input m) vals (filter string?) (str/join " "))
      ""))

(defn hook-output
  "Pure: verdict -> what the hook prints. Claude Code injects
  additionalContext from UserPromptSubmit hooks; silence (nil) means the
  gate held and the agent's context stays clean."
  [{:keys [push briefing]}]
  (when push
    {:hookSpecificOutput {:hookEventName "UserPromptSubmit"
                          :additionalContext briefing}}))
