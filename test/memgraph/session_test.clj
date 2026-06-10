(ns memgraph.session-test
  "Session extractor: the pure parts (transcript normalization, response
  parsing, clamping) as plain functions, and the shell end-to-end with an
  injected :extractor-fn — no LLM, no subprocess."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [memgraph.core :as core]
            [memgraph.ingest.session :as session]
            [memgraph.store.memory :as mem]))

(deftest transcript-normalization
  (testing "plain text passes through untouched"
    (is (= "hello\nworld" (session/->transcript "hello\nworld"))))
  (testing "claude-code session JSONL flattens to role: text turns"
    (let [jsonl (str/join "\n"
                          ["{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"use kebab-case everywhere\"}}"
                           "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Done.\"},{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}"
                           "{\"type\":\"summary\",\"summary\":\"irrelevant\"}"])]
      (is (= "user: use kebab-case everywhere\n\nassistant: Done."
             (session/->transcript jsonl)))))
  (testing "text that merely contains some JSON stays raw"
    (let [mixed "we discussed {\"a\":1}\nand moved on"]
      (is (= mixed (session/->transcript mixed))))))

(deftest response-parsing-is-tolerant
  (let [response (str/join "\n"
                           ["Here are the extracted facts:"
                            "```jsonl"
                            "{\"subject\":\"api\",\"predicate\":\"prefers\",\"object\":\"REST\",\"class\":\"preference\",\"confidence\":0.95}"
                            "```"
                            "not json at all"
                            "{\"subject\":\"\",\"predicate\":\"prefers\",\"object\":\"x\"}"])
        {:keys [facts rejected]} (session/prepare-facts (session/parse-extraction response))]
    (is (= 1 (count facts)))
    (is (= 1 (count rejected)) "incomplete triples are rejected, not ingested")
    (let [f (first facts)]
      (is (= 0.7 (:confidence f)) "session confidence is capped at 0.7")
      (is (= :session-log (:source-type f))))))

(deftest missing-confidence-gets-default
  (let [{:keys [facts]} (session/prepare-facts
                         [{:subject "a" :predicate "prefers" :object "b"}])]
    (is (= 0.6 (:confidence (first facts))))))

(deftest prompt-carries-the-vocabulary
  (let [prompt (session/extraction-prompt "user: hi"
                                          [{:id :core/prefers :definition "Subject prefers X."}])]
    (is (str/includes? prompt "core/prefers — Subject prefers X."))
    (is (str/includes? prompt "<transcript>\nuser: hi\n</transcript>"))))

(deftest extract-end-to-end-with-injected-extractor
  (let [s (mem/create)
        _ (core/seed! s)
        response (str/join "\n"
                           ["{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\",\"confidence\":0.95}"
                            "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\"}"])
        prompts (atom [])
        run! (fn [opts] (session/extract! s (merge {:transcript "user: we settled on Result types and rejected GraphQL"
                                                    :ref "sess-1"
                                                    :extractor-fn (fn [p] (swap! prompts conj p) response)}
                                                   opts)))]
    (testing "dry-run extracts but writes nothing"
      (let [r (run! {:dry-run true})]
        (is (= :dry-run (:status r)))
        (is (= 2 (count (:facts r))))
        (is (zero? (get-in (core/stats s) [:facts :total])))))
    (testing "real run ingests under a session-log episode"
      (let [r (run! {})]
        (is (= 2 (:total r)))
        (is (= 2 (get-in r [:counts :created])))
        (let [{:keys [facts]} (core/get-facts s {:entity "api-layer"})]
          (is (= :core/decided-against (:predicate (first facts)))
              "snake_case predicate from the LLM resolves to :core/*")
          (is (= :commitment (:epistemic (first facts)))
              ":class flows through to the epistemic class")
          (is (= :session-log (:source-type (first facts)))))
        (let [{:keys [facts]} (core/get-facts s {:entity "AuthService"})]
          (is (= 0.7 (:confidence (first facts))) "clamp survives ingestion"))))
    (testing "the prompt the extractor saw includes vocabulary and transcript"
      (is (str/includes? (first @prompts) "core/decided-against"))
      (is (str/includes? (first @prompts) "rejected GraphQL")))))
