(ns pallet.crate.nginx-test
  (:use [pallet.crate.nginx] :reload-all)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.target :as target]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils))

(deftest site-test
  []
  (core/defnode n [])
  (is (= "mkdir -p /etc/nginx/sites-available\nmkdir -p /etc/nginx/sites-enabled\ncat > /etc/nginx/sites-enabled/mysite <<EOF\n\nserver {\n  listen       80;\n  server_name  localhost;\n\n  access_log  /var/log/nginx/access.log;\n\n  location / {\n    root /var/www/data;\n    index  index.html index.htm;\n    ;\n  }\n}\n\nEOF\nrm  /etc/nginx/sites-available/mysite\n"
         (target/with-target nil {:tag :n}
           (resource/build-resources
            [] (site "mysite"))))))
