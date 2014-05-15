(ns pallet.utils.schema
  "Utils around schema"
  (:require
   [schema.core :as schema :refer [check required-key optional-key]]))

(defn validate
  [spec value msg]
  (try (schema/validate spec value)
       (catch Exception e
         (throw (ex-info msg (ex-data e) e)))))

(defn check-keys
  [m keys spec msg]
  (validate spec (select-keys m keys) msg))

(defn update-in-both
  "Update a schema inside a (both) schema."
  [^schema.core.Both both-schema index f & args]
  {:pre [(integer? index)]}
  (apply schema/both
         (apply update-in (vec (.schemas both-schema)) [index] f args)))


;;; workaround for https://github.com/Prismatic/schema/issues/92
(do
  (defmacro extend-primitive [cast-sym class-sym class-explain]
    `(extend-protocol schema.core/Schema
       ~cast-sym
       (walker [this#]
         (schema.core/subschema-walker ~class-sym))
       (explain [this#]
         '~class-explain)))

  (extend-primitive clojure.core$bytes (Class/forName "[B") bytes))
