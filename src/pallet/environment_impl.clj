(ns pallet.environment-impl
  "Implementation namespace for the pallet environment.")


(defn get-for
  "Retrieve the environment value at the path specified by keys.
   When no default value is specified, then raise an `:environment-not-found` if
   no environment value is set.

       (get-for {:p {:a {:b 1} {:d 2}}} [:p :a :d])
       ;=> 2"
  ([session keys]
     {:pre [(sequential? keys)]}
     (let [result (get-in (:environment session) keys ::not-set)]
       (when (= ::not-set result)
         (throw
          (ex-info
           (format
            "Could not find keys %s in session :environment"
            (if (sequential? keys) (vec keys) keys))
           {:type :environment-not-found
            :key-not-set keys
            :environment (:environment session)})))
       result))
  ([session keys default]
     (get-in (:environment session) keys default)))
