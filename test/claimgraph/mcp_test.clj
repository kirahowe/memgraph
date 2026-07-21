(ns claimgraph.mcp-test
  "The MCP front-end's pure handler: JSON-RPC in, response maps out — no
  stdio, in-memory store, no pod."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.mcp :as mcp]
            [claimgraph.store.memory :as mem]))

(defn- setup []
  (let [s (doto (mem/create) (core/seed!))
        db (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-mcp-test"}) "db"))]
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    [s db]))

(defn- tool-result [resp]
  (json/parse-string (get-in resp [:result :content 0 :text]) true))

(deftest lifecycle-and-tools
  (let [[s db] (setup)]
    (testing "initialize"
      (let [r (mcp/handle s db {:id 1 :method "initialize" :params {}})]
        (is (= "claimgraph" (get-in r [:result :serverInfo :name])))
        (is (get-in r [:result :capabilities :tools]))))

    (testing "notifications get no reply"
      (is (nil? (mcp/handle s db {:method "notifications/initialized"}))))

    (testing "tools/list advertises the surface"
      (let [r (mcp/handle s db {:id 2 :method "tools/list" :params {}})]
        (is (= #{"memory_facts" "memory_search" "memory_recall" "memory_history"
                 "memory_conflicts" "memory_coach" "memory_assert"}
               (set (map :name (get-in r [:result :tools])))))))

    (testing "a read tool answers from the graph"
      (let [r (mcp/handle s db {:id 3 :method "tools/call"
                                :params {:name "memory_facts"
                                         :arguments {:entity "api-layer"}}})
            body (tool-result r)]
        (is (false? (get-in r [:result :isError])))
        (is (= "GraphQL" (get-in body [:facts 0 :object-lit])))))

    (testing "the write tool goes through the full machinery under the lease"
      (let [r (mcp/handle s db {:id 4 :method "tools/call"
                                :params {:name "memory_assert"
                                         :arguments {:subject "AuthService"
                                                     :predicate "prefers"
                                                     :object "argon2"
                                                     :class "preference"}}})]
        (is (= "created" (:status (tool-result r))))
        (is (not (fs/exists? (str db ".lock"))) "lease released")))

    (testing "the coach gates over MCP too"
      (let [r (mcp/handle s db {:id 5 :method "tools/call"
                                :params {:name "memory_coach"
                                         :arguments {:task "adopt graphql in the api-layer"}}})]
        (is (true? (:push (tool-result r))))))

    (testing "tool errors come back as isError content, not protocol failures"
      (let [r (mcp/handle s db {:id 6 :method "tools/call"
                                :params {:name "memory_facts"
                                         :arguments {:entity "no-such-entity"}}})]
        (is (true? (get-in r [:result :isError])))
        (is (= "entity-not-found" (:type (tool-result r))))))

    (testing "unknown methods are JSON-RPC errors"
      (is (= -32601 (get-in (mcp/handle s db {:id 7 :method "bogus/thing"})
                            [:error :code]))))))
