(ns pallet.crate.network-service
  "Crate for working with network services"
  (:require
   [pallet.action.exec-script :as exec-script]))

(defn wait-for-port-listen
  "Wait for the network port `port` to be in a listening state.

   Options:
   - :standoff      time between checking port state (seconds)
   - :max-retries   number of times to test port state before erroring
   - :service-name  name of service to use in messages (defaults to port)"

  [session port & {:keys [max-retries standoff service-name]
                   :or {max-retries 5 standoff 2
                        service-name (str "port " port)}}]
  (->
   session
   (exec-script/exec-checked-script
    (format "Wait for %s to be in a listen state" service-name)
    (group (chain-or (let x 0) true))
    (while
        (pipe (netstat -lnt) (awk ~(format "'$4 ~ /:%s$/ {exit 1}'" port)))
      (let x (+ x 1))
      (if (= ~max-retries @x)
        (do
          (println
           ~(format "Timed out waiting for listen state for %s" service-name)
           >&2)
          (exit 1)))
      (println ~(format "Waiting for %s to be in a listen state" service-name))
      (sleep ~standoff))
    (sleep ~standoff))))

(defn wait-for-http-status
  "Wait for a url to respond with the given HTTP status code.

   Options:
   - :standoff      time between checking HTTP status (seconds)
   - :max-retries   number of times to test HTTP status before erroring
   - :url-name      name of url to use in messages (defaults to url)"

  [session url status & {:keys [max-retries standoff url-name]
                         :or {max-retries 5 standoff 2
                              url-name url}}]
  (->
   session
   (exec-script/exec-checked-script
    (format "Wait for %s to return a %s status" url-name status)

    (if ("test" @(shell/which wget))
      (defn httpresponse []
        (pipe
         ("wget" -q -S -O "/dev/null" (quoted ~url) "2>&1")
         ("grep" "HTTP/1.1")
         ("tail" -1)
         ("grep" -o -e (quoted "[0-9][0-9][0-9]"))))
      (if ("test" @(shell/which curl))
        (defn httpresponse []
          ("curl" -sL -w (quoted "%{http_code}") (quoted ~url)
           -o "/dev/null"))
        (do
          (println "No httpresponse utility available")
          (shell/exit 1))))

    (group (chain-or (let x 0) true))
    (while
        (!= ~status @(httpresponse))
      (let x (+ x 1))
      (if (= ~max-retries @x)
        (do
          (println
           ~(format
             "Timed out waiting for %s to return a %s status" url-name status)
           >&2)
          (exit 1)))
      (println ~(format "Waiting for %s to return a %s status" url-name status))
      (sleep ~standoff))
    (sleep ~standoff))))

(defn wait-for-port-response
  "Wait for a port to respond to a message with a given response regex.

   Options:
   - :host          host to check (defaults to localhost)
   - :timeout       time to wait for a response (default 2 secs)
   - :standoff      time between checking HTTP status (seconds)
   - :max-retries   number of times to test HTTP status before erroring
   - :service-name  name of service to use in messages (defaults to port)"

  [session port message response-regex
   & {:keys [host timeout max-retries standoff service-name]
      :or {host "localhost" max-retries 5 standoff 2 timeout 2
           service-name (str "port " port)}}]
  (->
   session
   (exec-script/exec-checked-script
    (format
     "Wait for %s to return a response %s to message %s"
     service-name response-regex message)

    (group (chain-or (let x 0) true))
    (while
        (! (pipe (println (quoted ~message))
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
          (exit 1)))
      (println
       ~(format
         "Waiting for %s to return response %s" service-name response-regex))
      (sleep ~standoff))
    (sleep ~standoff))))
