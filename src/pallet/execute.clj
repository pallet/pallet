(ns pallet.execute
  "Execute actions."
  (:require
   [clojure.core.typed
    :refer [ann doseq> fn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq NonEmptySeqable Set]]
   [clojure.set :refer [union]]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.core.plan-state :refer [get-settings update-settings]]
   [pallet.core.types :refer [ActionResult Keyword Session TargetMap User]]
   [pallet.session :refer [target]]
   [pallet.target :as target]
   [pallet.utils :refer [maybe-assoc]]))

(ann normalise-eol [String -> String])
(defn normalise-eol
  "Convert eol into platform specific value"
  [#^String s]
  (string/replace s #"[\r\n]+" (str \newline)))

(ann strip-sudo-password [String User -> String])
(defn strip-sudo-password
  "Elides the user's password or sudo-password from the given script output."
  [#^String s user]
  (string/replace
   s (format "\"%s\"" (or (:password user) (:sudo-password user))) "XXXXXXX"))

(ann clean-logs [User -> [String -> String]])
(defn clean-logs
  "Clean passwords from logs"
  [user]
  (comp (fn> [s :- String] (strip-sudo-password s user)) normalise-eol))

(ann status-line? [String -> boolean])
(defn status-line? [^String line]
  (.startsWith line "#> "))

(ann status-lines
     [(NilableNonEmptySeq String) -> (Nilable (NonEmptySeqable String))])
(defn status-lines
  "Return script status lines from the given sequence of lines."
  [lines]
  (seq (filter status-line? lines)))

(ann log-script-output [TargetMap User -> [String -> nil]])
;; TODO remove tc-ignore when core.typed can handle comp
(tc-ignore
 (defn log-script-output
   "Return a function to log (multi-line) script output, removing passwords."
   [server user]
   ;; TODO remove inst
   (comp
    (fn> [s :- (NonEmptySeqable String)]
         (doseq> [^String l :- String s]
                 (cond
                  (not (status-line? l)) (logging/debugf "%s   <== %s" server l)
                  (.endsWith l "FAIL") (logging/errorf "%s %s" server l)
                  :else (logging/infof "%s %s" server l))))
    string/split-lines
    (clean-logs user))
   nil))


;; TODO remove :no-check when core.typed can handle merge
(ann ^:no-check script-error-map
     [String String ActionResult
      -> (HMap :mandatory {:message String
                           :type Keyword
                           :server String}
               :optional {:err String
                          :out String
                          :exit AnyInteger})])
(defn script-error-map
  "Create an error map for a script execution"
  [server msg result]
  (merge
   (select-keys result [:server :err :out :exit])
   {:message (format
              "%s %s%s%s"
              server
              msg
              (let [out (when-let [o (get result :out)]
                          (string/join
                           ", "
                           (status-lines (string/split-lines o))))]
                (if (string/blank? out) "" (str " :out " out)))
              (if-let [err (:err result)] (str " :err " err) ""))
    :type :pallet-script-excution-error
    :server server}))

(ann result-with-error-map [String String ActionResult -> ActionResult])
;; TODO - remove tc-ignore when typing mojo has recovered
(tc-ignore
 (defn result-with-error-map
   "Verify the return code of a script execution, and add an error map if
   there is a non-zero result :exit"
   [server msg {:keys [exit] :as result}]
   (if (zero? exit)
     result
     (assoc result :error (script-error-map server msg result)))))
