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

;;; ## Flag Parsing

;;; In order to capture node state, actions emit output that matches a specific
;;; pattern. The executors are responsible for interpreting this text, and
;;; set the flags in the resulting node-value, and on the session under the
;;; target :flags key.

(ann setflag-regex java.util.regex.Pattern)
(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setflag-regex #"(?:SETFLAG: )([^:]+)(?: :SETFLAG)")

(ann setvalue-regex java.util.regex.Pattern)
(def ^{:doc "Regex used to match SETFLAG text in action output."
       :private true}
  setvalue-regex #"(?:SETVALUE: )([^ ]+) ([^:]+)(?: :SETVALUE)")

(ann set-target-flags [Session Set -> Session])
;; TODO remove tc-gnore when core.typed can handle update-in and (seq Set))
(tc-ignore
 (defn set-target-flags
   "Set flags for target."
   [session flags]
   (if (seq flags)
     (update-in
      session [:plan-state]
      update-settings (target/id (target session)) :flags union [flags] {})
     session)))

(ann set-target-flag-values [Session (Map Keyword String) -> Session])
;; TODO remove tc-ignore when core.typed can handle update-in
(tc-ignore
 (defn set-target-flag-values
   "Set flag values for target."
   [session flag-values]
   (if (seq flag-values)
     (update-in
      session [:plan-state]
      update-settings (target/id (target session))
      :flag-values union [flag-values] {})
     session)))

(ann clear-target-flag [Session Keyword -> Session])
;; TODO remove tc-ignore when core.typed can handle update-in
(tc-ignore
 (defn clear-target-flag
   "Clear flag for target."
   [session flag]
   (update-in
    session [:plan-state]
    update-settings (target/id (target session)) :flags disj [flag] {})))

(ann target-flag? (Fn [Session Keyword -> boolean]
                      [Keyword -> [Session -> (Vector* boolean Session)]]))
;; TODO - remove the tc-ignore when core.typed no longer raises an assertion
(tc-ignore
 (defn target-flag?
   "Predicate to test if the specified flag is set for target."
   ([session flag]
      (when-let [flags (get-settings
                        (get session :plan-state)
                        (target/id (target session))
                        :flags
                        {:default #{}})]
        (logging/tracef "target-flag? flag %s flags %s" flag flags)
        (flags flag)))
   ([flag]
      (fn [session]
        [(target-flag? session flag) session]))))

(ann parse-flags [(Nilable String) -> (Nilable Set)])
;; TODO - remove the tc-ignore when core.typed can handle comp
(tc-ignore
 (defn parse-flags
   "Parse flags from the output stream of an action."
   [output]
   (when output
     (let [flags-set (->>
                      (re-seq setflag-regex output)
                      (map (comp keyword second))
                      set)]
       (logging/tracef "flags-set %s" flags-set)
       flags-set))))

(ann parse-flag-values [String -> (Map Keyword String)])
;; TODO remove tc-ignore when core.typed handles map better
(tc-ignore
 (defn parse-flag-values
   "Parse flags with values from the output stream of an action."
   [output]
   (when output
     (let [flag-values (into {} (map
                                 (fn> [s :- (NonEmptySeqable String)]
                                      (vector (keyword (second s)) (nth s 2)))
                                 (re-seq setvalue-regex output)))]
       (logging/tracef "flag-values %s" flag-values)
       flag-values))))

(ann ^:no-check parse-shell-result [Session ActionResult -> Session])
;; TODO remove tc-ignore when we have updated maybe-assoc
(tc-ignore
 (defn parse-shell-result
   "Sets the :flags key in a shell result map for any flags set by an action."
   [session {:keys [out] :as result}]
   (let [flags (parse-flags out)
         values (parse-flag-values out)]
     [(-> result
          (maybe-assoc :flags flags)
          (maybe-assoc :flag-values values))
      (->
       session
       (set-target-flags flags)
       (set-target-flag-values values))])))
