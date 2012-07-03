(ns pallet.cache.impl
  "An implementation namespace for pallet.cache")

(defprotocol CacheProtocolImpl
  "Cache implementation interface."
  (lookup [cache e] [cache e default]
    "Retrieve the value associated with `e` if it exists")
  (has? [cache e]
    "Checks if the cache contains a value associtaed with `e`"))
