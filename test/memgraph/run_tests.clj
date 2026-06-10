(ns memgraph.run-tests
  (:require [clojure.test :as t]
            [memgraph.code-ingest-test]
            [memgraph.consolidate-test]
            [memgraph.core-test]
            [memgraph.judge-test]
            [memgraph.logic-test]
            [memgraph.session-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'memgraph.logic-test
                                          'memgraph.core-test
                                          'memgraph.code-ingest-test
                                          'memgraph.session-test
                                          'memgraph.judge-test
                                          'memgraph.consolidate-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
