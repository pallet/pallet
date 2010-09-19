(ns pallet.crate.etc-default
  "Generation and installation of /etc/default-style files."
 (:require
   [pallet.stevedore :as stevedore]
   [pallet.resource.file :as file]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script]
   [clojure.string :as string]))

(defn write
  "Writes a KEY=value file to /etc/default/~{filename}, moving the original
   file to /etc/default/~{filename}.orig if it has not yet been moved.
   Note that all values are quoted, and quotes in values are escaped, but
   otherwise, values are written literally.

   e.g. (write \"tomcat6\"
          :JAVA_OPTS \"-Xmx1024m\"
          \"JSP_COMPILER\" \"javac\")"
  [request filename & key-value-pairs]
  (let [default-dir (stevedore/script (etc-default))
        file (str default-dir "/" filename)
        original (str default-dir "/" filename ".orig")]
    (-> request
        (exec-script/exec-script
         (if-not (file-exists? ~original)
           (cp ~file ~original)))
        (remote-file/remote-file
         file
         :owner "root:root"
         :mode 644
         :content (string/join
                   \newline
                   (for [[k v] (partition 2 key-value-pairs)]
                     (str (name k) "=" (pr-str v))))))))
