(ns pallet.context
  "Provides contexts for exceptions, logging, etc"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.context :as context]))

(defn kw-context-entries
  "Return the :kw context entries for a context"
  ([context]
     (mapcat
      (fn kw-context-for-key [key]
        (map :kw (get context key)))
      (::key-stack context)))
  ([] (kw-context-entries context/*current-context*)))

(defn phase-contexts
  "Returns all phase contexts"
  []
  (when (bound? #'pallet.common.context/*current-context*)
    (context/scope-formatted-context-entries :pallet/phase)))

(defn possibly-formatted-msg
  [msg]
  (if (vector? msg)
    `(apply format ~msg)
    msg))

(defmacro with-context
  "Specifies a context for pallet implementation code."
  [context-kw context-msg & body]
  `(context/with-context
     {:kw ~context-kw :msg ~(possibly-formatted-msg context-msg)}
     {:scope :pallet/pallet
      :on-enter (context/context-history {})
      :format :msg}
     ~@body))

(defmacro with-phase-context
  "Specifies a context inside a phase function"
  [context-kw context-msg & body]
  `(context/with-context
     {:kw ~context-kw :msg ~(possibly-formatted-msg context-msg)}
     {:scope :pallet/phase
      :on-enter (context/context-history {})
      :format :msg}
     ~@body))

(defmacro phase-context
  "Specifies a context in a threaded phase function"
  [session context-kw context-msg & body]
  `(let [session# ~session]
     (with-phase-context ~context-kw ~context-msg
       (-> session# ~@body))))

(defmacro infof
  [fmt & fmtargs]
  `(logging/infof
    (str (string/join ", " (context/formatted-context-entries)) ": " ~fmt)
    ~@fmtargs))

(defmacro warnf
  [fmt & fmtargs]
  `(logging/warnf
    (str (string/join ", " (context/formatted-context-entries)) ": " ~fmt)
    ~@fmtargs))

(defmacro debugf
  [fmt & fmtargs]
  `(logging/debugf
    (str (string/join ", " (context/formatted-context-entries)) ": " ~fmt)
    ~@fmtargs))

(defmacro tracef
  [fmt & fmtargs]
  `(logging/tracef
    (str (string/join ", " (context/formatted-context-entries)) ": " ~fmt)
    ~@fmtargs))
