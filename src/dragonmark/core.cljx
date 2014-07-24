(ns dragonmark.core
  #+cljs (:require-macros [cljs.core.async.macros :refer [go]]
                          [schema.core :as sc])
  (:require
   [schema.core :as sc]
   #+clj [clojure.core.async :as async :refer [go chan timeout]]
   #+cljs [cljs.core.async :as async :refer [chan timeout]]
   )
  
  #+clj (:import
         [clojure.core.async.impl.channels ManyToManyChannel]
         [java.io Writer])
  )

(defonce env-root
  (atom nil))

(def i-got-it
  "Return this from the message handler if the
answer is going to be sent back to the return channel
ansynchronously"
  :dragonmark.core:i_got_it)

(defn- process
  "Processes a message sent to the root channel"
  [message env]
  (let [return (:_return message)
        command (:_cmd message)
        local? (-> message meta :local boolean)
        answer 
        (case command
          "list" {:answer (->> @env :services (filter #(or local? (-> % second :public))) (map first))}

          "add" (if local?
                  (let [old (get-in @env [:services (:service message)])]
                    (when old (go (async/>! old {:_cmd "removed_from_root"})))
                    (swap! env update-in [:services (:service message)] 
                           assoc (:service message) 
                           {:channel (:channel message)
                            :public (-> message :public boolean)})
                    {:answer true})
                  {:error "Cannot add a service without a :local in the meta of the message"})

          "remove" (if local?
                      (let [old (get-in @env [:services (:service message)])]
                        (when old (go (async/>! old {:_cmd "removed_from_root"})))
                        (swap! env update-in [:services] dissoc (:service message)))
                       
                     {:error "Cannot add a service without a :local in the meta of the message"})

          "get-service"
          (let [result (-> @env :services (get (:service message)))
                result (if (or (not local?)
                               (:public result))
                         (:channel result)
                         nil)]
            (if result {:answer result} {:error (str "Service " (:service message) " not found")})
           )
          
          (or
           (some-> (get (:commands @env) command) (apply [message env]))
           {:error 
            (str "Unabled to process command: "
                 command)}))]
    (when (and answer
               return
               (not (= answer i-got-it))) (go (async/>! return answer)))
    ))

(defn build-root-channel
  "Builds a root channel (usually put in env-root,
but can be stand-alone so there can be multiple
root channels in a single address space... nice for testing.
The commands parameter is a map of String/function where the
String is the name of the command and the function is a two
parameter function (message and env) that returns a value to
send to the answer channel or :dragonmark.core:i_got_it"
  [commands]
  (let [c (chan 5)
        env (atom {:services
                   {'root {:channel c
                           :public true}}
                   :commands commands})
        ]
    (go 
     (loop []
       (let [message (async/<! c)]
         (if (nil? message) '() ;; nil... closed channel... bye
             (do
               (process message env)
               (recur)))
       )
     ))
    c))

(defn- index-of
  "find the integer into of the value in the collection"
  [coll value]
  #+clj (.indexOf coll value)
  #+cljs (loop [pos 0 coll coll]
           (if (empty? coll) -1
               (let [i (first coll)
                     r (rest coll)]
                 (cond
                  (= i value) pos
                  :else (recur (+ 1 pos) r)))))
  )

(defn go-parallel
  "Run a bunch of go routines in parallel. params is a sequence of {:chan :msg}.
For each param, a return channel is created and sent with the messages. When
all the messages have been answered, to result is sent back to 
the result chan. If there's a timeout waiting, then send a timeout error
to the result-chan"
  [params timeout result-chan]
  (let [chans (map (fn [p] 
                     (let [ret-chan (async/chan)]
                       [(:chan p) (assoc (:msg p) :_return ret-chan) ret-chan]))
                   params)
        toc (async/timeout timeout)
        len (count chans)
        _ (doall (map (fn [[ch msg _]] (go (async/>! ch msg))) chans))
        alt-on (cons toc (map last chans))
        ret (mapv (fn [x] nil) chans)
        close-and-send (fn [result] 
                         (go (async/>! result-chan result))
                         (doall (map async/close! alt-on))
                         )]
    (go
     (loop [ret ret cnt 0]
       (let [[value chan] (async/alts! alt-on)
             ]
         (if (nil? value)
           (close-and-send {:error "timeout"})
           (let [pos (index-of alt-on chan)
                 ret (assoc ret (- pos 1) value)
                 cnt (+ 1 cnt)]
             (if (= len cnt) 
               (close-and-send ret)
               (recur ret cnt)
               )
             )))
       ))
    ))
                       
#+clj
(defmacro gofor
  "A for comprehension built out of go blocks

```
(gofor
  [a (foo channel {:thing value :other_thing other_value})]
  :let [b (+ a 1)]
  [c (baz other_channel)
   d (moose third_channel {:p b}
   :timeout 45000]
  (println a b c)
  :error (println &err &at))
```
"
  [& info]
  (let [cljs-macro (boolean (:ns &env))
        
        [chan go <! >! close!] (if cljs-macro 

                                 '[cljs.core.async/chan
                                   cljs.core.async.macros/go
                                   cljs.core.async/<!
                                   cljs.core.async/>!
                                   cljs.core.async/close!]
                                 
                                 '[clojure.core.async/chan
                                   clojure.core.async/go
                                   clojure.core.async/<!
                                   clojure.core.async/>!
                                   clojure.core.async/close!])
        
        [err info] 
        (or
         (and
          (= :error (-> info butlast last))
          (seq? (last info))
          [`(fn [~'&err ~'&var] ~(last info)) (drop-last 2 info)])
         
         [`(fn [& foo#] ) info])
        err-var `err#
        [body info] [(last info) (butlast info)]]
    (letfn [(do-go [mine other]
              (let [pairs (partition 2 mine)
                    [timeout pairs]
                    (if (= :timeout (-> pairs last first))
                      [(-> pairs last second) (butlast pairs)]
                      [30000 pairs])]
                `(let [chan# (~chan)]
                   (go-parallel 
                    ~(mapv (fn [[_ [cmd chan params]]] 
                             `{:chan ~chan 
                               :msg (with-meta (assoc ~params :_cmd ~(name cmd)) {:local true})
                               }) pairs) 
                    ~timeout chan#)
                   (~go
                    (let [res# (~<! chan#)]
                      (~close! chan#)
                      (if (vector? res#)
                        (let [answers# (partition 2 (interleave '[~@(map first pairs)] res#))
                              errors# (filter #(-> % second :error) answers#)]
                          (if (not (empty? errors#))
                            (~err-var (-> errors# first first) (-> errors# first second))
                            (let [[~@(map first pairs)] (map :answer res#)]
                              ~other)))
                        (~err-var res# '[~@(mapv first pairs)])))))
                ))
            
            (process-info [info]
              (cond
               (empty? info) `~body
               
               (vector? (first info))
               (do-go (first info) (process-info (rest info)))
               
               (and (= :let (first info))
                    (vector? (second info)))
               `(let ~(second info) ~(process-info (drop 2 info)))
               
               :else (throw (Exception. (str "Don't know how to process " info)))
               ))]
      `(let [~err-var ~err]
         ~(process-info info)))))


#+clj
(defn- build-func
  "Takes a function defintion and builds a name/function pair
where the function applies the function with the named parameters"
  [info]
  (let [xn `x#
        params (:arglists info)
        params (if (-> params first symbol?) (second params) params)
        params (sort #(> (count %1) (count %2)) params)
        params (map #(map keyword %) params)
        
        the-cond `(cond 
                   ~@(mapcat (fn [arity]
                               [`(and 
                                  ~@(map (fn [p] `(contains? ~xn ~p)) arity)
                                  )
                                `(apply ~(:name info) [~@(map (fn [p] `(~p ~xn)) arity)])]) params)
                   :else (with-meta {:error (str "parameters not matched. expecting " ~(str (into [] params)) " but got " (keys ~xn))} {:error true}))
        ]
    
  [(-> info :name name)
   `(fn [~xn]
      ~the-cond
      )]))

#+clj
(defn- wrap-in-thread
  "Wraps the call in a thread if it's on the JVM"
  [env s-exp]
  (if (:ns env) s-exp
      `(future ~s-exp)))

#+clj
(defmacro build-service
  "builds a service -- a channel that looks at all the public functions in the
current package marked with {:service true} and wraps up message responders. When
a message comes in with a :_cmd that has the name of the function, the named parameters
are unwrapped and the function is dispatched and the response is sent back to the channel in :_return.

Plays very well with `gofor`."
  []
  (let [cljs-macro (boolean (:ns &env))

        [chan go <! >! close!] (if cljs-macro '[cljs.core.async/chan
                                                cljs.core.async.macros/go
                                                cljs.core.async/<!
                                                cljs.core.async/>!
                                                cljs.core.async/close!]
                                   
                                   '[clojure.core.async/chan
                                     clojure.core.async/go
                                     clojure.core.async/<!
                                     clojure.core.async/>!
                                     clojure.core.async/close!])

        my-ns (if cljs-macro cljs.analyzer/*cljs-ns* *ns*)

        info 
        (if cljs-macro
         (some->> (cljs.analyzer/get-namespace my-ns) :defs vals 
                  (filter :service)
                  (into []))
         (->> (ns-publics my-ns)
              vals
              (filter #(-> % meta :service))
              (map meta)
              (into [])))
        the-funcs (map build-func info)


        cmds (into {} (map (fn [x] [(-> x :name name) (:doc x)]) info))

        built-funcs (merge {"_commands" 
                            `(fn [x#] ~cmds)
                            }
                           (into {} the-funcs))

        answer `answer#

        the-func `the-func#

        it `it#

        cmd `cmd#

        wrapper `wrapper#
        ]
    (let [ret
          `(let [c# (~chan)
                 funcs# ~built-funcs
                 ]
             (~go
              (loop [~it (~<! c#)]
                (if (nil? ~it) nil
                    (let [~cmd (:_cmd ~it)
                          ~answer (:_return ~it)
                          ~the-func (funcs# ~cmd)
                          ~wrapper (or (-> ~it meta :bound-fn)
                                       (fn [f# p#] (f# p#))) 
                          ]
                      ~(wrap-in-thread 
                        &env
                        `(let [res# (if ~the-func 
                                      (try 
                                        (let [result# (~wrapper ~the-func ~it)]
                                          (if (-> result# meta :error) result# {:answer result#}))
                                        (catch ~(if cljs-macro `js/Object `Exception) excp# {:error excp#}))
                                      {:error (str "Command " ~cmd " not found")})
                               ]
                           (when ~answer
                             (~go (~>! ~answer res#)))))
                      (recur (~<! c#))
                      )
                    )))
             c#
             )
          ]
      ;;  (.println System/err (str "ret\n" ret))
      ret)
  ))

(defprotocol Transport
  "A transport to another address space"
  (send-message [this guid message])
  (close-guid [this guid]))

(defprotocol Serializer
  "A serializer and deserializer that does special things for Channels"
  (serialize [this item transport])
  (deserialize [this string transport])
  (find-channel [this guid]))

;; (sc/defn serializer :- Serializer
;;   "Create a  serializer/deserializer that uses Transit
;; to serialize messages with specially attention to serializing
;; and deserializing Channels. In the case of Channels, the
;; serializer replaces the Channel with a GUID that goes over the wire.
;; On deserialization, the GUID is looked up. If it's not found,
;; then a proxy channel is created"
;;   [opts]
;;   (let [guid-to-info (atom {})
;;         channel-to-info (atom {})]
;;     (reify Serializer
;;       (serialize [this item transport] "")
;;       (deserialize [this string transport] {})
;;       (find-channel [this guid] (get @guid-to-info guid))
;;       )))
