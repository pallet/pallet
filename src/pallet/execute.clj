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
   [pallet.parameter :only [update-for-target get-for-target]]))

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

(defn set-target-flags
  "Set flags for target."
  [session flags]
  (update-for-target session [:flags] union flags))

(defn clear-target-flag
  "Clear flag for target."
  [session flag]
  (update-for-target session [:flags] disj flag))

(defn target-flag?
  "Predicate to test if the specified flag is set for target."
  [session flag]
  ((get-for-target session [:flags] #{}) flag))

(defn parse-flags
  "Parse flags from the output stream of an action."
  [output]
  (when output
    (let [flags-set (map (comp keyword second) (re-seq setflag-regex output))]
      (logging/debugf "flags-set %s" (vec flags-set))
      (when (seq flags-set)
        (set flags-set)))))

(defn parse-shell-result
  "Sets the :flags key in a shell result map for any flags set by an action."
  [session {:keys [out] :as result}]
  (if-let [flags (parse-flags out)]
    (let [flags (set flags)]
      [(set-target-flags session flags)
       (assoc result :flags flags)])
    [session result]))
