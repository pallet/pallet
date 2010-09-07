(ns pallet.script
  "Base infrastructure for script generation"
  (:use clojure.contrib.logging)
  (:require
   [clojure.contrib.def :as def]))

;; map from script name to implementations
;; where implementations is a map from keywords to function
(defonce *scripts* {})

(def *script-line* nil)
(def *script-file* nil)

(def/defunbound *template*
  "Determine the target to generate script for.")

(defmacro with-template
  "Specify the target for script generation"
  [template & body]
  `(binding [*template* ~template]
     ~@body))

(defmacro with-line-number
  "Record the source line number"
  [& body]
  `(do ;(defvar- ln# nil)
       ;(binding [*script-line* (:line (meta (var ln#)))
       ; *script-file* (:file (meta (var ln#)))]
         (ns-unmap *ns* 'ln#)
         ~@body));)


(defn print-args [args]
  (str "(" (apply str (interpose " " args)) ")"))

(defn- match-fn [fn-key]
  (some #(if (set? fn-key) (fn-key %) (= fn-key %)) *template*))

(defn- matches?
  "Return the keys that match the template, or nil if any of the keys are not in
  the template."
  [keys]
  (every? match-fn keys))

(defn- more-explicit? [current candidate]
  (or (= current :default)
      (> (count candidate) (count current))))

(defn- better-match? [current candidate]
  (if (and (matches? (first candidate))
           (more-explicit? (first current) (first candidate)))
    candidate
    current))

(defn best-match [script]
  (trace (str "Looking up script " script))
  (when-let [impls (*scripts* script)]
    (trace "Found implementations")
    (second (reduce better-match?
                    [:default (impls :default)]
                    (dissoc impls :default)))
;;     (impls (first (keys impls)))
    )) ;; TODO fix this

(defn dispatch-target
  "Invoke target, raising if there is no implementation."
  [script & args]
  (trace (str "dispatch-target " script " " (print-args ~@args)))
  (let [f (best-match script)]
    (if f
      (apply f args)
      (throw (str "No implementation for " (name script))))))

(defn invoke-target
  "Invoke target when possible, otherwise return nil"
  [script args]
  (trace (str "invoke-target [" *script-file* ":" *script-line* "] "
              script " " (print-args args)))
  (when-let [f (best-match (keyword (name script)))]
    (trace (str "Found implementation for " script " - " f
                " invoking with " (print-args args) " empty? " (empty? args)))
    (if (empty? args)
      (f)
      (apply f args))))

;; TODO - ensure that metadata is correctly placed on the generated function
(defmacro defscript
  "Define a script fragment"
  [name [& args]]
  (let [fwd-args (filter #(not (= '& %)) args)]
    `(defn ~name [~@args]
       ~(if (seq fwd-args)
          `(apply dispatch-target (keyword (name ~name)) ~@fwd-args)
          `(dispatch-target (keyword (name ~name)))))))

(defn add-to-scripts [scripts script-name specialisers f]
  (assoc scripts script-name
         (assoc (get *scripts* script-name {})
           specialisers f)))
