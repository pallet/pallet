(ns pallet.repl
  "A namespace that can be used to pull in most of pallet's namespaces.  uesful
  when working at the clojure REPL."
  (:require [pallet.core.data-api :as da]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:use [clojure.pprint :only [print-table]]
        [clojure.pprint :only [pprint with-pprint-dispatch code-dispatch
                               print-table pprint-indent]]
        [clojure.walk :only [prewalk]]
        [pallet.stevedore :only (with-source-line-comments)]
        ;; the equivalent to `use-pallet`
        [pallet.utils :exclude [make-user]]
        pallet.api
        pallet.actions
        clj-ssh.ssh))

(defmacro use-pallet
  "Macro that will use pallet's namespaces, to provide an easy to access REPL."
  []
  '(do
     (clojure.core/use
      '[pallet.utils :exclude [make-user]]
      'pallet.api
      'pallet.actions
      'clj-ssh.ssh)))

(defn show-nodes*
  "Prints the list of nodes in `nodes` as a table. The columns of the
  table and their order can be modified by passing a vector with all
  the keys in `keys`"
  [nodes & [keys]]
  (if keys
    (print-table keys nodes)
    (print-table nodes)))

(defn show-nodes
  "Prints a table with the information on all the nodes in a compute provider.
  The columns displayed can be modified by passing an optional `keys`
  vector containing the keys to display as columns (order is
  significative)"
  [compute & [rows]]
  (let [nodes (da/nodes compute)]
    (show-nodes* nodes rows)))

(defn show-group
  "Prints a table with the information on all the nodes belonging to a
  `group` (identified by it's name) on a particular `compute`
  provider. The columns displayed can be modified by passing an
  optional `keys` vector containing the keys to display as
  columns (order is significative)"
  [compute group-name & rows]
  (let [nodes (da/nodes compute)
        group-filter (fn [n] (= group-name (:group-name n)))
        group-nodes (filter group-filter nodes)]
    (show-nodes* group-nodes rows)))

(defn- prefix-text [prefix text]
  (when (and (seq text)
             (not (and (= 1 (count text))
                       (= "" (first text)))))
    (doseq [line (string/split-lines text)]
      (println (str prefix line)))))

(defn- indent-string [steps]
  (apply str (repeat steps " ")))

(defmacro with-indent [steps & body]
  `(prefix-text (indent-string ~steps)
                (with-out-str ~@body)))

(defn- explain-action [{:keys [location action-type script language form
                              script-dir script-prefix sudo-user blocks]
                       :as action}
                      level & [ print-scripts print-forms]]
  (println "ACTION:" (-> action :action :action-symbol)
           "of type" (name action-type)
           "executed on" (name location))
  (with-indent
   2
   (when (or script-dir script-prefix sudo-user)
     (format "OPTIONS: sudo-user=%s, script-dir=%s, and script-prefix=%s\n"
             sudo-user script-dir (name script-prefix)))
   (when print-forms
     (println "FORM:")
     (with-indent 2 (with-pprint-dispatch code-dispatch
                 (pprint form))))
   (when print-scripts
     (println "SCRIPT:")
     (with-indent 2 (println (second script))))))

(defn- explain-if-action [action level]
  (println "IF" (first (-> action :action :args)) "THEN:"))

(defn- explain-actions [actions & {:keys [level print-scripts print-forms]}]
  (let [level (or level 0)]
    (with-indent
     2
     (doseq [{:keys [location action-type script language form
                     script-dir script-prefix sudo-user blocks] :as action}
             actions
             :when action]
       (if (= action-type :flow/if)
         (do (explain-if-action action level)
             (when blocks
               (when (first blocks)
                 (explain-actions (first blocks) :level (inc level)
                                  :print-scripts print-scripts
                                  :print-forms print-forms))
               (when-not (seq? (second blocks))
                 (println "ELSE:")
                 (explain-actions (second blocks) :level (inc level)
                                  :print-scripts print-scripts
                                  :print-forms print-forms))))
         (do
           (explain-action action level print-scripts print-forms)))))))

(defn explain-plan
  "Prints the action plan and corresponding shell scripts built as a
result of executing a plan function `pfn`. If the plan function
requires settings to be set, adding the settings function call to
`:settings-phase`

  By default, the plan function is run against a mock node with
this configuration:

    [\"mock-node\" \"mock-group\" \"0.0.0.0\" :ubuntu]

and you can override this by passing your own node vector as `:node`,
or just change the os-family for the ndoe by passing `:os-family.

By default, both the generated action forms (clojure forms) and the
scripts corresponding to those action forms will be shown, but you can
disable them by passing `:print-scripts false` and/or `:print-forms
false` "
  [pfn & {:keys [settings-phase print-scripts print-forms
                 node os-family]
          :or {print-scripts true
               print-forms false}}]
  (let [actions (da/explain-plan pfn
                                 :settings-phase settings-phase
                                 :os-family os-family
                                 :node node)]
    (explain-actions actions
                     :print-scripts print-scripts
                     :print-forms print-forms)))