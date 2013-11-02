;; (ns pallet.phase
;;   "A phase is a function of a single `session` argument, that contains
;;    calls to crate functions or actions. A phase has an implicitly
;;    defined pre and post phase."
;;   (:require
;;    [pallet.core.session :refer [session session! session-context]]
;;    [pallet.session.verify :refer [check-session]]))

;; (defn pre-phase-name
;;   "Return the name for the pre-phase for the given `phase`."
;;   [phase]
;;   (keyword "pallet.phase" (str "pre-" (name phase))))

;; (defn post-phase-name
;;   "Return the name for the post-phase for the given `phase`."
;;   [phase]
;;   (keyword "pallet.phase" (str "post-" (name phase))))

;; (defn all-phases-for-phase
;;   "Return a sequence including the implicit pre and post phases for a phase."
;;   [phase]
;;   [(pre-phase-name phase) phase (post-phase-name phase)])

;; (defn subphase-for
;;   "Return the phase this is a subphase for, or nil if not a subphase"
;;   [phase]
;;   (when (= (namespace phase) "pallet.phase")
;;     (let [n (name phase)
;;           [_ pre] (re-matches #"pre-(.*)" n)
;;           [_ post] (re-matches #"post-(.*)" n)
;;           p (or pre post)]
;;       (when p
;;         (keyword p)))))

;; (defmacro ^{:requires [#'session-context session! session]}
;;   schedule-in-pre-phase
;;   "Specify that the body should be executed in the pre-phase."
;;   [& body]
;;   `(session-context
;;     schedule-in-pre-phase {}
;;     (let [phase# (get (session) :phase)]
;;       (session! (assoc (session) :phase (pre-phase-name phase#)))
;;       ~@body
;;       (session! (assoc (session) :phase phase#)))))

;; (defmacro schedule-in-post-phase
;;   "Specify that the body should be executed in the post-phase."
;;   [& body]
;;   `(session-context
;;     schedule-in-post-phase {}
;;     (let [phase# (get (session) :phase)]
;;       (session! (assoc (session) :phase (post-phase-name phase#)))
;;       ~@body
;;       (session! (assoc (session) :phase phase#)))))

;; (defmacro ^{:requires [check-session]}
;;   check-session-thread
;;   "Add session checking to a sequence of calls which thread a session
;;    map. e.g.

;;        (->
;;          session
;;          (check-session-thread
;;            (file \"/some-file\")
;;            (file \"/other-file\")))

;;    The example is thus equivalent to:

;;        (-> session
;;          (check-session \"The session passed to the pipeline\")
;;          (check-session (file \"/some-file\"))
;;          (check-session (file \"/other-file\")))"
;;   [arg & body]
;;   `(->
;;     ~arg
;;     (check-session "The session passed to the pipeline")
;;     ~@(mapcat (fn [form] [form `(check-session '~form)]) body)))
