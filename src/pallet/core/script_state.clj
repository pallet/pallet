(ns pallet.core.script-state
  "## Script Flag and State Parsing

In order to capture node state, actions emit output that matches a specific
pattern. The executors are responsible for interpreting this text, and
set the flags in the resulting node-value, and on the session under the
target :flags key."
  (:require
   [taoensso.timbre :as logging]))


(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setflag-regex #"(?:SETFLAG: )([^:]+)(?: :SETFLAG)")

(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setvalue-regex #"(?:SETVALUE: )([^ ]+) ([^:]+)(?: :SETVALUE)")

(defn ^:internal merge-node-state
  "Set flag values for target."
  [state node-id new-flag-values]
  (update-in state [node-id] merge new-flag-values))

(defn ^:internal parse-flags
  "Parse flags from the output stream of an action.
  Returns a map of state values."
  [output]
  (when output
    (let [flags (->>
                 (re-seq setflag-regex output)
                 (map (comp keyword second)))]
      (logging/tracef "flags %s" (pr-str flags))
      (zipmap flags (repeat true)))))

(defn ^:internal parse-flag-values
  "Parse flags with values from the output stream of an action.
  Returns a map of state values."
  [output]
  (when output
    (let [flag-values (into {} (map
                                (fn [s]
                                  (vector (keyword (second s)) (nth s 2)))
                                (re-seq setvalue-regex output)))]
      (logging/tracef "flag-values %s" (pr-str flag-values))
      flag-values)))

(defn parse-node-state
  "Parse any state flags out of the shell script output."
  [out]
  (merge (parse-flags out)
         (parse-flag-values out)))

(defn update-node-state
  "Update the state map for node with flag values parsed from out."
  [state node-id out]
  (merge-node-state state node-id (parse-node-state out)))

(defn get-node-state
  "Return the node state value for key."
  [state node-id key]
  (get-in state [node-id key]))

(defn node-state
  "Return the node state."
  [state node-id]
  (get state node-id))
