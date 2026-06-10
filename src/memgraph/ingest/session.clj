(ns memgraph.ingest.session
  "Session-log extractor: turn a session transcript into durable facts via a
  pluggable LLM extractor. Functional core / imperative shell: transcript
  normalization, prompt construction, and response parsing/clamping are all
  pure; the only effects are reading the transcript and shelling out to the
  extractor command.

  The default extractor is `claude -p` — an already-authenticated agent CLI
  (subscription-as-judge, ~$0 marginal). Override with --extractor or
  $MEMGRAPH_EXTRACTOR_CMD; tests inject :extractor-fn and never shell out.

  Session-derived facts are second-class evidence by design: confidence is
  capped at 0.7 and source-type is forced to :session-log."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [memgraph.core :as core]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(def max-confidence 0.7)
(def default-confidence 0.6)
(def default-extractor "claude -p")

;; ---------------------------------------------------------------------------
;; Pure: transcript normalization
;; ---------------------------------------------------------------------------

(defn- content-text [content]
  (cond
    (string? content) content
    (sequential? content) (->> content
                               (keep #(when (= "text" (:type %)) (:text %)))
                               (str/join "\n"))
    :else nil))

(defn- jsonl-message
  "One JSONL line -> {:role :text}, :skip for JSON lines with no message
  text (tool results, summaries), nil for non-JSON."
  [line]
  (try
    (let [m (json/parse-string line true)]
      (when (map? m)
        (let [msg (or (:message m) m)
              text (content-text (:content msg))]
          (if (and (:role msg) (not (str/blank? (str text))))
            {:role (:role msg) :text text}
            :skip))))
    (catch Exception _ nil)))

(defn ->transcript
  "Plain text passes through untouched; Claude-Code-style session JSONL
  (every line a JSON map) is flattened to \"role: text\" turns."
  [content]
  (let [lines (remove str/blank? (str/split-lines content))
        parsed (map jsonl-message lines)]
    (if (and (seq parsed) (every? some? parsed))
      (->> parsed
           (remove #{:skip})
           (map #(str (name (:role %)) ": " (:text %)))
           (str/join "\n\n"))
      content)))

;; ---------------------------------------------------------------------------
;; Pure: prompt
;; ---------------------------------------------------------------------------

(defn extraction-prompt [transcript predicates]
  (str
   "Extract durable project memory from this coding-session transcript.\n\n"
   "Emit one JSON object per line (JSONL) and nothing else — no prose, no code fences.\n"
   "Keys: subject (entity name), predicate, object, object_kind (\"entity\"|\"literal\"),\n"
   "class (\"observation\"|\"commitment\"|\"preference\"), confidence (0.0-1.0), scope (optional).\n\n"
   "Extract ONLY durable knowledge: stated preferences, decisions made or reported\n"
   "(including rejected alternatives), discovered gotchas and constraints, and conventions.\n"
   "Do NOT extract transient task chatter, edit-by-edit narration, or anything already\n"
   "obvious from the code itself. Prefer fewer, higher-value facts.\n"
   "Subjects and entity-kind objects must be stable names (services, namespaces, tools,\n"
   "ADR ids, people), never sentences; free text belongs in literal objects.\n\n"
   "Allowed predicates (coin x/<new-name> only if none fits):\n"
   (str/join "\n" (for [p predicates]
                    (str "  " (subs (str (:id p)) 1) " — " (:definition p))))
   "\n\nIf nothing qualifies, output nothing.\n\n"
   "<transcript>\n" transcript "\n</transcript>"))

;; ---------------------------------------------------------------------------
;; Pure: response parsing & clamping
;; ---------------------------------------------------------------------------

(defn parse-extraction
  "Tolerant JSONL parse of an LLM response: keeps JSON-object lines, drops
  prose, fences, and junk."
  [response]
  (->> (str/split-lines (or response ""))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "```")))
       (keep #(try (let [m (json/parse-string % true)]
                     (when (map? m) m))
                   (catch Exception _ nil)))
       (mapv logic/normalize-keys)))

(defn prepare-facts
  "Validate and clamp extracted candidates: complete triples become facts
  with confidence capped at 0.7 and source-type forced to :session-log;
  incomplete ones are returned as :rejected."
  [extracted]
  (let [complete? (fn [m] (every? #(not (str/blank? (str (get m %))))
                                  [:subject :predicate :object]))
        clamp (fn [m]
                (let [c (:confidence m)]
                  (-> m
                      (assoc :confidence (min max-confidence
                                              (if (number? c) (double c) default-confidence)))
                      (assoc :source-type :session-log))))
        {facts true rejected false} (group-by complete? extracted)]
    {:facts (mapv clamp facts)
     :rejected (vec rejected)}))

;; ---------------------------------------------------------------------------
;; Shell: extractor process + ingestion
;; ---------------------------------------------------------------------------

(defn- run-extractor! [cmd prompt]
  (let [{:keys [exit out err]} @(p/process (p/tokenize cmd)
                                           {:in prompt :out :string :err :string})]
    (when-not (zero? exit)
      (logic/fail (str "Extractor command failed: " cmd)
                  {:type :extractor-failed :exit exit
                   :stderr (str/trim (or err ""))}))
    out))

(defn extract!
  "Run the pluggable extractor over a transcript and ingest the results.
  opts: :file (path; stdin when absent) | :transcript (string, wins over :file)
        :ref (episode ref) :extractor (command string)
        :extractor-fn (prompt -> response; the injection seam)
        :dry-run (extract and report, write nothing)"
  [s {:keys [file transcript ref extractor extractor-fn dry-run]}]
  (let [content (or transcript (if file (slurp file) (slurp *in*)))
        run (or extractor-fn
                (partial run-extractor!
                         (or extractor
                             (System/getenv "MEMGRAPH_EXTRACTOR_CMD")
                             default-extractor)))
        prompt (extraction-prompt (->transcript content)
                                  (store/-list-predicates s {:status :stable}))
        {:keys [facts rejected]} (prepare-facts (parse-extraction (run prompt)))]
    (if dry-run
      {:status :dry-run :facts facts :rejected rejected}
      (cond-> (core/ingest s {:source-type :session-log
                              :ref (or ref (some-> file str) "session")}
                           facts)
        (seq rejected) (assoc :rejected rejected)))))
