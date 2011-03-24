(ns pallet.resource.service
  "Compatability namespace"
  (:require
   pallet.action.service
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib configure-service)

(utils/forward-fns pallet.action.service service init-script)

(defmacro with-restart
  [request service-name & body]
  `(do
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.resource.service/with-restart
       'pallet.action.service/with-restart))
     (pallet.action.service/with-restart ~request ~service-name ~@body)))
