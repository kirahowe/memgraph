(ns memgraph.run-tests
  (:require [clojure.test :as t]
            [memgraph.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'memgraph.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
