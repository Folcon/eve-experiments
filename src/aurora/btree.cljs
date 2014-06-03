(ns aurora.btree
  (:require [cemerick.double-check :as dc]
            [cemerick.double-check.generators :as gen]
            [cemerick.double-check.properties :as prop :include-macros true]
            [cemerick.pprng :as pprng]
            clojure.set)
  (:require-macros [aurora.macros :refer [debug check apush apush* amake aclear typeof set!! dofrom perf-time]]))

;; COMPARISONS

;; 'bool' < 'number' < 'string' < 'undefined'
(def least false)
(def greatest js/undefined)

(defn val? [a]
  (or (string? a) (number? a)))

(defn val-compare [a b]
  (if (identical? a b)
    0
    (if (or (and (identical? (typeof a) (typeof b))
                 (< a b))
            (< (typeof a) (typeof b)))
      -1
      1)))

(defn val-lt [a b]
  (== -1 (val-compare a b)))

(defn val-lte [a b]
  (not (== 1 (val-compare a b))))

(defn least-key [key-len]
  (let [result #js []]
    (dotimes [_ key-len]
      (.push result least))
    result))

(defn greatest-key [key-len]
  (let [result #js []]
    (dotimes [_ key-len]
      (.push result greatest))
    result))

(defn key-compare [as bs]
  (let [as-len (alength as)
        bs-len (alength bs)]
    (assert (== as-len bs-len) (pr-str as bs))
    (loop [i 0]
      (if (< i as-len)
        (let [a (aget as i)
              b (aget bs i)]
          (if (identical? a b)
            (recur (+ i 1))
            (if (or (and (identical? (typeof a) (typeof b))
                         (< a b))
                    (< (typeof a) (typeof b)))
              -1
              1)))
        0))))

(defn ^boolean prefix-not= [as bs max-len]
  (loop [i 0]
    (if (< i max-len)
      (let [a (aget as i)
            b (aget bs i)]
        (if (identical? a b)
          (recur (+ i 1))
          true))
      false)))

(defn ^boolean key= [as bs]
  (let [as-len (alength as)
        bs-len (alength bs)]
    (assert (== as-len bs-len) (pr-str as bs))
    (loop [i 0]
      (if (< i as-len)
        (let [a (aget as i)
              b (aget bs i)]
          (if (identical? a b)
            (recur (+ i 1))
            false))
        true))))

(defn ^boolean key-not= [as bs]
  (let [as-len (alength as)
        bs-len (alength bs)]
    (assert (== as-len bs-len) (pr-str as bs))
    (loop [i 0]
      (if (< i as-len)
        (let [a (aget as i)
              b (aget bs i)]
          (if (identical? a b)
            (recur (+ i 1))
            true))
        false))))

(defn ^boolean key-lt [as bs]
  (let [as-len (alength as)
        bs-len (alength bs)]
    (assert (== as-len bs-len) (pr-str as bs))
    (loop [i 0]
      (if (< i as-len)
        (let [a (aget as i)
              b (aget bs i)]
          (if (identical? a b)
            (recur (+ i 1))
            (or (and (identical? (typeof a) (typeof b))
                         (< a b))
                    (< (typeof a) (typeof b)))))
        false))))

(defn ^boolean key-gt [as bs]
  (let [as-len (alength as)
        bs-len (alength bs)]
    (assert (== as-len bs-len) (pr-str as bs))
    (loop [i 0]
      (if (< i as-len)
        (let [a (aget as i)
              b (aget bs i)]
          (if (identical? a b)
            (recur (+ i 1))
            (or (and (identical? (typeof a) (typeof b))
                         (> a b))
                    (> (typeof a) (typeof b)))))
        false))))

(defn ^boolean key-lte [as bs]
  (not (== 1 (key-compare as bs))))

(defn ^boolean key-gte [as bs]
  (not (== -1 (key-compare as bs))))

(defn key-find-gt [keys key]
  (loop [lo 0
         hi (- (alength keys) 1)]
    (if (< hi lo)
      lo
      (let [mid (+ lo (js/Math.floor (/ (- hi lo) 2)))
            mid-key (aget keys mid)]
        (if (key-lt mid-key key)
          (recur (+ mid 1) hi)
          (if (key= mid-key key)
            (+ mid 1)
            (recur lo (- mid 1))))))))

(defn key-find-gte [keys key]
  (loop [lo 0
         hi (- (alength keys) 1)]
    (if (< hi lo)
      lo
      (let [mid (+ lo (js/Math.floor (/ (- hi lo) 2)))
            mid-key (aget keys mid)]
        (if (key-lt mid-key key)
          (recur (+ mid 1) hi)
          (if (key= mid-key key)
            mid
            (recur lo (- mid 1))))))))

;; TREES

(def left-child 0)
(def right-child 1)

(deftype Tree [max-keys key-len ^:mutable root]
  Object
  (toString [this]
            (pr-str (into {} (map vec this))))
  (assoc! [this key val]
          (.assoc! root key val max-keys))
  (dissoc! [this key]
           (.dissoc! root key max-keys))
  (push! [this ix key&val&child which-child]
           (let [left-child (if (== which-child left-child) (aget key&val&child 2) root)
                 right-child (if (== which-child right-child) (aget key&val&child 2) root)]
             (set! root (Node. this 0 #js [(aget key&val&child 0)] #js [(aget key&val&child 1)] #js [left-child right-child] (.-lower left-child) (.-upper right-child)))
             (set! (.-parent left-child) root)
             (set! (.-parent-ix left-child) 0)
             (set! (.-parent right-child) root)
             (set! (.-parent-ix right-child) 1)))
  (maintain! [this])
  (valid! [this]
          (when (> (alength (.-keys root)) 0) ;; the empty tree does not obey most invariants
            (.valid! root max-keys))
          true)
  (pretty-print [this]
                (prn :root)
                (loop [nodes [root]]
                  (when (seq nodes)
                    (apply println (map #(.pretty-print %) nodes))
                    (recur (mapcat #(.-children %) nodes)))))
  (foreach [this f]
           (.foreach root f))
  ISeqable
  (-seq [this]
        (let [results #js []]
          (.foreach this #(apush results #js [%1 %2]))
          (seq results))))

(deftype Node [parent parent-ix keys vals children ^:mutable lower ^:mutable upper]
  Object
  (assoc! [this key val max-keys]
          (let [ix (key-find-gte keys key)]
            (if (and (< ix (alength keys)) (key= key (aget keys ix)))
              (do
                (aset vals ix val)
                true)
              (if (nil? children)
                (do
                  (.push! this ix #js [key val])
                  (.maintain! this max-keys)
                  false)
                (.assoc! (aget children ix) key val max-keys)))))
  (dissoc! [this key max-keys]
           (let [ix (key-find-gte keys key)]
             (if (and (< ix (alength keys)) (key= key (aget keys ix)))
               (if (nil? children)
                 (do
                   (.pop! this ix)
                   (.maintain! this max-keys)
                   true)
                 (loop [node (aget children (+ ix 1))]
                   (if (not (nil? (.-children node)))
                     (recur (aget (.-children node) 0))
                     (do
                       (aset keys ix (aget (.-keys node) 0))
                       (aset vals ix (aget (.-vals node) 0))
                       (.pop! node 0)
                       (.maintain! node max-keys)
                       (.maintain! this max-keys)
                       true))))
               (if (nil? children)
                 false ;; done
                 (.dissoc! (aget children ix) key max-keys)))))
  (push! [this ix key&val&child which-child]
         (.splice keys ix 0 (aget key&val&child 0))
         (.splice vals ix 0 (aget key&val&child 1))
         (when-not (nil? children)
           (let [child-ix (+ ix which-child)]
             (.splice children child-ix 0 (aget key&val&child 2)))))
  (pop! [this ix which-child]
        (let [key (aget keys ix)
              val (aget vals ix)
              child nil]
          (.splice keys ix 1)
          (.splice vals ix 1)
          (if-not (nil? children)
            (let [child-ix (+ ix which-child)
                  child (aget children child-ix)]
              (.splice children child-ix 1)
              (set! (.-parent child) nil)
              #js [key val child])
            #js [key val])))
  (maintain! [this max-keys]
             (assert (not (nil? max-keys)))
             (when-not (nil? parent)
               (let [min-keys (js/Math.floor (/ max-keys 2))]
                 (when-not (nil? children)
                   (dotimes [ix (alength children)]
                     (let [child (aget children ix)]
                       (set! (.-parent-ix child) ix)
                       (set! (.-parent child) this))))
                 (if (> (alength keys) max-keys)
                   (.split! this max-keys)
                   (if (and (< (alength keys) min-keys) (instance? Node parent))
                     (.rotate-left! this max-keys)
                     (if (== (alength keys) 0)
                       (if (nil? children)
                         (do
                           (set! lower nil)
                           (set! upper nil))
                         (do
                           #_(assert (== 1 (alength children)))
                           #_(assert (instance? Tree parent))
                           (set! (.-parent (aget children 0)) parent)
                           (set! (.-root parent) (aget children 0))))
                       (do
                         (.update-lower! this (if (nil? children) (aget keys 0) (.-lower (aget children 0))))
                         (.update-upper! this (if (nil? children) (aget keys (- (alength keys) 1)) (.-upper (aget children (- (alength children) 1))))))))))))
  (update-lower! [this new-lower]
                 (when (or (nil? lower) (key-not= lower new-lower))
                   (set! lower new-lower)
                   (when (and (instance? Node parent) (== parent-ix 0))
                     (.update-lower! parent new-lower))))
  (update-upper! [this new-upper]
                 (when (or (nil? upper) (key-not= upper new-upper))
                   (set! upper new-upper)
                   (when (and (instance? Node parent) (== parent-ix (- (alength (.-children parent)) 1)))
                     (.update-upper! parent new-upper))))
  (split! [this max-keys]
          (let [median (js/Math.floor (/ (alength keys) 2))
                right-node (Node. parent (+ parent-ix 1) #js [] #js [] (when-not (nil? children) #js []) nil nil)]
            (while (> (alength keys) (+ median 1))
              (.push! right-node 0 (.pop! this (- (alength keys) 1) right-child) left-child))
            (when-not (nil? children)
              (.unshift (.-children right-node) (.pop children)))
            (.push! parent parent-ix #js [(.pop keys) (.pop vals) right-node] right-child)
            (.maintain! this max-keys)
            (.maintain! right-node max-keys)
            (.maintain! parent max-keys)
            #_(.valid! this max-keys)
            #_(.valid! right-node max-keys)))
  (rotate-left! [this max-keys]
                (if (> parent-ix 0)
                  (let [left-node (aget (.-children parent) (- parent-ix 1))
                        min-keys (js/Math.floor (/ max-keys 2))]
                    (if (> (alength (.-keys left-node)) min-keys)
                      (let [key&val&child (.pop! left-node (- (alength (.-keys left-node)) 1) right-child)
                            separator-ix (- parent-ix 1)]
                        (.push! this 0 #js [(aget (.-keys parent) separator-ix) (aget (.-vals parent) separator-ix) (aget key&val&child 2)] left-child)
                        (aset (.-keys parent) separator-ix (aget key&val&child 0))
                        (aset (.-vals parent) separator-ix (aget key&val&child 1))
                        (.maintain! this max-keys)
                        (.maintain! left-node max-keys)
                        (.maintain! parent max-keys))
                      (.rotate-right! this max-keys)))
                  (.rotate-right! this max-keys)))
  (rotate-right! [this max-keys]
                 (if (< parent-ix (- (alength (.-children parent)) 2))
                   (let [right-node (aget (.-children parent) (+ parent-ix 1))
                         min-keys (js/Math.floor (/ max-keys 2))]
                     (if (> (alength (.-keys right-node)) min-keys)
                       (let [key&val&child (.pop! right-node 0 left-child)
                             separator-ix parent-ix]
                         (.push! this (alength keys) #js [(aget (.-keys parent) separator-ix) (aget (.-vals parent) separator-ix) (aget key&val&child 2)] right-child)
                         (aset (.-keys parent) separator-ix (aget key&val&child 0))
                         (aset (.-vals parent) separator-ix (aget key&val&child 1))
                         (.maintain! this max-keys)
                         (.maintain! right-node max-keys)
                         (.maintain! parent max-keys))
                       (.merge! this max-keys)))
                   (.merge! this max-keys)))
  (merge! [this max-keys]
          (let [parent parent ;; in case it gets nulled out by .pop!
                separator-ix (if (> parent-ix 0) (- parent-ix 1) parent-ix)
                key&val&child (.pop! parent separator-ix right-child)
                left-node (aget (.-children parent) separator-ix)
                right-node (aget key&val&child 2)]
            (.push! left-node (alength (.-keys left-node))
                    #js [(aget key&val&child 0)
                         (aget key&val&child 1)
                         (when-not (nil? (.-children right-node)) (.shift (.-children right-node)))]
                    right-child)
            (while (> (alength (.-keys right-node)) 0)
              (.push! left-node (alength (.-keys left-node)) (.pop! right-node 0 left-child) right-child))
            (.maintain! left-node max-keys)
            (.maintain! right-node max-keys)
            (.maintain! parent max-keys)))
  (valid! [this max-keys]
          (let [min-keys (js/Math.floor (/ max-keys 2))]
            (when (instance? Node parent) ;; root is allowed to have less keys
              (assert (>= (count keys) min-keys) (pr-str keys min-keys)))
            (assert (<= (count keys) max-keys) (pr-str keys max-keys))
            (assert (= (count keys) (count (set keys))))
            (assert (= (seq keys) (seq (sort-by identity key-compare keys))))
            (assert (every? #(key-lte lower %) keys) (pr-str lower keys))
            (assert (every? #(key-gte upper %) keys) (pr-str upper keys))
            (if (= 0 (count children))
              (do
                (assert (= (count keys) (count vals)) (pr-str keys vals))
                (assert (= lower (aget keys 0)) (pr-str lower keys))
                (assert (= upper (aget keys (- (alength keys) 1))) (pr-str upper keys)))
              (do
                (assert (> (count keys) 0))
                (dotimes [ix (count children)]
                  (assert (= ix (.-parent-ix (aget children ix)))))
                (assert (= (count keys) (count vals) (dec (count children))) (pr-str keys vals children))
                (assert (= lower (.-lower (aget children 0))) (pr-str lower (.-lower (aget children 0))))
                (assert (= upper (.-upper (aget children (- (alength children) 1)))) (pr-str upper (.-upper (aget children (- (alength children) 1)))))
                (assert (every? #(key-gt (aget keys %) (.-upper (aget children %))) (range (count keys))))
                (assert (every? #(key-lt (aget keys %) (.-lower (aget children (inc %)))) (range (count keys))))
                (dotimes [i (count children)] (.valid! (aget children i) max-keys))))))
  (pretty-print [this]
                (str "(" parent-ix ")" "|" (pr-str lower) " " (pr-str (vec keys)) " " (pr-str upper) "|"))
  (foreach [this f]
           (dotimes [i (alength keys)]
             (when (not (nil? children))
               (.foreach (aget children i) f))
             (f (aget keys i) (aget vals i)))
           (when (not (nil? children))
             (.foreach (aget children (alength keys)) f))))

(defn tree [min-keys key-len]
  (let [node (Node. nil nil #js [] #js [] nil nil nil)
        tree (Tree. (* 2 min-keys) key-len node)]
    (set! (.-parent node) tree)
    (set! (.-parent-ix node) 0)
    tree))

;; ITERATORS
;; .key / .val return key and val currently pointed at. key may be aliased
;; On creation/reset, points to first key
;; On next, either point to next key or set .-end? true.
;; On seek, point to first key greater than or equal to seek-key, or otherwise the last key
;; NOTE iterators are not write-safe unless reset after writing

(deftype Iterator [tree ^:mutable node ^:mutable ix]
  Object
  (reset [this key]
         (set! node (.-root tree))
         (set! ix 0)
         (set! end? (> (alength (.-keys node)) 0)))
  (seek-gt [this key]
           (loop []
             (if (and (instance? Node (.-parent node))
                      (or (key-lte (.-upper node) key)
                          (key-lt key (.-lower node))))
               (do
                 (set! ix 0)
                 (set! node (.-parent node))
                 (recur))
               (loop []
                  (set! ix (key-find-gt (.-keys node) key))
                  (if (nil? (.-children node))
                    (if (< ix (alength (.-keys node)))
                      (aget (.-keys node) ix)
                      nil)
                    (if (key-lte (.-upper (aget (.-children node) ix)) key)
                      (aget (.-keys node) ix)
                      (do
                        (set! node (aget (.-children node) ix))
                        (set! ix 0)
                        (recur))))))))
  (seek-gte [this key]
            (loop []
              (if (and (instance? Node (.-parent node))
                       (or (key-lt (.-upper node) key)
                           (key-lt key (.-lower node))))
                (do
                  (set! ix 0)
                  (set! node (.-parent node))
                  (recur))
                (loop []
                  (set! ix (key-find-gte (.-keys node) key))
                  (if (nil? (.-children node))
                    (if (< ix (alength (.-keys node)))
                      (aget (.-keys node) ix)
                      nil)
                    (if (key-lt (.-upper (aget (.-children node) ix)) key)
                      (aget (.-keys node) ix)
                      (do
                        (set! node (aget (.-children node) ix))
                        (set! ix 0)
                        (recur)))))))))

(defn iterator [tree]
  (Iterator. tree (.-root tree) 0 (> (alength (.-keys (.-root tree))) 0)))

;; CONSTRAINTS

;; los and his are inclusive

;; propagate updates the current lo/hi for each var and returns one of:
;;   failed (no possible solutions)
;;   changed (possible solutions, los/his changed)
;;   unchanged (possible solutions, los/his unchanged)
;; split-left either:
;;   breaks the solutions into two branches, sets the left branch, returns true
;;   has only one possible solution, does nothing, returns false
;; split-right:
;;   breaks the solutions into two branches, sets the right branch

;; solver remembers ixes, handles copying los/his
;; solver push los/his when splitting and remembers who split
;; solver checks changed/failed, sets dirty

(deftype Contains [iterator right-los failed-los]
  Object
  (reset [this]
         (.reset iterator))
  (propagate [this los his]
             ;; widen los/his to just the bounds we can use here
             (loop [i 0]
               (when (< i (alength los))
                 (if (identical? (aget los i) (aget his i))
                   (recur (+ i 1))
                   (loop [i (+ i 1)]
                     (when (< i (alength los))
                       (aset los i least)
                       (aset his i greatest)
                       (recur (+ i 1)))))))
             ;; update los
             (let [new-los (or (.seek-gte iterator los) failed-los)]
               (loop [i 0]
                 (when (< i (alength los))
                   (aset los i (aget new-los i))
                   (if (identical? (aget new-los i) (aget his i))
                     (recur (+ i 1)))))))
  (split-left [this los his]
              ;; fix the value of the first non-fixed var
              (loop [i 0]
                (when (< i (alength los))
                  (if (identical? (aget los i) (aget his i))
                    (recur (+ i 1))
                    (aset his i (aget los i))))))
  (split-right [this los his]
               ;; copy the los
               (dotimes [i (alength los)]
                 (aset right-los i (aget los i)))
               ;; find the his for the left branch...
               (loop [i 0]
                 (when (< i (alength right-los))
                   (if (identical? (aget right-los i) (aget his i))
                     (recur (+ i 1))
                     (loop [i (+ i 1)]
                       (when (< i (alength right-los))
                         (aset right-los i greatest)
                         (recur (+ i 1)))))))
               (debug :right-los right-los)
               ;; ...and then seek past it
               (let [new-los (or (.seek-gt iterator right-los) failed-los)]
                 (loop [i 0]
                   (when (< i (alength los))
                     (aset los i (aget new-los i))
                     (if (identical? (aget new-los i) (aget his i))
                       (recur (+ i 1))))))))

(defn contains [iterator]
  (let [key-len (.-key-len (.-tree iterator))]
    (Contains. iterator (make-array key-len) (greatest-key key-len))))

;; SOLVER

;; TODO replace last-changed with dirty tracking
(deftype Solver [constraints constraint->ixes constraint->los constraint->his ^:mutable los ^:mutable his ^:mutable depth pushed-los pushed-his pushed-splitters ^:mutable failed?]
  Object
  (reset [this]
         (dotimes [i (alength constraints)]
           (.reset (aget constraints i)))
         (dotimes [i (alength los)]
           (aset los i least)
           (aset his i greatest))
         (set! depth 0)
         (aclear pushed-los)
         (aclear pushed-his)
         (aclear pushed-splitters)
         (set! end? false))
  (write-bounds [this current]
                (let [current-ixes (aget constraint->ixes current)
                      current-los (aget constraint->los current)
                      current-his (aget constraint->his current)]
                 (dotimes [i (alength current-ixes)]
                   (let [ix (aget current-ixes i)]
                     (aset current-los i (aget los ix))
                     (aset current-his i (aget his ix))))))
  (read-bounds [this current]
               (let [current-ixes (aget constraint->ixes current)
                     current-los (aget constraint->los current)
                     current-his (aget constraint->his current)
                     changed? false]
                 (dotimes [i (alength current-ixes)]
                   (let [ix (aget current-ixes i)
                         new-lo (aget current-los i)
                         new-hi (aget current-his i)]
                     (when (val-lt (aget los ix) new-lo)
                       (set!! changed? true)
                       (aset los ix new-lo))
                     (when (val-lt new-hi (aget his ix))
                       (set!! changed? true)
                       (aset his ix new-hi))
                     ;; TODO need a better way to indicate failure
                     (when (or (identical? greatest (aget los ix))
                               (identical? least (aget his ix))
                               (val-lt (aget his ix) (aget los ix)))
                       (set! failed? true))))
                 changed?))
  (split [this]
         (debug :splitting-left los his)
         (apush pushed-los (aclone los))
         (apush pushed-his (aclone his))
         (set! depth (+ depth 1))
         (loop [splitter 0]
           (if (< splitter (alength constraints))
             (do
               (.write-bounds this splitter)
               (.split-left (aget constraints splitter) (aget constraint->los splitter) (aget constraint->his splitter))
               (if (true? (.read-bounds this splitter))
                 (do
                   (apush pushed-splitters splitter)
                   (debug :split-left los his splitter))
                 (recur (+ splitter 1))))
             (assert false "Can't split anything!"))))
  (backtrack [this]
             (set! los (.pop pushed-los))
             (set! his (.pop pushed-his))
             (set! depth (- depth 1))
             (set! failed? false)
             (let [splitter (.pop pushed-splitters)]
               (debug :backtracked los his splitter)
               (.write-bounds this splitter)
               (debug :splitting-right los his splitter)
               (.split-right (aget constraints splitter) (aget constraint->los splitter) (aget constraint->his splitter))
               (.read-bounds this splitter)
               (debug :split-right los his splitter failed?)))
  (next [this]
        (debug :next los his pushed-los pushed-his)
        (loop [current 0
               last-changed (- (alength constraints) 1)]
          (debug :next-loop los his current last-changed failed?)
          (if (true? failed?)
            (when (> depth 0)
              (.backtrack this)
              (recur current (- (alength constraints) 1)))
            (do
              (.write-bounds this current)
              (debug :propagating current (aget constraint->los current) (aget constraint->his current))
              (.propagate (aget constraints current) (aget constraint->los current) (aget constraint->his current))
              (debug :propagated current (aget constraint->los current) (aget constraint->his current))
              (if (true? (.read-bounds this current))
                (recur (mod (+ current 1) (alength constraints)) current)
                (if (== current last-changed)
                  (if (key= los his)
                    (do
                      (set! failed? true) ;; force solver to backtrack next time
                      (aclone los))
                    (do
                      (.split this current)
                      (recur 0 (- (alength constraints) 1))))
                  (recur (mod (+ current 1) (alength constraints)) last-changed))))))))

(defn solver [num-vars constraints constraint->ixes]
  (let [constraint->los (amake [i (alength constraint->ixes)]
                               (make-array (alength (aget constraint->ixes i))))
        constraint->his (amake [i (alength constraint->ixes)]
                               (make-array (alength (aget constraint->ixes i))))
        los (least-key num-vars)
        his (greatest-key num-vars)]
    (Solver. constraints constraint->ixes constraint->los constraint->his los his 0 #js [] #js [] #js [] false)))

;; TESTS

(defn gen-key [key-len]
  (gen/fmap into-array (gen/vector (gen/one-of [gen/int gen/string-ascii]) key-len)))

(defn least-prop [key-len]
  (prop/for-all [key (gen-key key-len)]
                (and (key-lt (least-key key-len) key)
                     (key-lte (least-key key-len) key)
                     (key-gt key (least-key key-len))
                     (key-gte key (least-key key-len)))))

(defn greatest-prop [key-len]
  (prop/for-all [key (gen-key key-len)]
                (and (key-gt (greatest-key key-len) key)
                     (key-gte (greatest-key key-len) key)
                     (key-lt key (greatest-key key-len))
                     (key-lte key (greatest-key key-len)))))

(defn equality-prop [key-len]
  (prop/for-all [key-a (gen-key key-len)
                 key-b (gen-key key-len)]
                (= (key= key-a key-b)
                   (and (key-lte key-a key-b) (not (key-lt key-a key-b)))
                   (and (key-gte key-a key-b) (not (key-gt key-a key-b))))))

(defn reflexive-prop [key-len]
  (prop/for-all [key (gen-key key-len)]
                (and (key-lte key key) (key-gte key key) (not (key-lt key key)) (not (key-gt key key)))))

(defn transitive-prop [key-len]
  (prop/for-all [key-a (gen-key key-len)
                 key-b (gen-key key-len)
                 key-c (gen-key key-len)]
                (and (if (and (key-lt key-a key-b) (key-lt key-b key-c)) (key-lt key-a key-c) true)
                     (if (and (key-lte key-a key-b) (key-lte key-b key-c)) (key-lte key-a key-c) true)
                     (if (and (key-gt key-a key-b) (key-gt key-b key-c)) (key-gt key-a key-c) true)
                     (if (and (key-gte key-a key-b) (key-gte key-b key-c)) (key-gte key-a key-c) true))))

(defn anti-symmetric-prop [key-len]
  (prop/for-all [key-a (gen-key key-len)
                 key-b (gen-key key-len)]
                (and (not (and (key-lt key-a key-b) (key-lt key-b key-a)))
                     (not (and (key-gt key-a key-b) (key-gt key-b key-a))))))

(defn total-prop [key-len]
  (prop/for-all [key-a (gen-key key-len)
                 key-b (gen-key key-len)]
                (and (or (key-lt key-a key-b) (key-gte key-a key-b))
                     (or (key-gt key-a key-b) (key-lte key-a key-b)))))

;; fast gens with no shrinking and no long strings. good enough for government work

(defn make-simple-key-elem [rnd size]
  (let [value (gen/rand-range rnd (- size) size)]
    (if (pprng/boolean rnd)
      value
      (str value))))

(defn make-simple-key [rnd size key-len]
  (let [result #js []]
    (dotimes [_ key-len]
      (.push result (make-simple-key-elem rnd size)))
    result))

(defn gen-assoc [key-len]
  (gen/make-gen
   (fn [rnd size]
     (let [key (make-simple-key rnd size key-len)
           val (make-simple-key rnd size key-len)]
       [[:assoc! key key] nil]))))

(defn gen-dissoc [key-len]
  (gen/make-gen
   (fn [rnd size]
     (let [key (make-simple-key rnd size key-len)]
       [[:dissoc! key] nil]))))

(defn gen-action [key-len]
  (gen/make-gen
   (fn [rnd size]
     (let [key (make-simple-key rnd size key-len)
           val (make-simple-key rnd size key-len)]
       (if (pprng/boolean rnd)
         [[:assoc! key val] nil]
         [[:dissoc! key] nil])))))

(defn apply-to-tree [tree actions]
  (doseq [action actions]
    (case (nth action 0)
      :assoc! (.assoc! tree (nth action 1) (nth action 2))
      :dissoc! (.dissoc! tree (nth action 1)))
    #_(do
      (prn action)
      (.pretty-print tree)
      (prn tree)
      (.valid! tree)))
  tree)

(defn apply-to-sorted-map [map actions]
  (reduce
   (fn [map action]
     (case (nth action 0)
       :assoc! (assoc map (nth action 1) (nth action 2))
       :dissoc! (dissoc map (nth action 1))))
   map actions))

(defn run-building-prop [min-keys key-len actions]
  (let [tree (apply-to-tree (tree min-keys key-len) actions)
        sorted-map (apply-to-sorted-map (sorted-map-by key-compare) actions)]
    (and (= (seq (map vec tree)) (seq sorted-map))
         (.valid! tree))))

(defn building-prop [gen key-len]
  (prop/for-all [min-keys gen/s-pos-int
                 actions (gen/vector (gen key-len))]
                (run-building-prop min-keys key-len actions)))

(defn run-lookup-prop [min-keys key-len actions action]
  (let [tree (apply-to-tree (tree min-keys key-len) actions)
        sorted-map (apply-to-sorted-map (sorted-map-by key-compare) actions)
        tree-result (case (nth action 0)
                      :assoc! (.assoc! tree (nth action 1) (nth action 2))
                      :dissoc! (.dissoc! tree (nth action 1)))
        sorted-map-result (contains? sorted-map (nth action 1))]
    (= tree-result sorted-map-result)))

(defn lookup-prop [gen key-len]
  (prop/for-all [min-keys gen/s-pos-int
                 actions (gen/vector (gen key-len))
                 action (gen key-len)]
                (run-lookup-prop min-keys key-len actions action)))

(defn gen-movement [key-len]
  (gen/make-gen
   (fn [rnd size]
     (let [key (make-simple-key rnd size key-len)]
       (if (pprng/boolean rnd)
         [[:seek-gt key] nil]
         [[:seek-gte key] nil])))))

(defn apply-to-iterator [iterator movements]
  (for [movement movements]
    (case (nth movement 0)
      :seek-gt (.seek-gt iterator (nth movement 1))
      :seek-gte (.seek-gte iterator (nth movement 1)))))

(defn apply-to-elems [elems movements]
  (let [cur-elems (atom elems)]
    (for [movement movements]
      (case (nth movement 0)
        :seek-gt (do
                   (reset! cur-elems (drop-while #(key-lte (nth % 0) (nth movement 1)) elems))
                   (first (first @cur-elems)))
        :seek-gte (do
                    (reset! cur-elems (drop-while #(key-lt (nth % 0) (nth movement 1)) elems))
                    (first (first @cur-elems)))))))

(defn run-iterator-prop [min-keys key-len actions movements]
  (let [tree (apply-to-tree (tree min-keys key-len) actions)
        sorted-map (apply-to-sorted-map (sorted-map-by key-compare) actions)
        iterator-results (apply-to-iterator (iterator tree) movements)
        elems-results (apply-to-elems (seq sorted-map) movements)]
    #_(.pretty-print tree)
    (= iterator-results elems-results)))

(defn iterator-prop [key-len]
  (prop/for-all [min-keys gen/s-pos-int
                 actions (gen/vector (gen-action key-len))
                 movements (gen/vector (gen-movement key-len))]
                (run-iterator-prop min-keys key-len actions movements)))

(defn run-self-join-prop [min-keys key-len actions movements]
  (let [tree (apply-to-tree (tree min-keys key-len) actions)
        iterator-results (apply-to-iterator (iterator tree) movements)
        join-results (apply-to-iterator
                      (join #js [(iterator tree) (iterator tree)]
                            key-len
                            #js [(into-array (repeat key-len true)) (into-array (repeat key-len true))]
                            #js [(into-array (repeat key-len true)) (into-array (repeat key-len true))])
                      movements)]
    (= (map (fn [[b k]] [b (vec k)]) iterator-results)
       (map (fn [[b k]] [b (vec k)]) join-results))))

(defn self-join-prop [key-len]
  (prop/for-all [min-keys gen/s-pos-int
                 actions (gen/vector (gen-action key-len))
                 movements (gen/vector (gen-movement key-len))]
                (run-self-join-prop min-keys key-len actions movements)))

(defn run-product-join-prop [min-keys key-len actions movements]
  (let [product-tree (tree min-keys key-len)
        tree (apply-to-tree (tree min-keys key-len) actions)
        elems (iterator->keys (iterator tree))
        _ (dotimes [i (alength elems)]
            (dotimes [j (alength elems)]
              (.assoc! product-tree (.concat (aget elems i) (aget elems j)) nil)))
        iterator-results (apply-to-iterator (iterator product-tree) movements)
        join-results (apply-to-iterator
                      (join #js [(iterator tree) (iterator tree)]
                            (* 2 key-len)
                            #js [(into-array (concat (repeat key-len true) (repeat key-len false)))
                                 (into-array (concat (repeat key-len false) (repeat key-len true)))]
                            #js [(into-array (concat (repeat key-len true) (repeat key-len false)))
                                 (into-array (concat (repeat key-len false) (repeat key-len true)))])
                      movements)]
    (= (map (fn [[b k]] [b (vec k)]) iterator-results)
       (map (fn [[b k]] [b (vec k)]) join-results))))

(defn product-join-prop [key-len]
  (prop/for-all [min-keys gen/s-pos-int
                 actions (gen/vector (gen-action key-len))
                 movements (gen/vector (gen-movement key-len))]
                (run-product-join-prop min-keys key-len actions movements)))

(comment
  (dc/quick-check 1000 (least-prop 1))
  (dc/quick-check 1000 (least-prop 2))
  (dc/quick-check 1000 (greatest-prop 1))
  (dc/quick-check 1000 (greatest-prop 2))
  (dc/quick-check 1000 (equality-prop 1))
  (dc/quick-check 1000 (equality-prop 2))
  (dc/quick-check 1000 (reflexive-prop 1))
  (dc/quick-check 1000 (reflexive-prop 2))
  (dc/quick-check 1000 (transitive-prop 1))
  (dc/quick-check 1000 (transitive-prop 2))
  (dc/quick-check 1000 (anti-symmetric-prop 1))
  (dc/quick-check 1000 (anti-symmetric-prop 2))
  (dc/quick-check 1000 (total-prop 1))
  (dc/quick-check 1000 (total-prop 2))
  (dc/quick-check 10000 (building-prop gen-assoc 1))
  (dc/quick-check 10000 (building-prop gen-action 1))
  (dc/quick-check 10000 (lookup-prop gen-action 1))
  (dc/quick-check 10000 (iterator-prop 1))
  (dc/quick-check 10000 (self-join-prop 1))
  (dc/quick-check 10000 (self-join-prop 2))
  (dc/quick-check 10000 (self-join-prop 3))
  (dc/quick-check 10000 (product-join-prop 1))
  (dc/quick-check 10000 (product-join-prop 2))
  (dc/quick-check 10000 (product-join-prop 3))

  (defn f []
    (time
     (let [tree (tree 100)]
       (dotimes [i 500000]
         (.assoc! tree #js [i i i] (* 2 i))))))

  (time (dotimes [_ 10] (f)))

  (defn g []
    (time
     (let [tree (tree 100)]
       (dotimes [i 500000]
         (.assoc! tree (if (even? i) #js [i i i] #js [(str i) (str i) (str i)]) (* 2 i))))))

  (time (dotimes [_ 10] (g)))

  (defn h []
    (time
     (let [tree (tree 100)]
       (dotimes [i 500000]
         (.assoc! tree #js [(js/Math.sin i) (js/Math.cos i) (js/Math.tan i)] (* 2 i))))))

  (time (dotimes [_ 10] (h)))

  (do
    (def samples (gen/sample (gen/tuple gen/s-pos-int (gen/vector gen-action) (gen/vector gen-movement)) 100))
    (def trees (for [[min-keys actions _] samples]
                 (apply-to-tree (tree min-keys) actions)))
    (def benches (mapv vector trees (map #(nth % 2) samples)))
    (time
     (doseq [[tree movements] benches]
       (apply-to-iterator (iterator tree) movements))))

  (let [tree1 (tree 10)
        _ (dotimes [i 10000]
            (let [i (+ i 0)]
              (.assoc! tree1 #js [i (+ i 1) (+ i 2)] (* 2 i))))
        tree2 (tree 10)
        _ (dotimes [i 1000]
            (let [i (+ i 1)]
              (.assoc! tree2 #js [i (+ i 2)] (* 2 i))))
        tree3 (tree 10)
        _ (dotimes [i 100000]
            (let [i (+ i 2)]
              (.assoc! tree3 #js [(+ i 1) (+ i 2)] (* 2 i))))
        ]
     (let [s (solver 3 #js [(contains (iterator tree1)) (contains (iterator tree2)) (contains (iterator tree3))] #js [#js [0 1 2] #js [0 2] #js [1 2]])]
       (while (not (nil? (.next s))))))

  (let [tree1 (tree 10)
          _ (dotimes [i 100000]
              (let [i (+ i 0)]
                (.assoc! tree1 #js [i i i] (* 2 i))))
          tree2 (tree 10)
          _ (dotimes [i 100000]
              (let [i (+ i 100000)]
                (.assoc! tree2 #js [i i i] (* 2 i))))
          tree3 (tree 10)
          _ (dotimes [i 100000]
              (let [i (+ i 50000)]
                (.assoc! tree3 #js [i i i] (* 2 i))))
          ]
      (time
       (dotimes [i 100]
         (let [j (join #js [(iterator tree1) (iterator tree2) (iterator tree3)] 3 #js [#js [true true true] #js [true true true] #js [true true true]])]
           (iterator->keys j)))))

  (let [tree (tree 10)
        _ (dotimes [i 10]
            (let [i (+ i 0)]
              (.assoc! tree #js [i i] (* 2 i))))
        j (time (join #js [(iterator tree) (iterator tree)] 3 #js [#js [true true false]
                                                                   #js [false true true]]))
        ]
    (alength (time (iterator->keys j)))
    )

  (let [tree (tree 10)
        _ (dotimes [i 10]
            (let [i (+ i 0)]
              (.assoc! tree #js [i i] (* 2 i))))
        j (time (join #js [(iterator tree) (iterator tree) (iterator tree)] 6 #js [#js [true false false true false false]
                                                                                   #js [false true false false true false]
                                                                                   #js [false false true false false true]]))
        ]
    (alength (time (iterator->keys j)))
  )

  (let [tree (tree 10)
        _ (dotimes [i 10]
            (let [i (+ i 0)]
              (.assoc! tree #js [i i] (* 2 i))))
        j (time (join #js [(iterator tree) (iterator tree) (iterator tree)] 6 #js [#js [true false false false false true]
                                                                                   #js [false true false false true false]
                                                                                   #js [false false true true false false]]))
        ]
    (alength (time (iterator->keys j)))
  )

  (let [tree1 (tree 10)
      _ (.assoc! tree1 #js ["a" "b"] 0)
      _ (.assoc! tree1 #js ["b" "c"] 0)
      _ (.assoc! tree1 #js ["c" "d"] 0)
      _ (.assoc! tree1 #js ["d" "b"] 0)
      tree2 (tree 10)
      _ (.assoc! tree2 #js ["b" "a"] 0)
      _ (.assoc! tree2 #js ["c" "b"] 0)
      _ (.assoc! tree2 #js ["d" "c"] 0)
      _ (.assoc! tree2 #js ["b" "d"] 0)
      s (solver 3
                #js [(contains (iterator tree1))
                     (contains (iterator tree2))]
                #js [#js [0 2]
                     #js [1 2]])
      ]
  [(.next s) (.next s) (.next s)]
  )

  (let [tree1 (tree 10)
      _ (.assoc! tree1 #js ["a" "b"] 0)
      _ (.assoc! tree1 #js ["b" "c"] 0)
      _ (.assoc! tree1 #js ["c" "d"] 0)
      _ (.assoc! tree1 #js ["d" "b"] 0)
      s (solver 4
                #js [(contains (iterator tree1))
                     (contains (iterator tree1))]
                #js [#js [0 1]
                     #js [2 3]])
      ]
  (take 100 (take-while identity (repeatedly #(.next s))))
  )

  (let [tree1 (tree 10)
      _ (.assoc! tree1 #js ["a" "b"] 0)
      _ (.assoc! tree1 #js ["b" "c"] 0)
      _ (.assoc! tree1 #js ["c" "d"] 0)
      _ (.assoc! tree1 #js ["d" "b"] 0)
      tree2 (tree 10)
      _ (.assoc! tree2 #js ["b" "a"] 0)
      _ (.assoc! tree2 #js ["c" "b"] 0)
      _ (.assoc! tree2 #js ["d" "c"] 0)
      _ (.assoc! tree2 #js ["b" "d"] 0)
      s (solver 3
                #js [(contains (iterator tree1))
                     (contains (iterator tree2))]
                #js [#js [0 2]
                     #js [1 2]])
      ]
  [(.next s) (.next s) (.next s) (.next s) (.next s)]
  )

 )

(enable-console-print!)

(let [tree1 (tree 10 3)
      _ (dotimes [i 10]
          (.assoc! tree1 #js [i i i]))
      tree2 (tree 10 2)
      _ (dotimes [i 10]
          (.assoc! tree2 #js [i i]))
      tree3 (tree 10 2)
      _ (dotimes [i 10]
          (.assoc! tree3 #js [i i]))
      s (solver 3
                #js [(contains (iterator tree1))
                     (contains (iterator tree2))
                     (contains (iterator tree3))]
                #js [#js [0 1 2]
                     #js [0 2]
                     #js [1 2]])
      ]
  (perf-time
   (dotimes [i 2]
     (.next s)
     )))
