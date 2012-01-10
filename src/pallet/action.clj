(ns pallet.action
  "Actions implement the conversion of phase functions to script and other
   execution code.

   An action has a :action-type. Known types include :script/bash
   and :fn/clojure.

   An action has a :location, :origin for execution on the node running
   pallet, and :target for the target node.

   An action has an :execution, which is one of :aggregated, :in-sequence or
   :collected. Calls to :aggregated actions will be grouped, and run before
   :in-sequence actions. Calls to :collected actions will be grouped, and run
   after :in-sequence actions."
  {:author "Hugo Duncan"}
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.argument :as argument]
   [pallet.common.def :as ccdef]
   [clojure.tools.logging :as logging]
   [clojure.set :as set]
   [clojure.string :as string])
  (:use
   [pallet.monad :only [phase-pipeline let-s]]
   [pallet.action-plan :only [schedule-action]]))

;;; action defining functions
(def ^{:no-doc true} precedence-key :action-precedence)

(defmacro ^{:indent 1} with-precedence
  "Set up local precedence relations between actions"
  [m & body]
  `(let-s
     [p# (~'get-in [precedence-key])
      _# (~'update-in [precedence-key] merge ~m)
      v# ~@body
      _# (~'assoc-in [precedence-key] p#)]
     v#))

(defn- force-set [x] (if (or (set? x) (nil? x)) x #{x}))

(defn ^{:no-doc true}
  action-metadata
  "Compute action metadata from precedence specification in session"
  [session f]
  (merge-with
   #(set/union
     (force-set %1)
     (force-set %2))
   (:meta f)
   (precedence-key session)))

(defmacro action
  "Define an anonymous action"
  [execution action-type location [session & args] & body]
  (let [meta-map (when (and (map? (first body)) (> (count body) 1))
                   (first body))
        body (if meta-map (rest body) body)]
    `(let [f# (vary-meta
               (fn ~@(when-let [an (:action-name meta-map)]
                       [(symbol (str an "-action-fn"))])
                 [~session ~@args] ~@body) merge ~meta-map)]
       (vary-meta
        (fn [& [~@args :as argv#]]
          (fn [session#]
            (let [action# (action-plan/action-map
                           f#
                           (action-metadata session# f#)
                           argv#
                           ~execution ~action-type ~location)]
              (schedule-action session# action#))))
        merge
        ~meta-map
        {::action-fn f#}))))

(defn action-fn
  "Retrieve the action-fn that is used to execute the specified action."
  [action]
  (::action-fn (meta action)))

;;; Convenience action definers for common cases
(defmacro bash-action
  "Define a remotely executed bash action function."
  [[session & args] & body]
  `(action :in-sequence :script/bash :target [~session ~@args] ~@body))

(defmacro bash-origin-action
  "Define an origin executed bash action function."
  [[session & args] & body]
  `(action :in-sequence :script/bash :origin [~session ~@args] ~@body))

(defmacro clj-action
  "Define a clojure action to be executed on the origin machine. The function
   should return a vector with two elements, the return value and the session."
  [[session & args] & body]
  `(action :in-sequence :fn/clojure :origin [~session ~@args] ~@body))

(defmacro aggregated-action
  "Define a remotely executed aggregated action function, which will
   be executed before :in-sequence actions."
  [[session & args] & body]
  `(action :aggregated :script/bash :target [~session ~@args] ~@body))

(defmacro collected-action
  "Define a remotely executed collected action function, which will
   be executed after :in-sequence actions."
  [[session & args] & body]
  `(action :collected :script/bash :target [~session ~@args] ~@body))

(defmacro as-clj-action
  "An adaptor for using a normal function as a local action function"
  ([f [session & args]]
     `(clj-action
       [~session ~@(map (comp symbol name) args)]
       [(~f ~session ~@(map (comp symbol name) args)) ~session]))
  ([f]
     `(as-clj-action
       ~f [~@(first (:arglists (meta (resolve f))))])))

(defn schedule-clj-fn
  "Schedule a clojure action function. action-fn is a function. It's first
  argument is a session and the remaining arguments are forwarded from args.

  Note that args are possibly evaluated at execution time, which makes this
  different to having action-fn close over it's additional arguments."
  [action-fn & args]
  (let [action (action-plan/action-map
                action-fn {} args :in-sequence :fn/clojure :origin)]
    #(action-plan/schedule-action % action)))

(defmacro def-action-def
  "Define a macro for definining action defining vars"
  [name actionfn1]
  `(defmacro ~name
     {:arglists '(~'[name [session & args] & body]
                  ~'[name [session & args] meta? & body])
      :indent '~'defun}
     [name# ~'& args#]
     (let [[name# args#] (ccdef/name-with-attributes name# args#)
           arglist# (first args#)
           body# (rest args#)
           [meta-map# body#] (if (and (map? (first body#))
                                        (> (count body#) 1))
                               [(merge
                                 {:action-name (name name#)} (first body#))
                                (rest body#)]
                               [{:action-name (name name#)} body#])
           name# (vary-meta
                  name#
                  #(merge
                    {:arglists (list 'quote (list arglist#))}
                    meta-map#
                    %))]
       `(def ~name# (~'~actionfn1 [~@arglist#] ~meta-map# ~@body#)))))

(def-action-def def-bash-action pallet.action/bash-action)
(def-action-def def-bash-origin-action pallet.action/bash-origin-action)
(def-action-def def-clj-action pallet.action/clj-action)
(def-action-def def-aggregated-action pallet.action/aggregated-action)
(def-action-def def-collected-action pallet.action/collected-action)
