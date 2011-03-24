(ns pallet.phase
  "A phase is a function of a single `request` argument, that contains
   calls to crate functions or actions. A phase has an implicitly
   defined pre and post phase."
  (:require
   [clojure.contrib.condition :as condition]))

(defn pre-phase-name
  "Return the name for the pre-phase for the given `phase`."
  [phase]
  (keyword (str "pre-" (name phase))))

(defn post-phase-name
  "Return the name for the post-phase for the given `phase`."
  [phase]
  (keyword (str "after-" (name phase))))

(defn- all-phases-for-phase
  "Return a sequence including the implicit pre and post phases for a phase."
  [phase]
  [(pre-phase-name phase) phase (post-phase-name phase)])

(defn phase-list-with-implicit-phases
  "Add implicit pre and post phases."
  [phases]
  (mapcat all-phases-for-phase phases))

(defmacro schedule-in-pre-phase
  "Specify that the body should be executed in the pre-phase."
  [request & body]
  `(let [request# ~request
         phase# (:phase request#)]
     (->
      (assoc request# :phase (pre-phase-name phase#))
      ~@body
      (assoc :phase phase#))))

(defmacro schedule-in-post-phase
  "Specify that the body should be executed in the post-phase."
  [request & body]
  `(let [request# ~request
         phase# (:phase request#)]
     (->
      (assoc request# :phase (post-phase-name phase#))
      ~@body
      (assoc :phase phase#))))

(defn check-request-map
  "Function that can check a request map to ensure it is a valid part of
   phase definiton. It returns the request map.

   If this fails, then it is likely that you have an incorrect crate function,
   which is failing to return its request map properly, or you have a non crate
   function in the phase defintion."
  ([request]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and request (map? request))
       (condition/raise
        :type :invalid-request-map
        :message
        "Invalid request map in phase. Check for non crate functions,
      improper crate functions, or problems in threading the request map
      in your phase definition.

      A crate function is a function that takes a request map and other
      arguments, and returns a modified request map. Calls to crate functions
      are often wrapped in a threading macro, -> or pallet.resource/phase,
      to simplify chaining of the request map argument."))
     request)
  ([request form]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and request (map? request))
       (condition/raise
        :type :invalid-request-map
        :message
        (format
         (str
          "Invalid request map in phase request.\n"
          "`request` is %s\n"
          "Problem probably caused in:\n  %s ")
         request form)))
     request))

(defmacro phase-fn
  "Create a phase function from a sequence of crate invocations with
   an ommited request parameter.

   eg. (phase-fn
         (file \"/some-file\")
         (file \"/other-file\"))

   which generates a function with a request argument, that is thread
   through the function calls. The example is thus equivalent to:

   (fn [request] (-> request
                   (file \"/some-file\")
                   (file \"/other-file\"))) "
  [& body]
  `(fn [request#]
     (->
      request#
      (check-request-map "The request passed to the pipeline")
      ~@(mapcat (fn [form] [form `(check-request-map '~form)]) body))))
