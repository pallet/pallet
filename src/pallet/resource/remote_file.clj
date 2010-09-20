(ns pallet.resource.remote-file
  "File Contents."
  (:use
   [pallet.resource :only [defresource]]
   [pallet.resource.file :only [adjust-file heredoc]]
   clojure.contrib.logging)
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.template :as templates]
   [pallet.resource.file :as file]
   [pallet.utils :as utils]
   [org.jclouds.blobstore :as jclouds-blobstore]
   [clojure.contrib.def :as def]))

(def/defvar
  content-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :blob :blobstore]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(def/defvar
  all-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :owner :group :mode]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(defn get-request
  [request]
  (stevedore/script
   (curl -s "--retry" 20
         ~(apply str (map
                      #(format "-H \"%s: %s\" " (first %) (second %))
                      (.. request getHeaders entries)))
         ~(.. request getEndpoint toASCIIString))))

(defresource remote-file
  "Remote file with contents management.
Options for specifying the file's content are:
  :url url          - download the specified url to the given filepath
  :content string   - use the specified content directly
  :local-file path  - use the file on the local machine at the given path
  :remote-file path - use the file on the remote machine at the given path
  :link             - file to link to:
  :literal          - prevent shell expansion on content
  :md5              - md5 for file
  :md5-url          - a url containing file's md5
  :template         - specify a template to be interpolated
  :values           - values for interpolation
  :blob             - map of :container, :path
  :blobstore        - a jclouds blobstore object (override blobstore in request)
Options for specifying the file's permissions are:
  :owner user-name
  :group group-name
  :mode  file-mode"
  (remote-file*
   [request path & {:keys [action url local-file remote-file link
                           content literal
                           template values
                           md5 md5-url
                           owner group mode force
                           blob blobstore]
                    :or {action :create}
                    :as options}]

   (case action
     :create
     (stevedore/checked-commands
      (str "remote-file " path)
      (cond
       (and url md5) (stevedore/chained-script
                      (if (|| (not (file-exists? ~path))
                              (!= ~md5 @(md5sum ~path "|" cut "-f1 -d' '")))
                        ~(stevedore/chained-script
                          (var tmpfile @(mktemp prfXXXXX))
                          (download-file ~url @tmpfile)
                          (mv @tmpfile ~path)))
                      (echo "MD5 sum is" @(md5sum ~path)))
       (and url md5-url) (let [md5-path (format "%s.md5" path)]
                           (stevedore/chained-script
                            (cd @(dirname ~path))
                            (if-not (file-exists? ~md5-path)
                              (download-file ~md5-url ~md5-path))
                            (if-not (&& (file-exists? ~path)
                                        @(md5sum --quiet --check ~md5-path))
                              (do
                                ~(stevedore/checked-script
                                  (format "Download %s" url)
                                  (var tmpfile @(mktemp prfXXXXX))
                                  (download-file ~url @tmpfile)
                                  (mv @tmpfile ~path)
                                  (if-not (md5sum --quiet --check ~md5-path)
                                    (do
                                      (echo "Invalid md5 for " ~path)
                                      (rm ~path)
                                      (exit 1))))))))
       url (stevedore/chained-script
            (var tmpfile @(mktemp prfXXXXX))
            (download-file ~url @tmpfile)
            (mv @tmpfile ~path)
            (echo "MD5 sum is" @(md5sum ~path)))
       content (apply heredoc
                      path content
                      (apply concat (seq (select-keys options [:literal]))))
       local-file (let [temp-path (resource/register-file-transfer!
                                   local-file)]
                    (stevedore/script
                     (mv (str "~/" ~temp-path) ~path)))
       remote-file (stevedore/script
                    (cp ~remote-file ~path))
       template (apply
                 heredoc
                 path (templates/interpolate-template
                       template (or values {}) (:node-type request))
                 (apply concat (seq (select-keys options [:literal]))))
       link (stevedore/script (ln -f -s ~link ~path))
       blob (stevedore/checked-script
             "Download blob"
             (download-request
              ~path
              ~(jclouds-blobstore/sign-blob-request
                (:container blob) (:path blob)
                {:method :get}
                (or blobstore (:blobstore request)))))
       :else (throw
              (IllegalArgumentException.
               (str "remote-file " path " specified without content."))))
      (adjust-file path options))
     :delete (stevedore/checked-script
              (str "delete remote-file " path)
              (rm ~path ~(select-keys options [:force]))))))
