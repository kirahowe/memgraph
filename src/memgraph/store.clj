(ns memgraph.store
  "The storage abstraction: the boundary between storage-agnostic core
  operations and a concrete storage engine. Implementations speak plain
  Clojure maps in the wire shapes documented below; all temporal/conflict/
  validation semantics live in memgraph.core and memgraph.logic, NOT here.
  Store methods are raw primitives.

  Wire shapes:

  entity    {:id :name :type :scope :aliases [str]}
  fact      {:id :subject <entity> :predicate kw :object-kind :entity|:literal
             :object-ref <entity>|nil :object-lit str|nil
             :t-valid Date :t-invalid Date|nil :recorded-at Date
             :confidence double :epistemic kw :scope str :source-type kw
             :episode str|nil :conflicts [fact-id] :invalidation-reason str|nil}
  episode   {:id :source-type :ref :summary :opened-at :closed-at}
  predicate {:id kw :label :category kw :object-kind kw :cardinality kw
             :inverse-of kw :status kw :replaced-by kw :definition
             :maps-to :default-epistemic kw}")

(defprotocol Store
  (-ensure-entity [s ent]
    "Exact name+scope match or create. ent = {:name :type :scope}. Returns entity.")
  (-get-entity [s name scope]
    "Entity by exact name+scope, or nil.")
  (-find-entities [s name scope]
    "Candidate entities for resolution: name or alias matches exactly, or
    normalizes (logic/normalize-entity-name) to the same form as the input.
    Over-returning is fine — precedence and ambiguity are decided purely in
    logic/pick-entity-match.")
  (-update-entity [s entity-id updates]
    "Apply {:name str, :type kw, :add-aliases [str]} to an entity. Stores
    maintain any derived lookup fields (normalized names, indexes).")
  (-repoint-facts [s from-entity-id to-entity-id]
    "Re-reference every fact whose subject or object is from-entity onto
    to-entity (the merge primitive). Returns the number of facts touched.")
  (-delete-entity [s entity-id]
    "Remove an entity row (the merged-away husk; its names live on as
    aliases of the survivor). Facts are never deleted.")
  (-list-entities [s opts]
    "All entities. opts {:type :scope} as exact filters.")
  (-insert-fact [s fact]
    "Raw insert of a complete fact map. No conflict logic. Returns the fact.")
  (-get-facts [s entity-id opts]
    "Raw facts touching an entity. opts {:direction :out|:in|:both, :predicate kw}.
    Includes invalidated facts; validity filtering happens in core.")
  (-get-history [s entity-id predicate]
    "All facts (valid + invalidated) for (subject, predicate).")
  (-invalidate [s fact-id at reason]
    "Close the validity interval: set :t-invalid and :invalidation-reason.")
  (-link-conflicts [s fact-id conflict-ids]
    "Record conflict links from fact-id to each id in conflict-ids.")
  (-unlink-conflicts [s fact-id conflict-ids]
    "Remove conflict links from fact-id to each id in conflict-ids.")
  (-update-confidence [s fact-id confidence])
  (-all-facts [s])
  (-open-episode [s ep]
    "ep = {:id :source-type :ref :opened-at}. Returns episode.")
  (-close-episode [s episode-id summary at])
  (-get-episode [s episode-id])
  (-list-episodes [s])
  (-get-predicate [s pred-id])
  (-list-predicates [s opts]
    "opts {:category :status} as exact filters.")
  (-register-predicate [s pred]
    "Insert or update a predicate registry row. Returns the predicate.")
  (-search [s query opts]
    "Full-text (or best-effort substring) search across entity names, literal
    objects and episode summaries. Returns {:entities [] :facts [] :episodes []}.")
  (-stats [s])
  (-close [s]))
