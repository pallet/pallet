(ns pallet.actions.direct.remote-directory
  "Action to specify the content of a remote directory.  At present the
   content can come from a downloaded tar or zip file."
  (:require
   pallet.actions.direct.directory
   pallet.actions.direct.remote-file
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.java.io :as io])
  (:use
   [clojure.algo.monads :only [m-when]]
   [pallet.action :only [action-fn implement-action]]
   [pallet.action-plan :only [checked-commands]]
   [pallet.actions :only [directory remote-directory]]
   [pallet.actions-impl :only [remote-directory-action remote-file-action]]
   [pallet.monad :only [let-s phase-pipeline]]
   [pallet.utils :only [apply-map]]))

(def ^{:private true}
  directory* (action-fn directory :direct))
(def ^{:private true}
  remote-file* (action-fn remote-file-action :direct))

(defn- source-to-cmd-and-path
  [session path url local-file remote-file md5 md5-url
   install-new-files overwrite-changes]
  (cond
   url (let [tarpath (str
                      (stevedore/script (~lib/tmp-dir)) "/"
                      (.getName
                       (java.io.File. (.getFile (java.net.URL. url)))))]
         [(->
           (remote-file* session tarpath
                         {:url url :md5 md5 :md5-url md5-url
                          :install-new-files install-new-files
                          :overwrite-changes overwrite-changes})
           first second)
          tarpath])
   local-file ["" (str path "-content")]
   remote-file ["" remote-file]))

(implement-action remote-directory-action :direct
  {:action-type :script :location :target}
  [session path {:keys [action url local-file remote-file
                        unpack tar-options unzip-options jar-options
                        strip-components md5 md5-url owner group recursive
                        install-new-files overwrite-changes]
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
      :create (let [url (options :url)
                    unpack (options :unpack :tar)]
                (when (and (or url local-file remote-file) unpack)
                  (let [[cmd tarpath] (source-to-cmd-and-path
                                       session path
                                       url local-file remote-file md5 md5-url
                                       install-new-files overwrite-changes)]
                    (checked-commands
                     "remote-directory"
                     (->
                      (directory*
                       session path :owner owner :group group :recursive false)
                      first second)
                     cmd
                     (condp = unpack
                       :tar (stevedore/checked-script
                             (format "Untar %s" tarpath)
                             (var rdf @(readlink -f ~tarpath))
                             (cd ~path)
                             (tar ~tar-options
                                  ~(str "--strip-components=" strip-components)
                                  -f @rdf)
                             (cd -))
                       :unzip (stevedore/checked-script
                               (format "Unzip %s" tarpath)
                               (var rdf @(readlink -f ~tarpath))
                               (cd ~path)
                               (unzip ~unzip-options @rdf)
                               (cd -))
                       :jar (stevedore/checked-script
                             (format "Unjar %s" tarpath)
                             (var rdf @(readlink -f ~tarpath))
                             (cd ~path)
                             (jar ~jar-options @rdf)
                             (cd -)))
                     (if recursive
                       (->
                        (directory*
                         session path
                         :owner owner
                         :group group
                         :recursive recursive)
                        first second)))))))]
   session])
