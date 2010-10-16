(ns pallet.crate.nginx-test
  (:use pallet.crate.nginx)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest site-test
  []
  (is (= (first
          (build-resources
           []
           (directory/directory "/etc/nginx/sites-available")
           (directory/directory "/etc/nginx/sites-enabled")
           (remote-file/remote-file
            "/etc/nginx/sites-enabled/mysite"
            :content "server {\n  listen       80;\n  server_name  localhost;\n\n  access_log  /var/log/nginx/access.log;\n\nlocation / {\n  root /some/path;\n  index  index.html index.htm;\n  \n  \n  \n}\n\nlocation /a {\n  \n  index  index.html index.htm;\n  proxy_pass localhost:8080;\n  \n  \n}\n\n}\n")
           (file/file
            "/etc/nginx/sites-available/mysite" :action :delete :force true)))
         (first
          (build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (site "mysite"
                 :locations [{:location "/" :root "/some/path"}
                             {:location "/a"
                              :proxy_pass "localhost:8080"}]))))))
