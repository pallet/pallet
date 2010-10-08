(ns pallet.resource.remote-directory
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]))

(resource/defresource remote-directory
  "Specify the contents of remote directory.
   Options:
     :url              - a url to download content from
     :unpack           - how download should be extracts (default :tar)
     :tar-options      - options to pass to tar (default \"xz\")
     :unzip-options    - options to pass to unzip (default \"xz\")
     :strip-components - number of pathc ompnents to remove when unpacking
     :md5              - md5 of file to unpack
     :md5-url          - url of md5 file for file to unpack
     :owner            - owner of files
     :group            - group of files
     :recursive        - flag to recursively set owner and group"
  (remote-directory*
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
                    (directory/directory*
                     request path :owner owner :group group)
                    (remote-file/remote-file*
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
                      (directory/directory*
                       request path
                       :owner owner
                       :group group
                       :recursive recursive)))))))))
