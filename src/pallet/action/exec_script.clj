(ns pallet.action.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:require
   [clojure.string :as string]
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.context :as context]
   [pallet.stevedore :as stevedore]))

(def exec-script* (action/bash-action [session script] script))

(defmacro checked-script
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  `(stevedore/checked-script
    (if-let [context# (seq (context/phase-contexts))]
      (str (string/join ": " context#) ": " ~name)
      ~name)
    ~@script))

(defmacro exec-script
  "Execute a bash script remotely"
  [session & script]
  `(exec-script* ~session (stevedore/script ~@script)))

(defmacro exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails."
  [session name & script]
  `(exec-script* ~session (checked-script ~name ~@script)))
