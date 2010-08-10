(ns pallet.resource.remote-file
  "File Contents."
  (:use
   [pallet.resource :only [defresource]]
   [pallet.resource.file :only [adjust-file heredoc]]
   clojure.contrib.logging)
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.template :as template]
   [pallet.resource.file :as file]
   [pallet.utils :as utils]
   [clojure.contrib.def :as def]))

(def/defvar
  content-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(defn remote-file*
  [path & options]
  (let [opts (merge {:action :create} (apply hash-map options))]
    (condp = (opts :action)
        :create
      (let [url (opts :url)
            content (opts :content)
            md5 (opts :md5)
            link (opts :link)
            md5-url (opts :md5-url)
            local-file (opts :local-file)
            remote-file (opts :remote-file)
            template-name (opts :template)]
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
                         (apply concat (seq (select-keys opts [:literal]))))
          local-file (let [temp-path (utils/register-file-transfer! local-file)]
                       (stevedore/script
                        (mv (str "~/" ~temp-path) ~path)))
          remote-file (stevedore/script
                       (cp ~remote-file ~path))
          template-name (apply
                         heredoc
                         path (template/interpolate-template
                               template-name (get opts :values {}))
                         (apply concat (seq (select-keys opts [:literal]))))
          link (stevedore/script (ln -f -s ~link ~path))
          :else (throw
                 (IllegalArgumentException.
                  (str "remote-file " path " specified without content."))))
         (adjust-file path opts))))))

(defresource remote-file "Remote file with contents management.
Options for specifying the file's content are:
  :url url          - download the specified url to the given filepath
  :content string   - use the specified content directly
  :local-file path  - use the file on the local machine at the given path
  :remote-file path - use the file on the remote machine at the given path
Options for specifying the file's permissions are:
  :owner user-name
  :group group-name
  :mode  file-mode"
  remote-file* [filepath & options])
