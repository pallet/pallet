(ns pallet.resource.remote-file
  "File Contents."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [defcomponent]]
        [pallet.resource.file :only [adjust-file]]
        clojure.contrib.logging))

(defn remote-file*
  [path & options]
  (let [opts (if (seq options) (apply hash-map options) {})
        opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :create
      (let [source (opts :source)
            md5 (opts :md5)]
        (cmd-join
         [(cond
           (and source md5)
           (script
            (if-not (&& (file-exists? ~path) (== ~md5 @(md5sum ~path)))
              (wget "-O" ~path ~source)))
           source (script
                   (wget "-O" ~path ~source))
           :else "")
          (adjust-file path opts)])))))

(defcomponent remote-file "File contents management."
  remote-file* [filename & options])
