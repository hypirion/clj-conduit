(ns com.hypirion.conduit
  (:require [clojure.core.async.impl.ioc-macros :as ioc])
  (:import [java.util.concurrent.atomic AtomicReferenceArray]))

(def ^:const XDUCER-STATE-IDX ioc/USER-START-IDX)
(def ^:const ACC-IDX (+ ioc/USER-START-IDX 1))
(def ^:const INPUT-IDX (+ ioc/USER-START-IDX 2))
(def ^:const REDUCER-IDX (+ ioc/USER-START-IDX 3))

(defn await!
  "Received a value downstream. If there are no values left upstream, no-left is
  returned (or nil if no-left is not provided)"
  ([] (await! nil))
  ([no-left]
   (assert nil "await! not used in (conduit ...) block")))

(defmacro if-let-await!
  "Sets the value of (await!) to name and runs then, if there is a
  value upstream. If there are no more values to read, else will be
  ran."
  ([name then]
   `(if-let-await! ~name ~then nil))
  ([name then else]
   `(let [tmp# (await! ::nothing)]
      (if-not (identical? tmp# ::nothing)
        (let [~name tmp#]
          ~then)
        ~else))))

(defmacro when-let-await!
  "Like if-let-await!, but as when-let."
  [name & body]
  `(if-let-await! ~name
     (do ~@body)))

(defn yield!
  "Sends a value downstream. Returns true if downstream still accepts new
  values, false otherwise."
  ([x]
   (assert nil "yield! not used in (conduit ...) block")))

(defn do-yield!
  [state blk val]
  ;; This is okay, because we wrap acc inputs in a Reduced.
  (let [acc (unreduced (ioc/aget-object state ACC-IDX))
        f (ioc/aget-object state REDUCER-IDX)
        res (f acc val)]
    (ioc/aset-all! state ACC-IDX res ioc/VALUE-IDX (not (reduced? res))
                   ioc/STATE-IDX blk)
    :recur))

(defn do-await!
  ([state blk]
   (do-await! state blk nil))
  ([state blk default-value]
   (case (ioc/aget-object state XDUCER-STATE-IDX)
     ;; Got input, so pop value off and continue
     :input (let [val (ioc/aget-object state INPUT-IDX)]
              (ioc/aset-all! state INPUT-IDX nil XDUCER-STATE-IDX :no-input
                             ioc/VALUE-IDX val ioc/STATE-IDX blk)
              :recur)
     ;; No data left, so set the default-value as value
     :complete (do (ioc/aset-all! state ioc/STATE-IDX blk ioc/VALUE-IDX default-value)
                   :recur)
     ;; No more input? Then park. The transducer wrapper will push the next
     ;; value in before we're started again, so this is not an issue.
     :no-input nil)))

(def conduit-terminators
  {`await! `do-await!
   `yield! `do-yield!})

(defmacro conduit
  "Creates a conduit (transducer)"
  [& body]
  `(fn [rf#]
     (let [captured-bindings# (clojure.lang.Var/getThreadBindingFrame)
           ;; ^ Does this make sense? Probably?
           f# ~(ioc/state-machine `(do ~@body) 4 (keys &env) conduit-terminators)
           state# (-> (f#)
                      (ioc/aset-all! REDUCER-IDX rf#
                                     ioc/BINDINGS-IDX captured-bindings#))]
       (fn
         ([] (rf#))
         ([acc#]
          (if (ioc/finished? state#)
            acc#
            (do
              (ioc/aset-all! state# XDUCER-STATE-IDX :complete
                             ACC-IDX (reduced acc#))
              (ioc/run-state-machine state#)
              ;; this will run until we're done, so no worries here.
              (let [acc# (unreduced (ioc/aget-object state# ACC-IDX))]
                ;; cleanup
                (ioc/aset-all! state# ACC-IDX nil)
                acc#))))
         ([acc# input#]
          (if (ioc/finished? state#)
            (reduced acc#)
            (do
              (ioc/aset-all! state# XDUCER-STATE-IDX :input
                             ACC-IDX (reduced acc#)
                             INPUT-IDX input#)
              (ioc/run-state-machine state#)
              (let [acc# (ioc/aget-object state# ACC-IDX)]
                (ioc/aset-all! state# ACC-IDX nil INPUT-IDX nil)
                acc#))))))))
