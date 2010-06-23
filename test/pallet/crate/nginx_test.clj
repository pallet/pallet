(ns pallet.crate.nginx-test
  (:use [pallet.crate.nginx] :reload-all)
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
  (is (= (stevedore/do-script
          (directory/directory* "/etc/nginx/sites-available")
          (directory/directory* "/etc/nginx/sites-enabled")
          (remote-file/remote-file*
           "/etc/nginx/sites-enabled/mysite"
           :content "\nserver {\n  listen       80;\n  server_name  localhost;\n\n  access_log  /var/log/nginx/access.log;\n\n  location / {\n    root /var/www/data;\n    index  index.html index.htm;\n    ;\n  }\n}\n")
          (file/file* "/etc/nginx/sites-available/mysite"
                      :action :delete :force true))
         (target/with-target nil {:tag :n :image [:ubuntu]}
           (resource/build-resources
            [] (site "mysite"))))))
