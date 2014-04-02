(ns pallet.core.context
  "Pallet context functions"
  (:require
   [com.palletops.log-config.timbre :as log-config]
   [potemkin]))

(potemkin/import-vars log-config/with-context log-config/context)

(defmacro with-request-context
  "Ensure that there is a request id in the context"
  [& body]
  `(log-config/with-total-context
     (update-in (context) [:request] #(or % (gensym "req")))
     ~@body))
