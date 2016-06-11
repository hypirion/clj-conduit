# clj-conduit

Conduits are transducers with a new interface. It tries to be simpler and more
readable than the raw transducer interface, at the cost of performance.

With conduits, the `take` transducer can be implemented as follows:

```clj
(ns my.lib
 (:refer-clojure :exclude [await])
 (:require [com.hypirion.conduit :refer [await yield conduit]]))

(defn taking [n]
  (conduit
    (dotimes [_ n]
      (yield (await)))))
```

The actual `take` transducer is implemented like this:

```clj
(defn take
  ([n]
     (fn [rf]
       (let [nv (volatile! n)]
         (fn
           ([] (rf))
           ([result] (rf result))
           ([result input]
              (let [n @nv
                    nn (vswap! nv dec)
                    result (if (pos? n)
                             (rf result input)
                             result)]
                (if (not (pos? nn))
                  (ensure-reduced result)
                  result)))))))
   ...)
```

For some examples, see the
[clojure.core ports](https://github.com/hyPiRion/clj-conduit/wiki/clojure.core-ports)
– all of Clojure's transducers implemented with conduits. For a more complex
example, you can see an implementation of a
[simple moving average](https://github.com/hyPiRion/clj-conduit/wiki/examples#simple-moving-average)
with conduits.

## Quickstart

If you want the full story, you can read my blogpost
[From Transducers to Conduits and Back Again](http://hypirion.com/musings/transducers-to-conduits-and-back)
which explains in detail the entire library and the rationale for it. If you
just want basic usage, you can continue reading this section.

To use clj-conduit, you need to add the following to your `:dependencies`:

```clj
[com.hypirion/conduit "0.1.0"]
```

The most essential part of the conduit library is `conduit`, `await` and
`yield`. I usually prefer to refer them directly, like so:

```clj
(ns my.namespace
 (:refer-clojure :exclude [await])
 (:require [com.hypirion.conduit :refer [await yield conduit]]))
```

Note that since there's already a function named `await` in clojure.core, we
exclude it to avoid the warning message.

`conduit` is a macro that transforms the code inside into transducer. It will
work as if the code is a process. To receive values from upstream, call `await`.
It can be considered as a "blocking" read, in which it will block until there is
a new value available. If there is no more values to read, it will block forever
(but contrary to Java threads, this will be garbage collected).

To send values downstream, call `yield`. This will indefinitely block if
downstream doesn't want any more values (and will be garbage collected). 

If the conduit code block finishes or blocks on a `yield`, then it signals
upstream that it is done.

The smallest valuable conduit example is probably an implementation of the
transducer `map`. It is implemented as follows:

```clj
(defn mapping [f]
  (conduit
    (while true
      (yield (f (await))))))
```

Note that we have to wrap the `(yield (f (await)))` part in a while true-loop to
avoid reading just a single value. If it was implemented as follows, it would
only read (at most) a single value and send it down:

```clj
(defn mapping1 [f]
  (conduit
    (yield (f (await)))))
```

Note that you must call `yield` and `await` directly inside a `(conduit ...)`
block, just like `<!` and friends from core.async needs to be called directly
inside a `(go ...)` block. This means that the following is not ok:

```clj
(defn await-n [n]
 (dotimes [_ n]
   (await))) ;; <- not ok, even if the call is inside a conduit

(defn taking [n]
  (conduit
    (doseq [val (repeatedly n await)]
              ;; ^- not ok to pass await/yield to higher order functions
      (yield val))))
```

## License

Copyright © 2016 Jean Niklas L'orange

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
