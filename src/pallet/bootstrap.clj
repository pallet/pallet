(ns #^{:author "Hugo Duncan"}
  pallet.bootstrap
  "Boostrap functions."
 (:require pallet.compat)
  (:use [org.jclouds.compute :only [os-families]]
        [pallet.chef :only [*remote-chef-path*]]
        [pallet.utils :only [*admin-user* make-user slurp-resource
                             resource-path quoted as-string]]
        clojure.contrib.logging))

(pallet.compat/require-contrib)

;;; Bootstrap from fragments
(defonce fragment-root "bootstrap")
(defn- bootstrap-fragment-paths [fragment tag os-family]
  (map #(pallet.compat/file fragment-root (name fragment) %)
       (remove nil? [(as-string tag) (and os-family (name os-family)) "default"])))

(defn- template-os-family [template]
  (first (filter #(some (fn [x] (= (as-string %) (str x))) (os-families)) template)))

(defn bootstrap-fragment
  "Returns a fragment. Fragments should be resources under the bootstrap
path. It returns the first fragment it finds in the following paths:

  bootstrap/fragment/tag
  bootstrap/fragment/os-family
  bootstrap/fragment
"
  [fragment tag template]
  (let [os-family (template-os-family template)
        found (remove
               nil?
               (map #(slurp-resource (.getPath %))
                         (bootstrap-fragment-paths fragment tag os-family)))]
    (if (empty? found)
      (do
        (warn (str "Could not find a fragment for fragment="
                   fragment " tag=" tag " os=" os-family))
        "")
      (first found))))

(defn bootstrap-template
  "Returns a bootstrap script function that returns a fragment based on
  specified template."
  [fragment]
  {:authorize-public-key nil
   :bootstrap-script (partial bootstrap-fragment fragment)})



;;; Bootstrapping with chef
(def *bootstrap-repo* "http://github.com/hugoduncan/orcloud-cookbooks/tarball/master")
(defmacro with-bootstrap-repo
  "Define the url used to retrieve the bootstrap repository."
  [url & body]
  `(binding [*bootstrap-repo* url]
     ~@body))

(defn md5crypt [passwd]
  (.replace (md5crypt.MD5Crypt/crypt passwd) "$" "\\$"))


(defn bootstrap-script
  "A script to install chef and create the specified user, enabled for
  access with the specified public key."
  ([tag template] (bootstrap-script tag template *admin-user* *bootstrap-repo*))
  ([tag template user] (bootstrap-script tag template user *bootstrap-repo*))
  ([tag template user bootstrap-url]
     (str (bootstrap-fragment :ensure-resolve tag template)
          (bootstrap-fragment :update-pkg-mgr tag template)
          (bootstrap-fragment :install-rubygems tag template)
          (apply str (interpose
                      "\n"
                      [ "gem install chef"
                        "cat > ~/solo.rb <<EOF"
                        "log_level :info"
                        "log_location STDOUT"
                        (str "file_cache_path " (quoted *remote-chef-path*))
                        (str "cookbook_path [ "
                             (quoted (str *remote-chef-path* "/site-cookbooks")) ","
                             (quoted (str *remote-chef-path* "/cookbooks"))
                             " ]")
                        (str "role_path " (quoted (str *remote-chef-path* "/roles")))
                        "ssl_verify_mode :verify_none"
                        "Chef::Log::Formatter.show_time = false"
                        "EOF"
                        "cat > ~/conf.json <<EOF"
                        (str "{\"orc\":{\"user\":{\"name\":\"" (:username user) "\",\"password\":\""
                             (md5crypt (:password user)) "\"},\"sudoers\":[\"" (:username user) "\"],")
                        (str "\"pk\":\"" (.trim (slurp (:public-key-path user)))
                             "\"},\"run_list\":[\"bootstrap-node\"]}")
                        "EOF"
                        (str "mkdir -p " *remote-chef-path*)
                        (str "wget -nv -O- " bootstrap-url " | tar xvz -C " *remote-chef-path* " --strip-components 1")
                        (str "chef-solo -c ~/solo.rb -j ~/conf.json")
                        (str "rm -rf " *remote-chef-path* "/*")]))))) ;; prevent permission issues

(defn bootstrap-admin-user
  "Returns a bootstrap descriptor for installing an admin user."
  ([] (bootstrap-admin-user *admin-user* *bootstrap-repo*))
  ([user] (bootstrap-admin-user user *bootstrap-repo*))
  ([user bootstrap-repo]
     {:authorize-public-key (:public-key-path user)
      :bootstrap-script #(bootstrap-script %1 %2 user bootstrap-repo)}))

(defn- bootstrap-merge [{apk :authorize-public-key bs :bootstrap-script}
                        {apk2 :authorize-public-key bs2 :bootstrap-script}]
  {:authorize-public-key (or apk apk2)
   :bootstrap-script (if (nil? bs2) bs (conj bs bs2))})

(defn bootstrap-with
  "Specify bootstrap functions to apply."
  [& bootstraps]
  (reduce #(bootstrap-merge %1 %2)
          {:authorize-public-key nil :bootstrap-script []}
          bootstraps))
