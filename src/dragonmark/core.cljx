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
(let [
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
              `(let [chan# (~'chan)]
                 (go-parallel 
                  ~(mapv (fn [[_ [cmd chan params]]] 
                           `{:chan ~chan 
                             :msg (with-meta (assoc ~params :_cmd ~(name cmd)) {:local true})
                             }) pairs) 
                  ~timeout chan#)
                 (~'go
                  (let [res# (~'<! chan#)]
                    (~'close! chan#)
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

      
      
;; (gofor 
;;  [root (get-service @env-root {:service 'root})]
;;  [added (add root {:service 'wombat2 :channel (chan) :public true})]
;;  [b (list root)]
;;  :let [a (+ 1 1)]
;;  (println "dog: " a " and " b " root " root " added " added) :error (println "Got an error " &err " for frogs " &var))
