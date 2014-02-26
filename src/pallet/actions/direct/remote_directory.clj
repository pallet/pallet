(ns pallet.actions.direct.remote-directory
  "Action to specify the content of a remote directory.  At present the
   content can come from a downloaded tar or zip file."
  (:require
   [clojure.string :as string]
   [pallet.action :refer [implement-action]]
   [pallet.actions.decl
    :refer [checked-commands remote-directory-action remote-file-action]]
   [pallet.actions.direct.remote-file :refer [file-uploader remote-file*]]
   [pallet.core.file-upload :refer [upload-file-path user-file-path]]
   [pallet.script.lib :as lib :refer [user-default-group]]
   [pallet.stevedore :as stevedore
    :refer [fragment with-source-line-comments]]))

(require 'pallet.actions.direct.directory)
(require 'pallet.actions.direct.remote-file)


(defn- source-to-cmd-and-path
  [action-state uploader path url local-file remote-file md5 md5-url
   install-new-files overwrite-changes]
  (cond
   url (let [tarpath (user-file-path uploader path (:options action-state))]
         [(->
           (remote-file*
            action-state
            tarpath {:url url :md5 md5 :md5-url md5-url
                     :install-new-files install-new-files
                     :overwrite-changes overwrite-changes})
           second)
          tarpath])
   local-file (let [new-path (upload-file-path
                              uploader path (:options action-state))]
                ["" new-path (str new-path ".md5")])
   remote-file ["" remote-file (str remote-file ".md5")]))

(defn remote-directory*
  [action-state
   path {:keys [action url local-file remote-file
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
  (case action
    :create (let [action-options (:options action-state)
                  uploader (file-uploader action-options)
                  url (options :url)
                  unpack (options :unpack :tar)
                  options (if (and owner (not group))
                            (assoc options
                              :group (fragment @(user-default-group ~owner)))
                            options)]
              (when (and (or url local-file remote-file) unpack)
                (let [[cmd tarpath tar-md5]
                      (source-to-cmd-and-path
                       action-state
                       uploader
                       path
                       url local-file remote-file
                       md5 md5-url
                       install-new-files
                       overwrite-changes)
                      tar-md5 (str tarpath ".md5")
                      path-md5 (str path "/.pallet.directory.md5")
                      extract-files (string/join \space extract-files)]
                  (checked-commands
                   "remote-directory"
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
                        ("cp" ~tar-md5 ~path-md5))))))))))

(implement-action remote-directory-action :direct {} {:language :bash}
                  remote-directory*)
