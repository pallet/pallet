(ns pallet.task.to-blob
  "Upload to a blob."
  (:require
   [pallet.blobstore :as blobstore]))

(defn war-file-name
  [project]
  (format "%s-%s.war" (:name project) (:version project)))

(defn find-war
  [project]
  (some
   #(let [f (% project)] (and (.exists (java.io.File. f)) f))
   [war-file-name]))

(defn to-blob
  "Upload to a blob.
    to-blob container path filename
   By default tries to upload the project war file."
  [request & args]
  (let [[container path & files] (map name args)
        file (or (first files) (find-war (:project request)))
        options (-> request :project :pallet)]
    (if file
      (do
        (println "Uploading" file)
        (blobstore/put-file (:blobstore request) container path file))
      (println "Nothing to upload"))))
