(ns pallet.resource.service
  "Compatability namespace"
  (:require
   pallet.action.service
   [pallet.common.deprecate :as deprecate]
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib configure-service)

(deprecate/forward-fns pallet.action.service service init-script)

(defmacro with-restart
  [session service-name & body]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource.service/with-restart
       'pallet.action.service/with-restart))
     (pallet.action.service/with-restart ~session ~service-name ~@body)))
