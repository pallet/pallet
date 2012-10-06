(ns pallet.context
  "Provides contexts for exceptions, logging, etc"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.context :as context]
   [pallet.common.logging.logutils :as logutils]
   [pallet.event :as event]))

(defn kw-context-entries
  "Return the :kw context entries for a context"
  ([context]
     (mapcat
      (fn kw-context-for-key [key]
        (map :kw (get context key)))
      (::key-stack context)))
  ([] (kw-context-entries context/*current-context*)))

(defn possibly-formatted-msg
  [msg]
  (if (vector? msg)
    `(apply format ~msg)
    msg))

(defmacro with-context
  "Specifies a context for pallet implementation code."
  {:indent 1}
  [context & body]
  (let [line (-> &form meta :line)]
    `(let [c# (merge {:ns ~(list 'quote (ns-name *ns*)) :line ~line} ~context)]
       (context/with-context
         c#
         {:scope :pallet/pallet
          :on-enter (context/context-history {})
          :format :msg}
         (event/publish c#)
         (logutils/with-context
           [~@(mapcat identity (dissoc context :kw :msg))]
           ~@body)))))

(defn contexts
  "Returns all pallet contexts"
  []
  (when (bound? #'pallet.common.context/*current-context*)
    (context/scope-formatted-context-entries :pallet/pallet)))

;;; Phase contexts
(defn phase-contexts
  "Returns all phase contexts"
  []
  (when (bound? #'pallet.common.context/*current-context*)
    (seq (context/scope-formatted-context-entries :pallet/phase))))

(defmacro with-phase-context
  "Specifies a context inside a phase function"
  [context & body]
  (let [line (-> &form meta :line)]
    `(let [c# (merge {:ns ~(list 'quote (ns-name *ns*)) :line ~line} ~context)]
       (context/with-context
         c#
         {:scope :pallet/phase
          :on-enter (context/context-history {})
          :format :msg}
         (event/publish c#)
         ~@body))))

(defmacro phase-context
  "Specifies a context in a threaded phase function"
  [session context-kw context-msg & body]
  `(let [session# ~session]
     (with-phase-context ~context-kw ~context-msg
       (-> session# ~@body))))

(defn phase-context-scope
  "Returns all phase contexts"
  []
  (when (bound? #'pallet.common.context/*current-context*)
    (context/scope-context-entries :pallet/phase)))

(defmacro in-phase-context-scope
  {:indent 1}
  [context & body]
  `(if (bound? #'pallet.common.context/*current-context*)
     (context/in-context
       ~context
       {:scope :pallet/phase
        :on-enter (context/context-history {})
        :format :msg}
       ~@body)
     (do ~@body)))

;;; exceptions
(defmacro invalid-argument
  [message problem-message value]
  `(let [m# ~message
         p# ~problem-message
         v# ~value]
     (context/throw-map
      (str m#  ": " v# " " p#)
      {:type :pallet/invalid-argument
       :argument v#
       :problem p#})))

;;; logging
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
