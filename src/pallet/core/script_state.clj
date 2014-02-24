(ns pallet.core.script-state
  "## Script Flag and State Parsing

In order to capture node state, actions emit output that matches a specific
pattern. The executors are responsible for interpreting this text, and
set the flags in the resulting node-value, and on the session under the
target :flags key."
  (:require
   [clojure.core.typed
    :refer [ann doseq> fn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq NonEmptySeqable Set]]
   [clojure.tools.logging :as logging]
   [pallet.core.types :refer []]
   [pallet.node :as node]))


(ann setflag-regex java.util.regex.Pattern)
(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setflag-regex #"(?:SETFLAG: )([^:]+)(?: :SETFLAG)")

(ann setvalue-regex java.util.regex.Pattern)
(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setvalue-regex #"(?:SETVALUE: )([^ ]+) ([^:]+)(?: :SETVALUE)")

(ann merge-node-state
  [FlagValues Node (Map Keyword String) -> FlagValues])
(defn ^:internal merge-node-state
  "Set flag values for target."
  [state node new-flag-values]
  (update-in state [(node/id node)] merge new-flag-values))

(ann parse-flags [(Nilable String) -> (Nilable Set)])
(defn ^:internal parse-flags
  "Parse flags from the output stream of an action.
  Returns a map of state values."
  [output]
  (when output
    (let [flags (->>
                 (re-seq setflag-regex output)
                 (map (comp keyword second)))]
      (logging/tracef "flags %s" flags)
      (zipmap flags (repeat true)))))

(ann parse-flag-values [String -> (Map Keyword String)])
(defn ^:internal parse-flag-values
  "Parse flags with values from the output stream of an action.
  Returns a map of state values."
  [output]
  (when output
    (let [flag-values (into {} (map
                                (fn> [s :- (NonEmptySeqable String)]
                                  (vector (keyword (second s)) (nth s 2)))
                                (re-seq setvalue-regex output)))]
      (logging/tracef "flag-values %s" flag-values)
      flag-values)))

(ann ^:no-check parse-node-state [String -> Session])
(defn parse-node-state
  "Parse any state flags out of the shell script output."
  [out]
  (merge (parse-flags out)
         (parse-flag-values out)))

(defn update-node-state
  "Update the state map for node with flag values parsed from out."
  [state node out]
  (merge-node-state state node (parse-node-state out)))

(ann node-state (Fn [Session Keyword -> boolean]
                    [Keyword -> [Session -> (Vector* boolean Session)]]))
(defn get-node-state
  "Return the node state value for key."
  [state node key]
  (get-in state [(node/id node) key]))

(defn node-state
  "Return the node state."
  [state node]
  (get state (node/id node)))
