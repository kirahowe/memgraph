(ns claimgraph.evidence
  "The raw-evidence tier (TierMem's argument, review §3.1): extraction
  decides what to keep before knowing what a future query will hinge on, so
  the raw input is kept too — as immutable, content-addressed artifacts next
  to the store, pointed to by the episode they were extracted under.
  Provenance upgrades from \"which session\" to \"the exact bytes\", and
  nothing an extractor drops is unrecoverable.

  Artifacts are write-once by construction: the name IS the sha-256 of the
  content, so re-ingesting identical content is a no-op and nothing can be
  edited in place. The tier is a local fallback (notes-as-primary,
  transcripts-as-fallback); artifacts do not ride the dump — the graph
  remains the portable artifact, evidence the local audit trail."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn default-dir
  "Evidence lives next to the store: <db>.evidence/"
  [db-path]
  (str db-path ".evidence"))

(defn content-hash
  "Full sha-256 hex of the content — the artifact's identity."
  [content]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str content) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn write!
  "Store content as an immutable artifact; returns its hash. Idempotent:
  identical content is already there under the same name."
  [dir content]
  (let [hash (content-hash content)
        path (fs/path dir hash)]
    (when-not (fs/exists? path)
      (fs/create-dirs dir)
      (spit (str path) (str content)))
    hash))

(defn fetch
  "The raw bytes for a hash, or nil when the artifact isn't present on this
  machine (evidence is local; the graph survives without it)."
  [dir hash]
  (let [path (fs/path (str dir) (str hash))]
    (when (and (not (str/blank? (str hash))) (fs/exists? path))
      (slurp (str path)))))
