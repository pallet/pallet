(ns pallet.resource.remote-file
  "File Contents."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [defcomponent]]
        [pallet.resource.file :only [adjust-file heredoc]]
        clojure.contrib.logging))

(defn remote-file*
  [path & options]
  (let [opts (merge {:action :create} (apply hash-map options))]
    (condp = (opts :action)
      :create
      (let [source (opts :source)
            content (opts :content)
            md5 (opts :md5)]
        (cmd-join
         [(cond
           (and source md5)
           (script
            (if-not (&& (file-exists? ~path)
                        (== ~md5 @(md5sum ~path "|" cut "-f1 -d' '")))
              (wget "-O" ~path ~source))
            (echo "MD5 sum is" @(md5sum ~path)))
           source (script
                   (wget "-O" ~path ~source)
                   (echo "MD5 sum is" @(md5sum ~path)))
           content (apply heredoc
                          path content
                          (apply concat (seq (select-keys opts [:literal]))))
           :else "")
          (adjust-file path opts)])))))

(defcomponent remote-file "File contents management."
  remote-file* [filename & options])
