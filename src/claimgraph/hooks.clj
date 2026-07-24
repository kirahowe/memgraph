(ns claimgraph.hooks
  "The ambient loop's automation (docs/consuming-auto-memory.md §4): one verb
  a Claude Code SessionEnd hook can call, and an installer that wires it into
  the project's hook configuration.

  `hooks run` = ingest-code-if-changed → ingest-notes → compile-context →
  consolidate-if-due. Each stage is attempted independently: an analyzer or
  extractor failure (no `claude` on PATH, not authenticated) must never
  stop the deterministic compile — the next session still deserves the
  freshest view the graph can produce. The code stage runs FIRST (so
  extraction's entity roster and conflict ground truth are fresh) and is
  delta-gated on <git-sha>+<dirty-digest> against the newest :code
  episode's ref — free when nothing changed, reconciling when anything did,
  including teammates' pulled changes; the `code-ingest` setting
  (session-end | manual) opts it out. Consolidation runs at lower
  frequency, gated by a sibling stamp file next to the db (default: at
  most every 7 days).

  `hooks install` merges a SessionEnd entry into the project's hook settings
  (default <project>/.claude/settings.json; overridable via --settings-file /
  $CLAIMGRAPH_SETTINGS_FILE / the project config), idempotently: re-running
  updates the claimgraph entry in place and never duplicates it; everything
  else in the file is preserved."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.ingest.notes :as notes]))

(def default-consolidate-days 7)
(def hook-timeout-seconds 600)

;; ---------------------------------------------------------------------------
;; hooks run
;; ---------------------------------------------------------------------------

(defn- attempt [f]
  (try (f)
       (catch Exception e
         (merge {:status :error :error (ex-message e)}
                (dissoc (ex-data e) :claimgraph/error)))))

(defn- stamp-path [db] (str db ".last-consolidate"))

(defn consolidate-due?
  "Due when the stamp is absent or older than the window. The stamp lives
  next to the db directory (<db>.last-consolidate), so per-store cadence
  follows the store."
  [db days now]
  (let [stamp (stamp-path db)]
    (or (not (fs/exists? stamp))
        (>= (- (.getTime ^java.util.Date now)
               (.toMillis (fs/last-modified-time stamp)))
            (* days 86400000)))))

(defn run!
  "The SessionEnd pass: refresh code facts when the code moved, capture
  what the harness's auto-memory learned, recompile the injected view,
  consolidate when due. Stages report independently; a failed stage is an
  :error (or :partial) entry, not an abort.

  opts: :db (the store path, for the consolidation stamp)
        :code-ingest (\"session-end\" default | \"manual\")
        :command-fn :which :analyzers (ingest-code, injectable for tests)
        :harness :project :dir :extractor :extractor-fn (ingest/compile)
        :inject-file (compile-context's write-target override)
        :consolidate-days (default 7; 0 = every run)
        :command :summarize-fn :judge-fn :resolve :min-confidence (consolidate)"
  [s {:keys [db consolidate-days code-ingest] :as opts}]
  (let [code (if (= "manual" (some-> code-ingest name))
               {:status :skipped :reason "code-ingest is set to manual — run ingest-code yourself"}
               (attempt #((requiring-resolve 'claimgraph.ingest.code/ingest-if-changed!)
                          s (assoc (select-keys opts [:command-fn :which :analyzers])
                                   :dir (:project opts)))))
        ingest (attempt #(notes/ingest! s (select-keys opts [:harness :project :dir :ctx
                                                             :extractor :extractor-fn
                                                             :evidence-dir])))
        compiled (attempt #(context/compile! s (select-keys opts [:harness :project :dir
                                                                  :ctx :inject-file
                                                                  :budget])))
        days (or consolidate-days default-consolidate-days)
        due? (consolidate-due? db days (core/now))
        consolidated (if-not due?
                       {:status :skipped :reason (str "ran within the last " days " days")}
                       (let [r (attempt #((requiring-resolve 'claimgraph.consolidate/consolidate!)
                                          s (select-keys opts [:command :summarize-fn :judge-fn
                                                               :resolve :min-confidence])))]
                         (when-not (= :error (:status r))
                           (spit (stamp-path db) (str (.toInstant ^java.util.Date (core/now)))))
                         r))]
    {:status (if (some #(contains? #{:error :partial} (:status %))
                       [code ingest compiled consolidated])
               :partial :ok)
     :ingest-code code
     :ingest-notes ingest
     :compile-context compiled
     :consolidate consolidated}))

;; ---------------------------------------------------------------------------
;; hooks install
;; ---------------------------------------------------------------------------

(defn install-plan
  "Pure: merge one claimgraph hook entry into settings under an event key.
  `marker` identifies our entry among foreign hooks (idempotent: replaced
  in place when present, appended otherwise); everything else in the file
  is untouched."
  [settings event entry marker]
  (let [existing (vec (get-in settings [:hooks event]))
        ours? (fn [e] (some #(str/includes? (str (:command %)) marker)
                            (:hooks e)))]
    (assoc-in settings [:hooks event]
              (if (some ours? existing)
                (mapv #(if (ours? %) entry %) existing)
                (conj existing entry)))))

(defn install!
  "Wire the ambient loop into the project's hook settings: a SessionEnd
  entry always, and with :coach also a UserPromptSubmit entry that runs the
  gated push (claim coach --hook) — a briefing lands only when standing
  decisions, failure modes, or open conflicts touch the task.
  opts: :project (default cwd) :harness (default claude-code)
        :settings-file (where the harness reads hook config from; default
        <project>/.claude/settings.json — override for settings.local.json,
        a relocated config dir, or another harness's layout)
        :consolidate-days :coach :bin (the claim executable for the hook
        command; auto-detects a repo-local bin/claim, else assumes PATH)"
  [{:keys [project harness settings-file consolidate-days coach bin]}]
  (let [project (str (fs/canonicalize (or project ".")))
        settings-file (str (or settings-file
                               (fs/path project ".claude" "settings.json")))
        settings (if (fs/exists? settings-file)
                   (json/parse-string (slurp settings-file) true)
                   {})
        bin (or bin
                (if (fs/exists? (fs/path project "bin" "claim"))
                  "bin/claim" "claim"))
        run-cmd (str bin " hooks run --harness " (name (or harness :claude-code))
                     (when consolidate-days (str " --consolidate-days " consolidate-days)))
        coach-cmd (str bin " coach --hook")
        updated (cond-> (install-plan settings :SessionEnd
                                      {:hooks [{:type "command" :command run-cmd
                                                :timeout hook-timeout-seconds}]}
                                      "hooks run")
                  coach (install-plan :UserPromptSubmit
                                      {:hooks [{:type "command" :command coach-cmd
                                                :timeout 30}]}
                                      "coach --hook"))
        added? (not= (count (get-in settings [:hooks :SessionEnd]))
                     (count (get-in updated [:hooks :SessionEnd])))]
    (fs/create-dirs (fs/parent settings-file))
    (spit settings-file (str (json/generate-string updated {:pretty true}) "\n"))
    (cond-> {:status (if added? :installed :updated)
             :settings settings-file
             :command run-cmd
             :note "every session now ends with ingest-code-if-changed + ingest-notes + compile-context; consolidate runs when due"}
      coach (assoc :coach-command coach-cmd))))
