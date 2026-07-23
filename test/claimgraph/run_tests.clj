(ns claimgraph.run-tests
  (:require [clojure.test :as t]
            [claimgraph.adr-test]
            [claimgraph.audit-test]
            [claimgraph.bench-test]
            [claimgraph.coach-test]
            [claimgraph.code-ingest-test]
            [claimgraph.config-test]
            [claimgraph.consolidate-test]
            [claimgraph.context-test]
            [claimgraph.core-test]
            [claimgraph.evidence-test]
            [claimgraph.failure-test]
            [claimgraph.hooks-test]
            [claimgraph.judge-test]
            [claimgraph.lease-test]
            [claimgraph.load-test]
            [claimgraph.mcp-test]
            [claimgraph.logic-test]
            [claimgraph.notes-test]
            [claimgraph.oplog-test]
            [claimgraph.outcome-test]
            [claimgraph.retrieval-test]
            [claimgraph.session-test]
            [claimgraph.setup-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'claimgraph.logic-test
                                          'claimgraph.config-test
                                          'claimgraph.setup-test
                                          'claimgraph.core-test
                                          'claimgraph.load-test
                                          'claimgraph.evidence-test
                                          'claimgraph.failure-test
                                          'claimgraph.adr-test
                                          'claimgraph.code-ingest-test
                                          'claimgraph.session-test
                                          'claimgraph.notes-test
                                          'claimgraph.audit-test
                                          'claimgraph.retrieval-test
                                          'claimgraph.coach-test
                                          'claimgraph.outcome-test
                                          'claimgraph.lease-test
                                          'claimgraph.mcp-test
                                          'claimgraph.oplog-test
                                          'claimgraph.context-test
                                          'claimgraph.hooks-test
                                          'claimgraph.judge-test
                                          'claimgraph.consolidate-test
                                          'claimgraph.bench-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
