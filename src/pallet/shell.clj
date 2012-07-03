;;;; A modified version of clojure.java.shell, that allows for reading of
;;;; shell output as it is produced.

; Copyright (c) Rich Hickey. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v10.html at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns
  ^{:author "Chris Houser, Stuart Halloway, Hugo Duncan"
    :doc "Conveniently launch a sub-process providing its stdin and
collecting its stdout"}
  pallet.shell
  (:use [clojure.java.io :only (as-file copy)])
  (:require
   [clojure.tools.logging :as logging])
  (:import (java.io OutputStreamWriter ByteArrayOutputStream StringWriter)
           (java.nio.charset Charset)))

(def ^{:dynamic true} *sh-dir* nil)
(def ^{:dynamic true} *sh-env* nil)

(defmacro with-sh-dir
  "Sets the directory for use with sh, see sh for details."
  {:added "1.2"}
  [dir & forms]
  `(binding [*sh-dir* ~dir]
     ~@forms))

(defmacro with-sh-env
  "Sets the environment for use with sh, see sh for details."
  {:added "1.2"}
  [env & forms]
  `(binding [*sh-env* ~env]
     ~@forms))

(defn- aconcat
  "Concatenates arrays of given type."
  [type & xs]
  (let [target (make-array type (apply + (map count xs)))]
    (loop [i 0 idx 0]
      (when-let [a (nth xs i nil)]
        (System/arraycopy a 0 target idx (count a))
        (recur (inc i) (+ idx (count a)))))
    target))

(defn- parse-args
  [args]
  (let [default-encoding "UTF-8" ;; see sh doc string
        default-opts {:out-enc default-encoding
                      :in-enc default-encoding
                      :dir *sh-dir*
                      :env *sh-env*}
        [cmd opts] (split-with string? args)]
    [cmd (merge default-opts (apply hash-map opts))]))

(defn- ^"[Ljava.lang.String;" as-env-strings
  "Helper so that callers can pass a Clojure map for the :env to sh."
  [arg]
  (cond
   (nil? arg) nil
   (map? arg) (into-array String (map (fn [[k v]] (str (name k) "=" v)) arg))
   true arg))

(defn- stream-to-bytes
  [in]
  (with-open [bout (ByteArrayOutputStream.)]
    (copy in bout)
    (.toByteArray bout)))

(defn- stream-to-string
  ([in] (stream-to-string in (.name (Charset/defaultCharset))))
  ([in enc]
     (with-open [bout (StringWriter.)]
       (copy in bout :encoding enc)
       (.toString bout))))

(defn- stream-to-enc
  [stream enc]
  (if (= enc :bytes)
    (stream-to-bytes stream)
    (stream-to-string stream enc)))

(defn sh
  "Passes the given strings to Runtime.exec() to launch a sub-process.

  Options are

  :in      may be given followed by a String or byte array specifying input
           to be fed to the sub-process's stdin.
  :in-enc  option may be given followed by a String, used as a character
           encoding name (for example \"UTF-8\" or \"ISO-8859-1\") to
           convert the input string specified by the :in option to the
           sub-process's stdin.  Defaults to UTF-8.
           If the :in option provides a byte array, then the bytes are passed
           unencoded, and this option is ignored.
  :out-enc option may be given followed by :bytes, :stream or a String. If a
           String is given, it will be used as a character encoding
           name (for example \"UTF-8\" or \"ISO-8859-1\") to convert
           the sub-process's stdout to a String which is returned.
           If :bytes is given, the sub-process's stdout will be stored
           in a byte array and returned.  Defaults to UTF-8.
  :async   If true, returns a map with :out, :err and :proc keys, and
           the caller is responsible for reading these and
           the exit status.
  :env     override the process env with a map (or the underlying Java
           String[] if you are a masochist).
  :dir     override the process dir with a String or java.io.File.

  You can bind :env or :dir for multiple operations using with-sh-env
  and with-sh-dir.

  sh returns a map of
    :exit => sub-process's exit code
    :out  => sub-process's stdout (as byte[] or String)
    :err  => sub-process's stderr (String via platform default encoding)"
  {:added "1.2"}
  [& args]
  (let [[cmd opts] (parse-args args)
        proc (.exec (Runtime/getRuntime)
                    ^"[Ljava.lang.String;" (into-array String cmd)
                    (as-env-strings (:env opts))
                    (as-file (:dir opts)))
        {:keys [in in-enc out-enc async]} opts]
    (if in
      (future
       (if (instance? (class (byte-array 0)) in)
         (with-open [os (.getOutputStream proc)]
           (.write os ^"[B" in))
         (with-open [osw (OutputStreamWriter.
                          (.getOutputStream proc) ^String in-enc)]
           (.write osw ^String in))))
      (.close (.getOutputStream proc)))
    (if async
      {:out (.getInputStream proc)
       :err (.getErrorStream proc)
       :proc proc}
      (with-open [stdout (.getInputStream proc)
                  stderr (.getErrorStream proc)]
        (let [out (future (stream-to-enc stdout out-enc))
              err (future (stream-to-string stderr))
              exit-code (.waitFor proc)]
          {:exit exit-code :out @out :err @err})))))

;;; Pallet specific functionality
(def
  ^{:doc "Specifies the buffer size used to read the output stream.
    Defaults to 10K"}
  output-buffer-size (atom (* 1024 10)))

(def
  ^{:doc "Specifies the polling period for retrieving command output.
    Defaults to 1000ms."}
  output-poll-period (atom 1000))

(defn read-buffer [stream output-f]
  (let [buffer-size @output-buffer-size
        bytes (byte-array buffer-size)
        sb (StringBuilder.)]
    {:sb sb
     :reader (fn []
               (when (pos? (.available stream))
                 (let [num-read (.read stream bytes 0 buffer-size)
                       s (String. bytes 0 num-read "UTF-8")]
                   (output-f s)
                   (.append sb s)
                   s)))}))

(defn sh-script
  "Run a script on local machine.

   Command:
     :execv  sequence of command and arguments to be run (default /bin/bash).
     :in     standard input for the process.
   Options:
     :output-f  function to incrementally process output"
  [{:keys [execv in] :as command}
   {:keys [output-f] :as options}]
  (logging/tracef "sh-script %s" command)
  (if output-f
    (try
      (let [{:keys [out err proc]} (apply
                                    sh
                                    (concat
                                     (or execv ["/bin/bash"]) ;; TODO generalise
                                     [:in in :async true]))
            out-reader (read-buffer out output-f)
            err-reader (read-buffer err output-f)
            period @output-poll-period
            read-out (:reader out-reader)
            read-err (:reader err-reader)]
        (with-open [out out err err]
          (while (not (try (.exitValue proc)
                           (catch IllegalThreadStateException _)))
            (Thread/sleep period)
            (read-out)
            (read-err))
          (while (read-out))
          (while (read-err))
          (let [exit (.exitValue proc)]
            {:exit exit
             :out (str (:sb out-reader))
             :err (str (:sb err-reader))}))))
    (apply sh (concat (or execv ["/bin/bash"]) [:in in]))))
