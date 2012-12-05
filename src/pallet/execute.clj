(ns pallet.execute
  "Execute actions."
  (:require
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.context :as context]
   [pallet.script :as script]
   [pallet.script-builder :as script-builder]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [pallet.shell :as shell]
   [clojure.tools.logging :as logging])
  (:use
   [clojure.set :only [union]]
   [pallet.core.plan-state :only [update-settings get-settings]]
   [pallet.core.session :only [target-id]]))

(defn normalise-eol
  "Convert eol into platform specific value"
  [#^String s]
  (string/replace s #"[\r\n]+" (str \newline)))

(defn strip-sudo-password
  "Elides the user's password or sudo-password from the given script output."
  [#^String s user]
  (string/replace
   s (format "\"%s\"" (or (:password user) (:sudo-password user))) "XXXXXXX"))

;;; ## Flag Parsing

;;; In order to capture node state, actions emit output that matches a specific
;;; pattern. The executors are responsible for interpreting this text, and
;;; set the flags in the resulting node-value, and on the session under the
;;; target :flags key.

(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setflag-regex #"(?:SETFLAG: )([^:]+)(?: :SETFLAG)")

(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setvalue-regex #"(?:SETVALUE: )([^ ]+) ([^:]+)(?: :SETVALUE)")

(defn set-target-flags
  "Set flags for target."
  [session flags]
  (if (seq flags)
    (update-in
     session [:plan-state]
     update-settings (target-id session) :flags union [flags] {})
    session))

(defn set-target-flag-values
  "Set flag valuess for target."
  [session flag-values]
  (if (seq flag-values)
    (update-in
     session [:plan-state]
     update-settings (target-id session) :flag-values union [flag-values] {})
    session))

(defn set-target-flags
  "Set flags for target."
  [session flags]
  (if (seq flags)
    (update-in
     session [:plan-state]
     update-settings (target-id session) :flags union [flags] {})
    session))

(defn clear-target-flag
  "Clear flag for target."
  [session flag]
  (update-in
   session [:plan-state]
   update-settings (target-id session) :flags disj [flag] {}))

(defn target-flag?
  "Predicate to test if the specified flag is set for target."
  ([session flag]
     (when-let [flags (get-settings
                       (:plan-state session) (target-id session) :flags
                       {:default #{}})]
       (logging/tracef "target-flag? flag %s flags %s" flag flags)
       (flags flag)))
  ([flag]
     (fn [session]
       [(target-flag? session flag) session])))

(defn parse-flags
  "Parse flags from the output stream of an action."
  [output]
  (when output
    (let [flags-set (->>
                     (re-seq setflag-regex output)
                     (map (comp keyword second))
                     set)]
      (logging/tracef "flags-set %s" flags-set)
      flags-set)))

(defn parse-flag-values
  "Parse flags with values from the output stream of an action."
  [output]
  (when output
    (let [flag-values (into {} (map
                                #(vector (keyword (second %)) (nth % 2))
                                (re-seq setvalue-regex output)))]
      (logging/tracef "flag-values %s" flag-values)
      flag-values)))

(defn parse-shell-result
  "Sets the :flags key in a shell result map for any flags set by an action."
  [session {:keys [out] :as result}]
  (let [flags (parse-flags out)
        values (parse-flag-values out)]
    [(assoc result :flags flags :flag-values values)
     (->
      session
      (set-target-flags flags)
      (set-target-flag-values values))]))
