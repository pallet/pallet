(ns pallet.phase
  "A phase is a function of a single `session` argument, that contains
   calls to crate functions or actions. A phase has an implicitly
   defined pre and post phase."
  (:use [pallet.thread-expr :only (-->)]
        [clojure.contrib.def :only (name-with-attributes)])
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
   phase definition. It returns the session map.

   On failure, the function will print the phase through which the
   session passed prior to crashing. It is like that this phase
   introduced the fault into the session; to aid in debugging, make
   sure to use `phase-fn` and `def-phase-fn` to build phases."
  [session form]
  ;; we do not use a precondition in order to improve the error message
  (when-not (and session (map? session))
    (condition/raise
     :type :invalid-session
     :message
     (str
      "Invalid session map in phase.\n"
      (format "session is %s\n" session)
      (format "Problem probably caused by subphase:\n  %s\n" form)
      "Check for non crate functions, improper crate functions, or
      problems in threading the session map in your phase
      definition. A crate function is a function that takes a session
      map and other arguments, and returns a modified session
      map. Calls to crate functions are often wrapped in a threading
      macro, -> or pallet.phase/phase-fn, to simplify chaining of the
      session map argument.")))
  session)
(defthreadfn mapped-thread-fn
  (fn [arg sub-expr]
    (if (map? arg)
      arg
      (condition/raise
       :type :invalid-session
       :message
       (format "Only maps are allowed, and %s is most definitely not a map."
               sub-expr)))))

(defmacro defthreadfn
  "Binds a var to a particular class of anonymous functions that
  accept a vector of arguments and a number of subexpressions. When
  calling the produced functions, the caller must supply a threading
  parameter as the first argument. This parameter will be threaded
  through the resulting forms; the extra parameters made available in
  the argument vector will be available to all subexpressions.

  In addition to the argument vector, `defthreadfn` accepts any number
  of watchdog functions, each of which are inserted into the thread
  after each subexpression invocation. These watchdog functions are
  required to accept 2 arguments -- the threaded argument, and a
  string representation of the previous subexpression. For example,

    (defthreadfn mapped-thread-fn
      (fn [arg sub-expr]
        (if (map? arg)
          arg
          (condition/raise
           :type :invalid-session
           :message
           (format \"Only maps are allowed, and %s is most definitely
                   not a map.\" sub-expr)))))

  Returns a `thread-fn` that makes sure the threaded argument remains
  a map, on every step of its journey.

  For a more specific example if `defthreadfn`, see
  `pallet.phase/phase-fn`."
  [macro-name & rest]
  (let [[macro-name checkers] (name-with-attributes macro-name rest)]
    `(defmacro ~macro-name
       ([argvec#] (~macro-name argvec# identity))
       ([argvec# subphase# & left#]
          `(fn [~'session# ~@argvec#]
             (--> ~'session#
                  ~subphase#
                  ~@(for [func# '~checkers]
                      `(~func# (str '~subphase#)))
                  ~@(when left#
                      [`((~'~macro-name ~argvec# ~@left#) ~@argvec#)])))))))

(defthreadfn phase-fn
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
  
   with a number of verifications on the session map performed after
   each phase invocation."
  check-session)

(defthreadfn unchecked-phase-fn
  "Unchecked version of `phase-fn`.

   The following two forms are equivalent:

   (unchecked-phase-fn [x y]
       (+ x)
       (+ y))

   (fn [session x y]
       (-> session
           (+ x)
           (+ y)))")