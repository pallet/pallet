(ns pallet.resource
  "Namespace for backward compatibility"
  (:require
   [pallet.action :as action]
   [pallet.common.deprecate :as deprecate]
   [pallet.phase :as phase]
   [pallet.common.def :as ccdef]))

(defmacro phase [& body]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename 'pallet.resource/phase 'pallet.phase/phase-fn))
     (phase/phase-fn ~@body)))

(defmacro execute-pre-phase
  [& body]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource/execute-pre-phase 'pallet.phase/schedule-in-pre-phase))
     (phase/schedule-in-pre-phase ~@body)))

(defmacro execute-after-phase [& body]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource/execute-after-phase
       'pallet.phase/schedule-in-post-phase))
     (phase/schedule-in-post-phase ~@body)))

(defmacro defresource [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (deprecate/deprecated-macro
        ~&form
        (deprecate/rename
         'pallet.resource/defresource 'pallet.action/def-bash-action))
       (action/def-bash-action
         ~n [~@arguments] ~@forms))))

(defmacro deflocal [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (deprecate/deprecated-macro
        ~&form
        (deprecate/rename
         'pallet.resource/deflocal 'pallet.action/def-clj-action))
       (action/def-clj-action
         ~n [~@arguments] ~@forms))))

(defmacro defaggregate [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (deprecate/deprecated-macro
        ~&form
        (deprecate/rename
         'pallet.resource/defaggregate 'pallet.action/def-aggregated-action))
       (action/def-aggregated-action
         ~n [~@arguments] ~@forms))))

(defmacro defcollect [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (deprecate/deprecated-macro
        ~&form
        (deprecate/rename
         'pallet.resource/defcollect 'pallet.action/def-collected-action))
       (action/def-collected-action
         ~n [~@arguments] ~@forms))))

(action/def-clj-action as-local-resource
  [session f & args]
  (apply f session args))
