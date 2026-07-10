(ns memgraph.context
  "compile-context: the write-back half of the ambient loop
  (docs/consuming-auto-memory.md §3). Compiles a deterministic, budgeted
  'what's currently true' view into the marker-delimited managed section of
  the file the harness auto-injects (Claude Code: the head of MEMORY.md) —
  the graph's answer to ambient injection without building injection.

  Deterministic by construction: no LLM anywhere, same graph + same clock =
  byte-identical output. Priority order under the byte budget: standing
  commitments (the do-not-relitigate list), open conflicts awaiting the
  human, recent supersessions (the 'what changed since you last looked'
  briefing nothing else in the field provides), then top currently-valid
  facts by decay-aware effective confidence. Code-derived facts are excluded
  from the fact list: they are regenerable and obvious from the code itself,
  and the AGENTS.md result says ambient context must carry only what the
  code cannot say.

  The echo-loop guard is structural: ingest-notes strips the managed section
  before hashing and extraction, so compile → ingest → compile is a fixed
  point — the graph never re-consumes its own compiled view."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [memgraph.core :as core]
            [memgraph.harness :as harness]
            [memgraph.logic :as logic]
            [memgraph.store :as store]))

(def default-budget
  "Bytes for the whole managed block, markers included. Claude Code injects
  the first ~200 lines / 25 KB of MEMORY.md; staying inside that window is
  the point of the exercise."
  25000)

(def supersession-window-days 30)
(def max-facts 50)

;; ---------------------------------------------------------------------------
;; Pure: rendering
;; ---------------------------------------------------------------------------

(defn- day [d] (subs (str (.toInstant ^java.util.Date d)) 0 10))

(defn- one-line [s] (str/replace (str s) #"\s*\n\s*" " "))

(defn- object-str [f]
  (if (= :entity (:object-kind f))
    (get-in f [:object-ref :name])
    (str "\"" (one-line (:object-lit f)) "\"")))

(defn- subject-str [f] (get-in f [:subject :name]))
(defn- pred-str [f] (name (:predicate f)))

(defn recent-supersessions
  "Pure: facts invalidated by supersession within the window, newest first,
  each paired with its successor when the invalidation reason identifies one."
  [facts now window-days]
  (let [by-id (into {} (map (juxt :id identity)) facts)
        cutoff (- (logic/ms now) (* window-days 86400000))]
    (->> facts
         (keep (fn [f]
                 (when-let [ti (:t-invalid f)]
                   (when (>= (logic/ms ti) cutoff)
                     (when-let [[_ succ-id] (re-matches #"superseded by (\S+)"
                                                        (str (:invalidation-reason f)))]
                       {:old f :new (get by-id succ-id) :at ti})))))
         (sort-by (comp - logic/ms :at))
         vec)))

(defn compiled-sections
  "Pure: the whole store's facts + open conflict pairs + the clock -> the
  priority-ordered sections of the compiled view, ready for the budget fold."
  [{:keys [facts conflicts now]}]
  (let [valid (filterv #(logic/fact-valid-at? % now) facts)
        commitments (->> valid
                         (filter #(= :commitment (:epistemic %)))
                         (sort-by (comp logic/ms :t-valid)))
        top (->> valid
                 (remove #(= :commitment (:epistemic %)))
                 (remove #(= :code (:source-type %)))
                 (map #(assoc % :effective-confidence (logic/effective-confidence % now)))
                 (sort-by (fn [f] [(- (:effective-confidence f)) (subject-str f)]))
                 (take max-facts))
        sups (recent-supersessions facts now supersession-window-days)]
    [{:key :commitments
      :header "Standing decisions (do not relitigate)"
      :lines (mapv (fn [f] (str "- " (subject-str f) " " (pred-str f) " "
                                (object-str f) " (since " (day (:t-valid f)) ")"))
                   commitments)}
     {:key :conflicts
      :header "Open conflicts (awaiting review — `memgraph conflicts`)"
      :lines (mapv (fn [{:keys [fact candidate]}]
                     (str "- " (subject-str fact) " " (pred-str fact) ": "
                          (object-str fact) " vs " (object-str candidate)))
                   conflicts)}
     {:key :supersessions
      :header "Changed recently"
      :lines (mapv (fn [{:keys [old new at]}]
                     (str "- " (subject-str old) " " (pred-str old) ": "
                          (object-str old)
                          (if new (str " → " (object-str new)) " (no longer)")
                          " (" (day at) ")"))
                   sups)}
     {:key :facts
      :header "Current facts (by effective confidence)"
      :lines (mapv (fn [f] (str "- " (subject-str f) " " (pred-str f) " "
                                (object-str f)
                                (format " (%.2f)" (:effective-confidence f))))
                   top)}]))

(defn- byte-len [s] (alength (.getBytes (str s) "UTF-8")))

(defn fit-to-budget
  "Pure fold of sections into one string within budget bytes. Sections are
  priority-ordered: each contributes its header plus as many of its lines as
  fit. A truncated section always announces how much the graph holds beyond
  the cut — lines are dropped to make room for the announcement — and a
  section that cannot fit even its announcement is dropped whole, as are
  empty sections."
  [preamble sections budget]
  (reduce
   (fn [out {:keys [header lines]}]
     (if (empty? lines)
       out
       (let [render (fn [ls extra]
                      (apply str out "\n\n### " header
                             (concat (map #(str "\n" %) ls)
                                     (when extra [(str "\n" extra)]))))
             fits? (fn [s] (<= (byte-len s) budget))
             kept (loop [kept [] ls lines]
                    (if (and (seq ls) (fits? (render (conj kept (first ls)) nil)))
                      (recur (conj kept (first ls)) (rest ls))
                      kept))]
         (if (= (count kept) (count lines))
           (render kept nil)
           (loop [kept kept]
             (let [s (render kept (str "- … " (- (count lines) (count kept))
                                       " more — query the graph"))]
               (cond
                 (fits? s) s
                 (seq kept) (recur (vec (butlast kept)))
                 :else out)))))))
   preamble
   sections))

(defn render-view
  "Pure: sections -> everything compile! writes between the markers, budget
  applied to the whole managed block (markers included)."
  [sections {:keys [now budget]}]
  (let [preamble (str "## Project memory — compiled by memgraph (as of " (day now) ")\n\n"
                      "The knowledge graph's current view. Regenerated on each compile; edits\n"
                      "here are discarded and never re-ingested. History, provenance, and\n"
                      "time-travel: `bin/memgraph help`.")
        marker-overhead (+ (byte-len harness/begin-marker) (byte-len harness/end-marker) 2)]
    (fit-to-budget preamble sections (- (or budget default-budget) marker-overhead))))

(defn compiled-view
  "Pure: the whole store's facts + open conflicts + the clock -> the managed
  section's content."
  [inputs]
  (render-view (compiled-sections inputs) inputs))

;; ---------------------------------------------------------------------------
;; Shell: read the store, splice the file
;; ---------------------------------------------------------------------------

(defn compile!
  "Compile the graph's current view into the managed section of the file the
  harness auto-injects. Creates the notes dir and inject file when absent;
  replaces only the marker-delimited block when present — the harness's own
  notes around it are untouched.

  opts: :harness (default claude-code) :project (default cwd)
        :dir (override the resolved notes dir) :budget (bytes, default 25000)
        :dry-run (return the block, write nothing) :now (injectable clock)"
  [s {:keys [harness project dir budget dry-run now]}]
  (let [h (harness/resolve-harness harness)
        notes-dir (or dir
                      ((:notes-dir h) (System/getProperty "user.home")
                                      (str (fs/canonicalize (or project ".")))))
        target (str (fs/path notes-dir (:inject-file h)))
        now (or now (core/now))
        inputs {:facts (store/-all-facts s)
                :conflicts (:conflicts (core/conflicts s))
                :now now
                :budget budget}
        sections (compiled-sections inputs)
        inner (render-view sections inputs)
        result {:harness (name (:id h))
                :file target
                :bytes (+ (byte-len inner)
                          (byte-len harness/begin-marker)
                          (byte-len harness/end-marker) 2)
                :sections (into {} (map (juxt :key (comp count :lines))) sections)}]
    (if dry-run
      (assoc result :status :dry-run :content inner)
      (do (fs/create-dirs notes-dir)
          (spit target (harness/splice-managed-section
                        (when (fs/exists? target) (slurp target))
                        inner))
          (assoc result :status :compiled)))))
