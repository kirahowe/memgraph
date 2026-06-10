(ns memgraph.llm
  "Shared shell-out to an authenticated agent CLI — the subscription-as-judge
  mechanism used by the session extractor and the conflict judge. The command
  receives the prompt on stdin and replies on stdout. Resolution order:
  explicit command > $MEMGRAPH_LLM_CMD > \"claude -p\"."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [memgraph.logic :as logic]))

(def default-command "claude -p")

(defn command [override]
  (or override (System/getenv "MEMGRAPH_LLM_CMD") default-command))

(defn complete!
  "Send prompt on stdin to cmd; return stdout. Throws on non-zero exit."
  [cmd prompt]
  (let [{:keys [exit out err]} @(p/process (p/tokenize cmd)
                                           {:in prompt :out :string :err :string})]
    (when-not (zero? exit)
      (logic/fail (str "LLM command failed: " cmd)
                  {:type :llm-command-failed :exit exit
                   :stderr (str/trim (or err ""))}))
    out))
