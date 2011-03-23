(ns pallet.resource
  "Namespace for backward compatibility"
  (:require
   [pallet.action :as action]
   [pallet.phase :as phase]
   [pallet.utils :as utils]
   [clojure.contrib.def :as ccdef]))

(defmacro phase [& body]
  `(do
     (utils/deprecated ~&form phase "is deprecated, use" phase/phase-fn)
     (phase/phase-fn ~@body)))

(defmacro execute-pre-phase
  [& body]
  `(do
     (utils/deprecated
      ~&form execute-pre-phase "is deprecated, use" phase/execute-in-pre-phase)
     (phase/execute-in-pre-phase ~@body)))

(defmacro execute-after-phase [& body]
  `(do
     (utils/deprecated
      ~&form
      execute-after-phase "is deprecated, use" phase/execute-in-post-phase)
     (phase/execute-in-pre-phase ~@body)))


(defmacro defresource [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (utils/deprecated
        ~&form defresource "is deprecated, use" action/def-bash-action)
       (action/def-bash-action
         ~n [~@arguments] ~@forms))))

(defmacro deflocal [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (utils/deprecated
        ~&form deflocal "is deprecated, use" action/def-clj-action)
       (action/def-clj-action
         ~n [~@arguments] ~@forms))))

(defmacro defaggregate [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (utils/deprecated
        ~&form defaggregate "is deprecated, use" action/def-aggregated-action)
       (action/def-aggregated-action
         ~n [~@arguments] ~@forms))))

(defmacro defcollect [n & body]
  (let [[n args] (ccdef/name-with-attributes n body)
        n (vary-meta n dissoc :use-arglist :copy-arglist)
        [[n* arguments & forms]] args]
    `(do
       (utils/deprecated
        ~&form defcollect "is deprecated, use" action/def-collected-action)
       (action/def-collected-action
         ~n [~@arguments] ~@forms))))
