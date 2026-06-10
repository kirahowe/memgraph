(ns memgraph.run-tests
  (:require [clojure.test :as t]
            [memgraph.core-test]
            [memgraph.logic-test]
            [memgraph.session-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'memgraph.logic-test
                                          'memgraph.core-test
                                          'memgraph.session-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
