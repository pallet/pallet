(ns pallet.crate.rubygems
 "Installation of rubygems from source"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.resource-when :only [resource-when resource-when-not]]
   [pallet.resource :only [defcomponent]]
   [pallet.crate.ruby :only [ruby ruby-packages ruby-version]]
   [pallet.resource.user :only [user-home]]
   [pallet.stevedore :only [script defimpl map-to-arg-string]]
   [pallet.script :only [defscript]]
   [pallet.utils :only [*admin-user*]]
   [pallet.target :only [packager]])
  (:require
   [pallet.resource.file]
   [org.danlarkin [json :as json]]))

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
  ([] (rubygems "1.3.6"))
  ([version]
     ;(resource-when-not (file-exists? "/usr/bin/ruby"))
     (ruby-packages)
     (resource-when (< @(ruby-version) "1.8.6")
                    (ruby))
     (let [info (rubygems-downloads version)
           basename (str "rubygems-" version)
           tarfile (str basename ".tgz")
           tarpath (str (script (tmp-dir)) "/" tarfile)]
       (remote-file
        tarpath
        :url (first info)
        :md5 (second info))
       (exec-script
        (script
         (cd (tmp-dir))
         (tar xfz ~tarfile)
         (cd ~basename)
         (ruby setup.rb)
         (if-not (|| (file-exists? "/usr/bin/gem1.8")
                     (file-exists? "/usr/local/bin/gem"))
           (exit 1))
         ;; Create a symlink if we only have one ruby version installed
         (if-not (&& (file-exists? "/usr/bin/gem1.8")
                     (file-exists? "/usr/bin/gem1.9"))
           (if (file-exists? "/usr/bin/gem1.8")
             (ln "-sfv" "/usr/bin/gem1.8" "/usr/bin/gem")))
         )))))

(defn rubygems-update
  [] (exec-script (script ("gem" "update" "--system"))))

(defn gem* [name & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :install} opts)]
    (condp = (opts :action)
      :install
      (script (gem "install" ~name ~(select-keys opts [:version :no-ri :no-rdoc])))
      :delete
      (script (gem "uninstall" ~name ~(select-keys opts [:version :no-ri :no-rdoc]))))))

(defcomponent gem "Gem management."
  gem* [name & options])

(defn gem-source* [source & options]
(let [opts (apply hash-map options)
      opts (merge {:action :create} opts)]
    (condp = (opts :action)
      :create
      (script
       (if-not ("gem" "sources" "--list" "|" "grep" ~source)
         (gem "sources" ~source ~{:add true})))
      :delete
      (script (gem "sources" ~source ~{:remove true})))))

(defcomponent gem-source "Gem source management."
  gem-source* [source & options])

(defn gemrc* [m & user?]
  (let [user (or (first user?) (*admin-user* :username))]
    (remote-file* (str (script (user-home user)) "/.gemrc")
                  :content (.replaceAll (json/encode-to-str m) "[{}]" "")
                  :owner user)))

(defcomponent gemrc "rubygems configuration"
  gemrc* [m & user?])
