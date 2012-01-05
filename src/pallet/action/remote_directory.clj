(ns pallet.action.remote-directory
  "Action to specify the content of a remote directory.  At present the
   content can come from a downloaded tar or zip file."
  (:require
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.action.directory :as directory]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.java.io :as io])
  (:use
   [clojure.algo.monads :only [m-when]]
   [pallet.monad :only [let-s phase-pipeline]]
   [pallet.utils :only [apply-map]]))

(def ^{:private true}
  directory* (action/action-fn directory/directory))
(def ^{:private true}
  remote-file* (action/action-fn remote-file/remote-file-action))

(defn- source-to-cmd-and-path
  [session path url local-file remote-file md5 md5-url]
  (cond
   url (let [tarpath (str
                      (stevedore/script (~lib/tmp-dir)) "/"
                      (.getName
                       (java.io.File. (.getFile (java.net.URL. url)))))]
         [(remote-file* session tarpath :url url :md5 md5 :md5-url md5-url)
          tarpath])
   local-file ["" (str path "-content")]
   remote-file ["" remote-file]))

(action/def-bash-action remote-directory-action
  [session path & {:keys [action url local-file remote-file
                          unpack tar-options unzip-options jar-options
                          strip-components md5 md5-url owner group recursive]
                   :or {action :create
                        tar-options "xz"
                        unzip-options "-o"
                        jar-options "xf"
                        strip-components 1
                        recursive true}
                   :as options}]
  (case action
    :create (let [url (options :url)
                  unpack (options :unpack :tar)]
              (when (and (or url local-file remote-file) unpack)
                (let [[cmd tarpath] (source-to-cmd-and-path
                                     session path
                                     url local-file remote-file md5 md5-url)]
                  (action-plan/checked-commands
                   "remote-directory"
                   (directory*
                    session path :owner owner :group group :recursive false)
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
                     (directory*
                      session path
                      :owner owner
                      :group group
                      :recursive recursive))))))))

(defn remote-directory
  "Specify the contents of remote directory.

   Options:
    - :url              - a url to download content from
    - :unpack           - how download should be extracts (default :tar)
    - :tar-options      - options to pass to tar (default \"xz\")
    - :unzip-options    - options to pass to unzip (default \"-o\")
    - :jar-options      - options to pass to unzip (default \"xf\")
                          jar does not support stripping path components
    - :strip-components - number of path compnents to remove when unpacking
    - :md5              - md5 of file to unpack
    - :md5-url          - url of md5 file for file to unpack

   Ownership options:
    - :owner            - owner of files
    - :group            - group of files
    - :recursive        - flag to recursively set owner and group

   To install the content of an url pointing at a tar file, specify the :url
   option.
       (remote-directory session path
          :url \"http://a.com/path/file.tgz\")

   If there is an md5 url with the tar file's md5, you can specify that as well,
   to prevent unecessary downloads and verify the content.
       (remote-directory session path
          :url \"http://a.com/path/file.tgz\"
          :md5-url \"http://a.com/path/file.md5\")

   To install the content of an url pointing at a zip file, specify the :url
   option and :unpack :unzip.
       (remote-directory session path
          :url \"http://a.com/path/file.\"
          :unpack :unzip)"
  [path & {:keys [action url local-file remote-file
                  unpack tar-options unzip-options jar-options
                  strip-components md5 md5-url owner group recursive
                  force-overwrite
                  local-file-options]
           :or {action :create
                tar-options "xz"
                unzip-options "-o"
                jar-options "xf"
                strip-components 1
                recursive true}
           :as options}]
  (when-let [f (and local-file (io/file local-file))]
    (when (not (and (.exists f) (.isFile f) (.canRead f)))
      (throw (IllegalArgumentException.
              (format
               (str "'%s' does not exist, is a directory, or is unreadable; "
                    "cannot register it for transfer.")
               local-file)))))
  (let-s
    [_ (m-when local-file
               ;; transfer local file to remote system if required
               (remote-file/transfer-file
                local-file
                (str path "-content")
                local-file-options))
     f (action/with-precedence local-file-options
         (apply-map
          remote-directory-action path
          (merge
           {:overwrite-changes force-overwrite} ;; capture the value of the flag
           options)))]
    f))
