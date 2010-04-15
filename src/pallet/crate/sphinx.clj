(ns pallet.crate.sphinx
  (:use
   [pallet.stevedore :only [script]]
   [pallet.resource.remote-file :only [remote-file]]
   [pallet.resource.exec-script :only [exec-script]]))

(def sphinx-downloads
  {"0.9.9" ["http://sphinxsearch.com/downloads/sphinx-0.9.9.tar.gz" "x"]})

(defn sphinx
  "Install sphinx from source"
  ([] (sphinx "0.9.9"))
  ([version]
     (let [info (sphinx-downloads version)
           basename (str "sphinx-" version)
           tarfile (str basename ".tar.gz")
           tarpath (str (script (tmp-dir)) "/" tarfile)]
       (remote-file tarpath :url (first info) :md5 (second info))
       (exec-script
        (script
         (cd (tmp-dir))
         (tar xfz ~tarfile)
         (cd ~basename)
         ("(" "./configure" "&&" "make" "&&" "make install" ")" "||" exit 1) )))))
