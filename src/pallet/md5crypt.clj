(ns pallet.md5crypt
  (:import [java.security NoSuchAlgorithmException MessageDigest]))

(defonce salt-chars
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890")

(defonce itoa64
  "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")

(defonce md5-magic "$1$")
(defonce apache-magic "$apr1$")

(defn to64
  "Return value encoded as n base64 chars"
  [value n]
  (if (pos? n)
    (str (.charAt itoa64 (bit-and value 0x3f))
         (to64 (bit-shift-right value 6) (dec n)))))

(defn salt
  []
  (apply str (take 8 (repeatedly #(rand-nth itoa64)))))

(defn clean-salt
  "Clean up a passed salt value"
  [salt magic]
  (let [salt (if (.startsWith salt magic)
                (.substring salt (.length magic))
                salt)
        salt (if (.contains salt "$")
               (.substring salt 0 (.indexOf salt "$"))
               salt)
        salt (if (> (.length salt) 8)
               (.substring salt 0 8)
               salt)]
    salt))

(defn set-array [#^bytes array value]
  (dotimes [i (alength array)]
    (aset array i value))
  array)

(defn byte-as-unsigned
  [b]
  (bit-and (int (byte b)) 0xff))

(defn crypt
  "LINUX/BSD MD5Crypt function"
  ([password]
     (crypt password (salt) md5-magic))
  ([password salt]
     (crypt password salt md5-magic))
  ([password salt magic]
     (let [salt (clean-salt salt magic)
           ctx (doto (MessageDigest/getInstance "md5")
                 (.update (.getBytes password))
                 (.update (.getBytes magic))
                 (.update (.getBytes salt)))
           ctx1 (doto (MessageDigest/getInstance "md5")
                  (.update (.getBytes password))
                  (.update (.getBytes salt))
                  (.update (.getBytes password)))
           final-state (.digest ctx1)]

       (loop [l (.length password)]
         (.update ctx final-state 0 (min l 16))
         (if (> l 16)
           (recur (- l 16))))

       (set-array final-state (byte 0))


       (loop [i (.length password)]
         (when (pos? i)
           (if (pos? (bit-and i 1))
             (.update ctx final-state 0 1)
             (.update ctx (.getBytes password) 0 1))
           (recur (bit-shift-right i 1))))

       (let [final-state (loop [final-state (.digest ctx)
                                i 0]
                           (if (< i 1000)
                             (let [ctx1 (MessageDigest/getInstance "md5")]
                               (if (pos? (bit-and i 1))
                                 (.update ctx1 (.getBytes password))
                                 (.update ctx1 final-state 0 16))
                               (if (pos? (mod i 3))
                                 (.update ctx1 (.getBytes salt)))
                               (if (pos? (mod i 7))
                                 (.update ctx1 (.getBytes password)))
                               (if (pos? (bit-and i 1))
                                 (.update ctx1 final-state 0 16)
                                 (.update ctx1 (.getBytes password)))
                               (recur (.digest ctx1) (inc i)))
                             final-state))]

         (str
          magic
          salt
          "$"
          (to64 (bit-or
                 (bit-or
                  (bit-shift-left (byte-as-unsigned (aget final-state 0)) 16)
                  (bit-shift-left (byte-as-unsigned (aget final-state 6)) 8))
                 (byte-as-unsigned (aget final-state 12)))
                4)
          (to64 (bit-or
                 (bit-or
                  (bit-shift-left (byte-as-unsigned (aget final-state 1)) 16)
                  (bit-shift-left (byte-as-unsigned (aget final-state 7)) 8))
                 (byte-as-unsigned (aget final-state 13)))
                4)
          (to64 (bit-or
                 (bit-or
                  (bit-shift-left (byte-as-unsigned (aget final-state 2)) 16)
                  (bit-shift-left (byte-as-unsigned (aget final-state 8)) 8))
                 (byte-as-unsigned (aget final-state 14)))
                4)
          (to64 (bit-or
                 (bit-or
                  (bit-shift-left (byte-as-unsigned (aget final-state 3)) 16)
                  (bit-shift-left (byte-as-unsigned (aget final-state 9)) 8))
                 (byte-as-unsigned (aget final-state 15)))
                4)
          (to64 (bit-or
                 (bit-or
                  (bit-shift-left (byte-as-unsigned (aget final-state 4)) 16)
                  (bit-shift-left (byte-as-unsigned (aget final-state 10)) 8))
                 (byte-as-unsigned (aget final-state 5)))
                4)
          (to64 (byte-as-unsigned (aget final-state 11)) 2))))))
