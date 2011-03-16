(ns pallet.resource.remote-directory
  "Resource to specify the content of a remote directory.  At present the
   content can come from a downloaded tar or zip file."
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]))

(def ^{:private true}
  directory* (action/action-fn directory/directory))
(def ^{:private true}
  remote-file* (action/action-fn remote-file/remote-file-resource))

(action/def-bash-action remote-directory
  "Specify the contents of remote directory.

   Options:
    - :url              - a url to download content from
    - :unpack           - how download should be extracts (default :tar)
    - :tar-options      - options to pass to tar (default \"xz\")
    - :unzip-options    - options to pass to unzip (default \"-o\")
    - :strip-components - number of path compnents to remove when unpacking
    - :md5              - md5 of file to unpack
    - :md5-url          - url of md5 file for file to unpack

   Ownership options:
    - :owner            - owner of files
    - :group            - group of files
    - :recursive        - flag to recursively set owner and group

   To install the content of an url pointing at a tar file, specify the :url
   option.
       (remote-directory request path
          :url \"http://a.com/path/file.tgz\")

   If there is an md5 url with the tar file's md5, you can specify that as well,
   to prevent unecessary downloads and verify the content.
       (remote-directory request path
          :url \"http://a.com/path/file.tgz\"
          :md5-url \"http://a.com/path/file.md5\")

   To install the content of an url pointing at a zip file, specify the :url
   option and :unpack :unzip.
       (remote-directory request path
          :url \"http://a.com/path/file.\"
          :unpack :unzip)"
  [request path & {:keys [action url unpack tar-options unzip-options
                          strip-components md5 md5-url owner group recursive]
                   :or {action :create
                        tar-options "xz"
                        unzip-options "-o"
                        strip-components 1}
                   :as options}]
  (case action
    :create (let [url (options :url)
                  unpack (options :unpack :tar)]
              (when (and url unpack)
                (let [filename (.getName
                                (java.io.File. (.getFile (java.net.URL. url))))
                      tarpath (str (stevedore/script (tmp-dir)) "/" filename)]
                  (stevedore/checked-commands
                   "remote-directory"
                   (directory*
                    request path :owner owner :group group)
                   (remote-file*
                    request tarpath :url url :md5 md5 :md5-url md5-url)
                   (condp = unpack
                       :tar (stevedore/script
                             (cd ~path)
                             (tar ~tar-options
                                  ~(str "--strip-components=" strip-components)
                                  -f ~tarpath))
                       :unzip (stevedore/script
                               (cd ~path)
                               (unzip ~unzip-options ~tarpath)))
                   (if recursive
                     (directory*
                      request path
                      :owner owner
                      :group group
                      :recursive recursive))))))))
