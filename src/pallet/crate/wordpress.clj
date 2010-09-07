(ns pallet.crate.wordpress
  "Install wordpress"
  (:require
   [pallet.resource.package :as package]
   [pallet.crate.php :as php]
   [pallet.crate.mysql :as mysql]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file])
  (:use
   pallet.thread-expr))

(defn wordpress
  "Install wordpress - no configuration yet!"
  [request
   mysql-wp-username
   mysql-wp-password
   mysql-wp-database
   & extensions]
  (->
   request
   (package/package "wordpress")
   (for-> [extension extensions]
     (package/package (format "wordpress-%s" (name extension))))
   (mysql/create-database mysql-wp-database)
   (mysql/create-user mysql-wp-username mysql-wp-password)
   (mysql/grant "ALL" (format "`%s`.*" mysql-wp-database) mysql-wp-username)))
