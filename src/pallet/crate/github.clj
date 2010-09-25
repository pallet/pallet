(ns pallet.crate.github
  "Crate for github api"
  (:require
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [clj-http.client :as client]
   [clojure.string :as string]
   [clojure.contrib.json :as json]
   [clojure.contrib.logging :as logging]
   [clojure.contrib.condition :as condition]))

(def endpoint "https://github.com/api/v2/json")

(defn api
  "Call the github api using json"
  [username apikey method [& path] & [opts]]
  (update-in
   (client/request
    (update-in
     (merge
      {:method method
       :url (str endpoint (string/join "/" path))
       :basic-auth [username apikey]
       :content-type :json
       :accept :json}
      opts)
     [:body]
     (fn body-to-json [b] (when b (json/json-str b)))))
   [:body]
   (fn body-from-json [b] (when b (json/read-json b)))))

(defn- set-deploy-key
  "Set a deploy key for the specified project."
  [project title key [username apikey]]
  (let [response (api username apikey :get ["/repos/keys" project])]
    (when-not (some #(= key (:key %)) (:public_keys response))
      (logging/info
       (format "Installing key %s to github project %s" title project))
      (let [response (api username apikey :post ["/repos/keys" project]
                          {:body {:title title :key key}})]
        (when-not (some #(= key (:key %)) (:public_keys response))
          (logging/error
           (format
            "Failed to install key %s to github project %s (response %s)"
            title project response))
          (condition/raise
           :type :github-deploy-key-failed-to-install
           :message (format
                     "Github deploy key '%s' for project '%s'"
                     " failed to install."
                     title project)))))))

(defn- credentials
  "Extract credentials from request or arguments, and normalise username
   to the required form, depending on whether apikey or password supplied."
  [request options]
  (let [{:keys [username password apikey]} (merge
                                            (parameter/get-for
                                             request [:github] nil)
                                            options)]
    (when-not (and username (or password apikey))
      (condition/raise
       :type :no-github-credentials
       :message "No github credentials supplied in request or invocation."))
    (if apikey
      [(str username "/token") apikey]
      [username password])))

(resource/deflocal deploy-key
  "Set a deploy key"
  (deploy-key*
   [request project title key & {:keys [username password apikey] :as options}]
   (set-deploy-key project title key (credentials request options))
   request))
