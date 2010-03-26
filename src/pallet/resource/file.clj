(ns pallet.resource.file
  "File manipulation."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [defresource]]
        clojure.contrib.logging))

(defscript rm [file & options])
(defimpl rm :default [file & options]
  ("rm" ~(map-to-arg-string (first options)) ~file))

(defscript chown [owner file & options])
(defimpl chown :default [owner file & options]
  ("chown" ~(map-to-arg-string (first options)) ~owner ~file))

(defscript chgrp [group file & options])
(defimpl chgrp :default [group file & options]
  ("chgrp" ~(map-to-arg-string (first options)) ~group ~file))

(defscript chmod [mode file & options])
(defimpl chmod :default [mode file & options]
  ("chmod" ~(map-to-arg-string (first options)) ~mode ~file))

(defscript touch [file & options])
(defimpl touch :default [file & options]
  ("touch" ~(map-to-arg-string (first options)) ~file))

(defscript sed-file [file expr replacement & options])

(defimpl sed-file :default [file expr replacement & options]
  (sed "-i" ~(str "/" expr "/" replacement "/") ~file))

(defn adjust-file [path opts]
  (cmd-join
   (filter
    (complement nil?)
    [ (script
       (touch ~path ~(select-keys opts [:force])))
      (when (opts :owner)
        (script (chown ~(opts :owner) ~path)))
      (when (opts :group)
        (script (chgrp ~(opts :group) ~path)))
      (when (opts :mode)
        (script (chmod ~(opts :mode) ~path)))])))

(defn file*
  [path & options]
  (let [opts (if (seq options) (apply hash-map options) {})
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :delete
      (script (rm ~path ~(select-keys opts [:force])))
      :create
      (adjust-file path opts)
      :touch
      (adjust-file path opts))))

(def file-args (atom []))

(defn- apply-files [file-args]
  (cmd-join (map #(apply file* %) file-args)))


(defresource file "File management."
  file-args apply-files [filename & options])
