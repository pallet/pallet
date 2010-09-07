(ns pallet.crate.etc-default
  "Generation and installation of /etc/default-style files."
 (:require
   [pallet.stevedore :as script]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script]
   [clojure.contrib.string :as string]))

(def default-dir "/etc/default")

(defn- quoted
  [#^String s]
  (str \" (.replace s "\"" "\\\"") \"))

(defn write
  "Writes a KEY=value file to /etc/default/~{filename}, moving the original
   file to /etc/default/~{filename}.orig if it has not yet been moved.
   Note that all values are quoted, and quotes in values are escaped, but
   otherwise, values are written literally.

   e.g. (write \"tomcat6\"
          :JAVA_OPTS \"-Xmx1024m\"
          \"JSP_COMPILER\" \"javac\")"
  [request filename & key-value-pairs]
  (let [file (str default-dir "/" filename)
        original (str default-dir "/" filename ".orig")]
    (-> request
        (exec-script/exec-script
         (if (not (file-exists? ~original))
           (cp ~file ~original)))
        (remote-file/remote-file
         file
         :owner "root:root"
         :mode 644
         :content (string/join
                   \newline
                   (for [[k v] (partition 2 key-value-pairs)
                         :let [k (if (string? k) k (name k))]]
                     (str k "=" (quoted v))))))))
