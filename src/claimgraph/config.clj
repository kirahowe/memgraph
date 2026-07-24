(ns claimgraph.config
  "Where claimgraph finds things. No file location is assumed: everything the
  tool touches resolves through one precedence chain —

      CLI flag  >  environment variable  >  project config file  >  default

  The project config file is JSON at $CLAIMGRAPH_CONFIG or
  ./.claimgraph/config.json, keyed by the kebab-case names below. It is
  committable (unlike the live store next to it) and is what `claim setup`
  writes when non-default locations are chosen, so one person's choices hold
  for every writer of the repo. `claim config` prints every setting with its
  resolved value and the layer it came from.

  Resolution is pure (resolve-setting over passed opts/env/config maps); the
  only impure seams are reading the real environment and the config file."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(def settings
  "The registry of configurable settings: option key -> where a value may
  come from. :opt-key is the CLI option when it differs from the setting name
  (--dir means the notes dir on the commands that take it). A nil :default
  means the consumer computes one (documented in :desc)."
  (array-map
   :db {:flag "--db" :env "CLAIMGRAPH_DB" :default ".claimgraph/db"
        :desc "Store path (an LMDB directory; the oplog, evidence, lock, and stamp files derive from it as siblings)."}
   :harness {:flag "--harness" :env "CLAIMGRAPH_HARNESS" :default "claude-code"
             :desc "Which harness's auto-memory the ambient loop consumes (claude-code | codex)."}
   :notes-dir {:flag "--dir" :opt-key :dir :env "CLAIMGRAPH_NOTES_DIR"
               :desc "The harness's auto-memory notes directory. Default: resolved per harness from its own layout, honoring CLAUDE_CONFIG_DIR / CODEX_HOME."}
   :inject-file {:flag "--inject-file" :env "CLAIMGRAPH_INJECT_FILE"
                 :desc "The file the harness injects at session start — compile-context's write target, relative to the notes dir (or absolute). Default per harness: MEMORY.md / memory_summary.md."}
   :settings-file {:flag "--settings-file" :env "CLAIMGRAPH_SETTINGS_FILE"
                   :desc "The hook-settings file `hooks install` writes. Default: <project>/.claude/settings.json."}
   :skills-dir {:flag "--skills-dir" :env "CLAIMGRAPH_SKILLS_DIR"
                :desc "Where `setup` installs the agent skill. Default: <project>/.claude/skills."}
   :extractor {:flag "--extractor" :env "CLAIMGRAPH_LLM_CMD" :default "claude -p"
               :desc "LLM command for extraction and judging: prompt on stdin, completion on stdout."}
   :evidence-dir {:flag "--evidence-dir" :env "CLAIMGRAPH_EVIDENCE_DIR"
                  :desc "Content-addressed raw-evidence store. Default: <db>.evidence."}
   :consolidate-days {:flag "--consolidate-days" :env "CLAIMGRAPH_CONSOLIDATE_DAYS"
                      :default 7 :coerce :long
                      :desc "Consolidation cadence for hooks run, in days (0 = every run)."}
   :code-ingest {:flag "--code-ingest" :env "CLAIMGRAPH_CODE_INGEST" :default "session-end"
                 :desc "Whether hooks run refreshes code facts as its first stage (session-end | manual). Delta-gated either way; manual opts a project with an expensive analyzer out of the ambient pass. The code-analyzers map (config-file only) tunes which analyzers run."}))

;; ---------------------------------------------------------------------------
;; Pure: resolution
;; ---------------------------------------------------------------------------

(defn- coerce-value [spec v]
  (if (and (= :long (:coerce spec)) (string? v))
    (parse-long v)
    v))

(defn resolve-setting
  "Pure. One setting against the three layers + default ->
  {:value v :source :flag|:env|:config|:default}, or {:value nil :source nil}
  when unset everywhere and there is no static default."
  [k {:keys [opts env config]}]
  (let [spec (get settings k)
        opt (get opts (or (:opt-key spec) k))
        env-v (some->> (:env spec) (get env))
        cfg-v (get config k)]
    (cond
      (some? opt) {:value opt :source :flag}
      (some? env-v) {:value (coerce-value spec env-v) :source :env}
      (some? cfg-v) {:value (coerce-value spec cfg-v) :source :config}
      (some? (:default spec)) {:value (:default spec) :source :default}
      :else {:value nil :source nil})))

(defn merge-defaults
  "Pure: fill absent CLI opts from the env/config layers for the given
  setting keys — flags stay authoritative, commands stay oblivious. Static
  defaults are NOT merged: those remain owned by each consumer, so a command
  only sees a value the user actually set somewhere."
  [opts ctx ks]
  (reduce (fn [o k]
            (let [ok (or (:opt-key (get settings k)) k)
                  {:keys [value source]} (resolve-setting k (assoc ctx :opts o))]
              (if (and (contains? #{:env :config} source) (nil? (get o ok)))
                (assoc o ok value)
                o)))
          opts ks))

;; ---------------------------------------------------------------------------
;; Shell: the real environment and config file
;; ---------------------------------------------------------------------------

(defn config-file-path
  "Where the project config file lives: $CLAIMGRAPH_CONFIG or
  ./.claimgraph/config.json (relative to cwd, like the default db path)."
  ([] (config-file-path (into {} (System/getenv))))
  ([env] (or (get env "CLAIMGRAPH_CONFIG") ".claimgraph/config.json")))

(defn read-config-file
  "Parsed config map (keyword keys) or nil. A malformed file fails loudly —
  silently ignoring configuration is worse than an error."
  [path]
  (when (fs/exists? path)
    (json/parse-string (slurp (str path)) true)))

(defn context
  "The two ambient layers, read once: {:env ... :config ... :config-file path}."
  []
  (let [env (into {} (System/getenv))
        path (config-file-path env)]
    {:env env :config (read-config-file path) :config-file (str path)}))

(defn with-defaults
  "Shell version of merge-defaults against the real env + config file."
  [opts ks]
  (merge-defaults opts (context) ks))

(defn value
  "Resolve one setting against the real environment. -> the value or nil."
  [k opts]
  (:value (resolve-setting k (assoc (context) :opts opts))))

(defn describe
  "The `claim config` payload: every setting with its resolved value, the
  layer it came from, and how to set it at each layer."
  [opts]
  (let [ctx (assoc (context) :opts opts)]
    {:config-file {:path (:config-file ctx)
                   :exists (boolean (:config ctx))}
     :precedence "flag > env > config-file > default"
     :settings
     (into (array-map)
           (for [[k spec] settings]
             [k (merge (resolve-setting k ctx)
                       {:flag (:flag spec)
                        :env (:env spec)
                        :config-key (name k)
                        :desc (:desc spec)})]))}))
