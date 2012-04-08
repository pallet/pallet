(ns pallet.session.verify
  (:use
   [pallet.common.context :only [throw-map]]))

(def session-verification-key :pallet.phase/session-verification)

(defn add-session-verification-key
  [session]
  (assoc session session-verification-key true))

(defn check-session
  "Function that can check a session map to ensure it is a valid part of
   phase definiton. It returns the session map.

   If this fails, then it is likely that you have an incorrect crate function
   which is failing to return its session map properly, or you have a non crate
   function in the phase defintion."
  ([session]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and session (map? session) (session-verification-key session))
       (throw-map
        "Invalid session map in phase. Check for non crate functions
      improper crate functions, or problems in threading the session map
      in your phase definition.

      A crate function is a function that takes a session map and other
      arguments, and returns a modified session map. Calls to crate functions
      are often wrapped in a threading macro, -> or pallet.phase/phase-fn
      to simplify chaining of the session map argument."
        {:type :invalid-session
         :session session}))
     session)
  ([session form]
     ;; we do not use a precondition in order to improve the error message
     (when-not (and session (map? session) (session-verification-key session))
       (throw-map
        (format
         (str
          "Invalid session map in phase session.\n"
          "`session` is %s\n"
          "Problem probably caused in:\n  %s ")
         session form)
        {:type :invalid-session
         :session session}))
     session))
