(ns dragonmark.core
  #+cljs (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   #+clj [clojure.core.async :as async :refer [go chan timeout]]
   #+cljs [cljs.core.async :as async :refer [chan timeout]]
   )
  
  #+clj (:import
         [clojure.core.async.impl.channels ManyToManyChannel]
         [java.io Writer])
  )

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(defn- find-guid-for-chan
  "Given a GUID, find a channel and create one if it's not found"
  [guid]
  (throw (#+clj Exception.
                #+cljs js/Exception. 
                "FIXME")))

#+clj (defmethod print-method ManyToManyChannel [chan, ^Writer w]
   (let [guid (find-guid-for-chan chan)]
  (.write w "#guid-chan\"")
  (.write w guid)
  (.write w "\"")))
