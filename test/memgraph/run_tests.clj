(ns memgraph.run-tests
  (:require [clojure.test :as t]
            [memgraph.bench-test]
            [memgraph.code-ingest-test]
            [memgraph.consolidate-test]
            [memgraph.context-test]
            [memgraph.core-test]
            [memgraph.judge-test]
            [memgraph.logic-test]
            [memgraph.notes-test]
            [memgraph.session-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'memgraph.logic-test
                                          'memgraph.core-test
                                          'memgraph.code-ingest-test
                                          'memgraph.session-test
                                          'memgraph.notes-test
                                          'memgraph.context-test
                                          'memgraph.judge-test
                                          'memgraph.consolidate-test
                                          'memgraph.bench-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
