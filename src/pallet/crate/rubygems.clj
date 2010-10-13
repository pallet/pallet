(ns pallet.crate.rubygems
 "Installation of rubygems from source"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.resource-when :only [resource-when resource-when-not]]
   [pallet.resource :only [defresource]]
   [pallet.crate.ruby :only [ruby ruby-packages ruby-version]]
   [pallet.resource.user :only [user-home]]
   [pallet.stevedore :as stevedore]
   [pallet.script :only [defscript]]
   [pallet.utils :only [*admin-user*]]
   [clojure.contrib.json :as json])
  (:require
   [pallet.resource.file]
   [pallet.stevedore :as stevedore]))

(defscript gem [action package & options])
(defimpl gem :default [action package & options]
  ("gem" ~action ~(map-to-arg-string (first options)) ~package))

(def rubygems-downloads
     {"1.3.6"  ["http://rubyforge.org/frs/download.php/69365/rubygems-1.3.6.tgz"
                "789ca8e9ad1d4d3fe5f0534fcc038a0d"]
      "1.3.5" ["http://rubyforge.org/frs/download.php/60718/rubygems-1.3.5.tgz"
                "6e317335898e73beab15623cdd5f8cff"]})

(defn rubygems
  "Install rubygems from source"
  ([request] (rubygems request "1.3.6"))
  ([request version]
     (let [info (rubygems-downloads version)
           basename (str "rubygems-" version)
           tarfile (str basename ".tgz")
           tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
       (->
        request
        (ruby-packages)
        (resource-when (< @(ruby-version) "1.8.6")
                       (ruby))
        (remote-file
         tarpath
         :url (first info)
         :md5 (second info))
        (exec-script/exec-script
         (if-not (pipe ("gem" "--version") (grep (quoted ~version)))
           (do
             ~(stevedore/checked-script
               "Building rubygems"
               (cd (tmp-dir))
               (tar xfz ~tarfile)
               (cd ~basename)
               (ruby setup.rb)
               (if-not (|| (file-exists? "/usr/bin/gem1.8")
                           (file-exists? "/usr/local/bin/gem"))
                 (do (println "Could not find rubygem executable")
                     (exit 1)))
               ;; Create a symlink if we only have one ruby version installed
               (if-not (&& (file-exists? "/usr/bin/gem1.8")
                           (file-exists? "/usr/bin/gem1.9"))
                 (if (file-exists? "/usr/bin/gem1.8")
                   (ln "-sfv" "/usr/bin/gem1.8" "/usr/bin/gem")))
               (if-not (&& (file-exists? "/usr/local/bin/gem1.8")
                           (file-exists? "/usr/local/bin/gem1.9"))
                 (if (file-exists? "/usr/local/bin/gem1.8")
                   (ln "-sfv" "/usr/locl/bin/gem1.8" "/usr/local/bin/gem")))))))))))

(defn rubygems-update
  [request]
  (exec-script/exec-script
   request
   ("gem" "update" "--system")))

(defresource gem "Gem management."
  (gem*
   [request name & {:keys [action version no-ri no-rdoc]
                    :or {action :install}
                    :as options}]
   (case action
     :install (stevedore/checked-script
               (format "Install gem %s" name)
               (gem
                "install" ~name
                ~(select-keys options [:version :no-ri :no-rdoc])))
     :delete (stevedore/checked-script
              (format "Uninstall gem %s" name)
              (gem
               "uninstall" ~name
               ~(select-keys options [:version :no-ri :no-rdoc]))))))


(defresource gem-source "Gem source management."
  (gem-source*
   [request source & {:keys [action] :or {action :create} :as options}]
   (case action
     :create (stevedore/script
              (if-not ("gem" "sources" "--list" "|" "grep" ~source)
                (gem "sources" ~source ~{:add true})))
     :delete (stevedore/script (gem "sources" ~source ~{:remove true})))))

(defresource gemrc "rubygems configuration"
  (gemrc*
   [request m & user?]
   (let [user (or (first user?) (*admin-user* :username))]
     (remote-file*
      request
      (str (stevedore/script (user-home ~user)) "/.gemrc")
      :content (.replaceAll (json/json-str m) "[{}]" "")
      :owner user))))

(defn require-rubygems
  "Ensure that a version of rubygems is installed"
   [request]
   (exec-script/exec-checked-script
    request
    "Checking for rubygems"
    ("gem" "--version")))
