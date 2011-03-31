(ns pallet.phase
  "A phase is a function of a single `session` argument, that contains
   calls to crate functions or actions. A phase has an implicitly
   defined pre and post phase."
  (:use [pallet.thread-expr :only (-->)])
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

(defn all-phases-for-phase
  "Return a sequence including the implicit pre and post phases for a phase."
  [phase]
  [(pre-phase-name phase) phase (post-phase-name phase)])

;; (defn phase-list-with-implicit-phases
;;   "Add implicit pre and post phases."
;;   [phases]
;;   (mapcat all-phases-for-phase phases))

(defmacro schedule-in-pre-phase
  "Specify that the body should be executed in the pre-phase."
  [session & body]
  `(let [session# ~session
         phase# (:phase session#)]
     (->
      (assoc session# :phase (pre-phase-name phase#))
      ~@body
      (assoc :phase phase#))))

(defmacro schedule-in-post-phase
  "Specify that the body should be executed in the post-phase."
  [session & body]
  `(let [session# ~session
         phase# (:phase session#)]
     (->
      (assoc session# :phase (post-phase-name phase#))
      ~@body
      (assoc :phase phase#))))

(defn check-session
  "Function that can check a session map to ensure it is a valid part of
   phase definiton. It returns the session map.

   If this fails, then it is likely that you have an incorrect crate function,
   which is failing to return its session map properly, or you have a non crate
   function in the phase defintion."
  ([session]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and session (map? session))
       (condition/raise
        :type :invalid-session
        :message
        "Invalid session map in phase. Check for non crate functions,
      improper crate functions, or problems in threading the session map
      in your phase definition.

      A crate function is a function that takes a session map and other
      arguments, and returns a modified session map. Calls to crate functions
      are often wrapped in a threading macro, -> or pallet.phase/phase-fn,
      to simplify chaining of the session map argument."))
     session)
  ([session form]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and session (map? session))
       (condition/raise
        :type :invalid-session
        :message
        (format
         (str
          "Invalid session map in phase session.\n"
          "`session` is %s\n"
          "Problem probably caused in:\n  %s ")
         session form)))
     session))

(defmacro phase-fn
  "Composes a phase function from a sequence of phases by threading an
 implicit phase session parameter through each. Each phase will have
 access to the parameters passed in through `phase-fn`'s argument
 vector. thus,

    (phase-fn [filename]
         (file filename)
         (file \"/other-file\"))

   is equivalent to:

   (fn [session filename]
     (-> session
         (file filename)
         (file \"/other-file\")))
  
   with an added safety call to `check-session` prior to each phase
   invocation."
  ([argvec] `(phase-fn ~argvec identity))
  ([argvec subphase & left]
     `(fn [session# ~@argvec]
        (--> session#
            ~subphase
            (check-session (str "The session passed out of" '~subphase))
            ~@(when left
                [`((phase-fn ~argvec ~@left) ~@argvec)])))))