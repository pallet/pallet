(ns pallet.crate.network-service
  "Crate for working with network services"
  (:require
   [pallet.actions :refer [exec-checked-script]]
   [pallet.crate :refer [defplan]]
   [pallet.script.lib :as lib]))

(defplan wait-for-port-listen
  "Wait for the network port `port` to be in a listening state.

   Options:
   - :standoff      time between checking port state (seconds)
   - :max-retries   number of times to test port state before erroring
   - :service-name  name of service to use in messages (defaults to port)
   - :protocol      name of the network protocol family (:raw, :tcp, :udp or :udplite, defaults to :tcp)"

  [port & {:keys [max-retries standoff service-name protocol]
           :or {max-retries 5 standoff 2 protocol :tcp
                service-name (str "port " port)}}]
  (exec-checked-script
   (format "Wait for %s to be in a listen state" service-name)
   (group (chain-or (let x 0) true))
   (while
       (pipe ("netstat" ~(format "-ln%s" (case protocol
                                           :raw "w"
                                           :tcp "t"
                                           :udp "u"
                                           :udplite "U")
                                 ))
             ("awk" ~(format "'$4 ~ /:%s$/ {exit 1}'" port)))
     (let x (+ x 1))
     (if (= ~max-retries @x)
       (do
         (println
          ~(format "Timed out waiting for listen state for %s" service-name)
          >&2)
         (~lib/exit 1)))
     (println ~(format "Waiting for %s to be in a listen state" service-name))
     ("sleep" ~standoff))
   ("sleep" ~standoff)))

(defplan wait-for-http-status
  "Wait for a url to respond with the given HTTP status code.

   Options:
   - :standoff      time between checking HTTP status (seconds)
   - :max-retries   number of times to test HTTP status before erroring
   - :url-name      name of url to use in messages (defaults to url)"

  [url status & {:keys [max-retries standoff url-name cookie insecure
                        ssl-version]
                 :or {max-retries 5 standoff 2
                      url-name url}}]
  (exec-checked-script
   (format "Wait for %s to return a %s status" url-name status)

   (if (~lib/has-command? wget)
     (defn httpresponse []
       (pipe
        ("wget" -q -S -O "/dev/null"
         ~(if cookie (str "--header " (format "'Cookie: %s'" cookie)) "")
         ~(if insecure "--no-check-certificate" "")
         ~(if ssl-version (str "--secure-protocol=SSLv" ssl-version) "")
         (quoted ~url) "2>&1")
        ("grep" "HTTP/1.1")
        ("tail" -1)
        ("grep" -o -e (quoted "[0-9][0-9][0-9]"))))
     (if (~lib/has-command? curl)
       (defn httpresponse []
         ("curl" -sL -w (quoted "%{http_code}")
          ~(if cookie (str "-b '" cookie "'") "")
          ~(if insecure "--insecure" "")
          ~(if ssl-version (str "-" ssl-version) "")
          (quoted ~url)
          -o "/dev/null"))
       (do
         (println "No httpresponse utility available")
         (~lib/exit 1))))

   (group (chain-or (let x 0) true))
   (while
       (!= ~status @("httpresponse"))
     (let x (+ x 1))
     (if (= ~max-retries @x)
       (do
         (println
          ~(format
            "Timed out waiting for %s to return a %s status" url-name status)
          >&2)
         (~lib/exit 1)))
     (println ~(format "Waiting for %s to return a %s status" url-name status))
     ("sleep" ~standoff))
   ("sleep" ~standoff)))

(defplan wait-for-port-response
  "Wait for a port to respond to a message with a given response regex.

   Options:
   - :host          host to check (defaults to localhost)
   - :timeout       time to wait for a response (default 2 secs)
   - :standoff      time between checking HTTP status (seconds)
   - :max-retries   number of times to test HTTP status before erroring
   - :service-name  name of service to use in messages (defaults to port)"

  [port message response-regex
   & {:keys [host timeout max-retries standoff service-name]
      :or {host "localhost" max-retries 5 standoff 2 timeout 2
           service-name (str "port " port)}}]
  (exec-checked-script
   (format
    "Wait for %s to return a response %s to message %s"
    service-name response-regex message)

   (group (chain-or (let x 0) true))
   (while
       ("!" (pipe (println (quoted ~message))
                ("nc" -q ~timeout ~host ~port)
                ("grep" -E (quoted ~response-regex))))
     (let x (+ x 1))
     (if (= ~max-retries @x)
       (do
         (println
          ~(format
            "Timed out waiting for %s to return response %s"
            service-name response-regex)
          >&2)
         (~lib/exit 1)))
     (println
      ~(format
        "Waiting for %s to return response %s" service-name response-regex))
     ("sleep" ~standoff))
   ("sleep" ~standoff)))
