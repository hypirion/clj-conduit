(ns com.hypirion.conduit
  (:require [clojure.core.async.impl.ioc-macros :as ioc])
  (:refer-clojure :exclude [await]))

(def ^:const XDUCER-STATE-IDX ioc/USER-START-IDX)
(def ^:const ACC-IDX (+ ioc/USER-START-IDX 1))
(def ^:const INPUT-IDX (+ ioc/USER-START-IDX 2))
(def ^:const REDUCER-IDX (+ ioc/USER-START-IDX 3))

(defn await
  "Receive a value downstream, and block until it receives the value.
  If there are no more values left, will \"block\" indefinitely. See also
  `await!`"
  []
  (assert nil "await not used in (conduit ...) block"))

(defn await!
  "Receive a value upstream. If there are no values left upstream,
  `no-left` is returned (or nil if `no-left` is not provided)."
  ([] (await! nil))
  ([no-left]
   (assert nil "await! not used in (conduit ...) block")))

(defmacro if-let-await!
  "Sets the value of (await!) to `name` and runs `then`, if there is a
  value upstream. If there are no more values to read, `else` will be
  ran."
  ([name then]
   `(if-let-await! ~name ~then nil))
  ([name then else]
   `(let [tmp# (await! ::eof)]
      (if-not (identical? tmp# ::eof)
        (let [~name tmp#]
          ~then)
        ~else))))

(defmacro when-let-await!
  "Like if-let-await!, but as when-let."
  [name & body]
  `(if-let-await! ~name
     (do ~@body)))

(defn yield
  "Sends a value downstream. Will \"block\" indefinitely if downstream does not
  accept any more values. See also `yield!`."
  [x]
  (assert nil "yield not used in (conduit ...) block"))

(defn yield!
  "Sends a value downstream. Returns true if downstream still accepts new
  values, false otherwise. When you're being sent false, you should clean up
  and shut down properly."
  ([x]
   (assert nil "yield! not used in (conduit ...) block")))

;; TODO: I should probably put these in another namespace.

(defn do-await
  [state blk]
  (if (identical? :input (ioc/aget-object state XDUCER-STATE-IDX))
    ;; Got input, so pop value off and continue
    (let [val (ioc/aget-object state INPUT-IDX)]
      (ioc/aset-all! state INPUT-IDX nil XDUCER-STATE-IDX :no-input
                     ioc/VALUE-IDX val ioc/STATE-IDX blk)
      :recur)
    ;; Prepare for more input (but do not be surprised if we never go to this
    ;; state afterwards. Set :await-parked so that upstream can omit our step if
    ;; we're done.
    (do (ioc/aset-all! state ioc/STATE-IDX blk
                       XDUCER-STATE-IDX :await-parked)
        nil)))

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
     ;; No more input? Then park. I had the assumption the terminator (this fn)
     ;; had its own block and would be called again, but alas, only the previous
     ;; one would be. So what we do is: We do as with :complete, but we park.
     ;; Since we've not changed our xducer-state, the dispatcher can check
     ;; for that value and replace VALUE-IDX if need be before starting again.
     ;; Complete can just leave the value be and set xducer-state to :complete.
     :no-input (do (ioc/aset-all! state ioc/STATE-IDX blk ioc/VALUE-IDX default-value)
                   nil))))

(defn do-yield
  [state blk val]
  (let [acc (unreduced (ioc/aget-object state ACC-IDX))
        f (ioc/aget-object state REDUCER-IDX)
        res (f acc val)]
    (ioc/aset-all! state ACC-IDX res ioc/VALUE-IDX nil
                   ioc/STATE-IDX blk)
    (if-not (reduced? res)
      :recur
      (do
        (ioc/aset-all! state ioc/STATE-IDX
                       :clojure.core.async.impl.ioc-macros/finished)
        nil))))


(defn do-yield!
  [state blk val]
  ;; This is actually not _that_ okay, because we may unwrap a Reduced input
  ;; acc. Not sure how one would go around that or even if it's considered legal
  ;; transducer sense, but if we don't, we probably break one or two
  ;; transducers. Bah.
  (let [acc (unreduced (ioc/aget-object state ACC-IDX))
        f (ioc/aget-object state REDUCER-IDX)
        res (f acc val)]
    (ioc/aset-all! state ACC-IDX res ioc/VALUE-IDX (not (reduced? res))
                   ioc/STATE-IDX blk)
    :recur))

(def conduit-terminators
  {`await `do-await
   `await! `do-await!
   `yield `do-yield
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
          (if (or (ioc/finished? state#)
                  (identical? (ioc/aget-object state# XDUCER-STATE-IDX)
                              :await-parked))
            (rf# acc#)
            (do
              (ioc/aset-all! state# XDUCER-STATE-IDX :complete
                             ACC-IDX acc#)
              (ioc/run-state-machine state#)
              ;; this will run until we're done, so no worries here.
              (let [acc# (unreduced (ioc/aget-object state# ACC-IDX))]
                ;; cleanup
                (ioc/aset-all! state# ACC-IDX nil)
                (rf# acc#)))))
         ([acc# input#]
          (if (ioc/finished? state#)
            (reduced acc#)
            (do
              (case (ioc/aget-object state# XDUCER-STATE-IDX)
                (:no-input :await-parked)
                (ioc/aset-all! state# XDUCER-STATE-IDX :no-input
                               ACC-IDX acc#
                               ioc/VALUE-IDX input#)
                (ioc/aset-all! state# XDUCER-STATE-IDX :input
                               ACC-IDX acc#
                               INPUT-IDX input#))
              ;; previous call moved
              (ioc/run-state-machine state#)
              (let [acc# (ioc/aget-object state# ACC-IDX)]
                (ioc/aset-all! state# ACC-IDX nil INPUT-IDX nil)
                acc#))))))))
