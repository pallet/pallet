(ns pallet.actions.direct.remote-directory
  "Action to specify the content of a remote directory.  At present the
   content can come from a downloaded tar or zip file."
  (:require
   [clojure.string :as string]
   [pallet.action :refer [action-fn implement-action]]
   [pallet.action-plan :refer [checked-commands]]
   [pallet.actions :refer [directory]]
   [pallet.actions-impl
    :refer [md5-filename
            new-filename
            remote-directory-action
            remote-file-action]]
   [pallet.actions.direct.remote-file :refer [file-uploader]]
   [pallet.core.file-upload :refer [upload-file-path]]
   [pallet.script.lib :as lib :refer [user-default-group]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.stevedore :refer [with-source-line-comments]]))

(require 'pallet.actions.direct.directory)
(require 'pallet.actions.direct.remote-file)

(def ^{:private true}
  directory* (action-fn directory :direct))
(def ^{:private true}
  remote-file* (action-fn remote-file-action :direct))

(defn- source-to-cmd-and-path
  [session path url local-file remote-file md5 md5-url
   install-new-files overwrite-changes upload-path]
  (cond
   url (let [tarpath (str
                      (with-source-line-comments false
                        (stevedore/script (~lib/tmp-dir))) "/"
                      (.getName
                       (java.io.File. (.getFile (java.net.URL. url)))))]
         [(->
           (remote-file* session tarpath
                         {:url url :md5 md5 :md5-url md5-url
                          :install-new-files install-new-files
                          :overwrite-changes overwrite-changes})
           first second)
          tarpath])
   local-file [""
               upload-path
               (md5-filename session (-> session :action :script-dir) path)]
   remote-file ["" remote-file (str remote-file ".md5")]))

(implement-action remote-directory-action :direct
  {:action-type :script :location :target}
  [session path {:keys [action url local-file remote-file
                        unpack tar-options unzip-options jar-options
                        strip-components md5 md5-url owner group recursive
                        install-new-files overwrite-changes extract-files]
                 :or {action :create
                      tar-options "xz"
                      unzip-options "-o"
                      jar-options "xf"
                      strip-components 1
                      recursive true
                      install-new-files true}
                 :as options}]
  [[{:language :bash}
    (case action
      :create (let [uploader (file-uploader options)
                    url (options :url)
                    unpack (options :unpack :tar)
                    upload-path (upload-file-path uploader session path options)
                    options (if (and owner (not group))
                              (assoc options
                                :group (fragment @(user-default-group ~owner)))
                              options)]
                (when (and (or url local-file remote-file) unpack)
                  (let [[cmd tarpath tar-md5] (source-to-cmd-and-path
                                               session path
                                               url local-file remote-file
                                               md5 md5-url
                                               install-new-files
                                               overwrite-changes
                                               upload-path)
                        tar-md5 (str tarpath ".md5")
                        path-md5 (str path "/.pallet.directory.md5")
                        extract-files (string/join \space extract-files)]
                    (checked-commands
                     "remote-directory"
                     (->
                      (directory*
                       session path :owner owner :group group :recursive false)
                      first second)
                     cmd
                     (stevedore/script
                      (when (or (not (file-exists? ~tar-md5))
                                (or (not (file-exists? ~path-md5))
                                    (not ("diff" ~tar-md5 ~path-md5))))
                        ~(condp = unpack
                          :tar (stevedore/checked-script
                                (format "Untar %s" tarpath)
                                (var rdf @(lib/canonical-path ~tarpath))
                                ("cd" ~path)
                                ("tar"
                                 ~tar-options
                                 ~(str "--strip-components=" strip-components)
                                 -f @rdf
                                 ~extract-files)
                                ("cd" -))
                          :unzip (stevedore/checked-script
                                  (format "Unzip %s" tarpath)
                                  (var rdf @(lib/canonical-path ~tarpath))
                                  ("cd" ~path)
                                  ("unzip" ~unzip-options @rdf ~extract-files)
                                  ("cd" -))
                          :jar (stevedore/checked-script
                                (format "Unjar %s" tarpath)
                                (var rdf @(lib/canonical-path ~tarpath))
                                ("cd" ~path)
                                ("jar" ~jar-options @rdf ~extract-files)
                                ("cd" -)))
                        (when (file-exists? ~tar-md5)
                          ("cp" ~tar-md5 ~path-md5))))
                     (if recursive
                       (->
                        (directory*
                         session path
                         :owner owner
                         :group group
                         :recursive recursive)
                        first second)))))))]
   session])
