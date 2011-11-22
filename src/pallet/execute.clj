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
   [clojure.tools.logging :as logging]))

(defn normalise-eol
  "Convert eol into platform specific value"
  [#^String s]
  (string/replace s #"[\r\n]+" (str \newline)))

(defn strip-sudo-password
  "Elides the user's password or sudo-password from the given script output."
  [#^String s user]
  (string/replace
   s (format "\"%s\"" (or (:password user) (:sudo-password user))) "XXXXXXX"))
