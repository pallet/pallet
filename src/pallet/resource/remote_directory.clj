(ns pallet.resource.remote-directory
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]))

(defn remote-directory*
  [path & options]
  (let [options (apply hash-map options)]
    (condp = (get options :action :create)
      (let [url (options :url)
            unpack (options :unpack)]
        (when (and url unpack)
          (let [filename (.getName (java.io.File. (.getFile (java.net.URL. url))))
                tarpath (str (stevedore/script (tmp-dir)) "/" filename)]
            (stevedore/checked-commands
             "remote-directory"
             (apply directory/directory*
                    path (apply concat (select-keys options [:owner :group])))
             (remote-file/remote-file*
              tarpath :url url :md5 (options :md5) :md5-url (:md5-url options))
             (condp = unpack
               :tar (stevedore/script
                     (cd ~path)
                     (tar ~(get options :tar-options "xz")
                         ~(str "--strip-components="
                               (get options :strip-components 1))
                         -f ~tarpath)))
             (if (:recursive options)
               (apply
                directory/directory*
                path (apply concat
                            (select-keys options [:owner :group :recursive])))))))))))

(resource/defresource remote-directory
  "Specify the contents of remote directory"
  remote-directory* [path & options])
