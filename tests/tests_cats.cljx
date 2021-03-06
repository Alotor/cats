(ns tests-cats
  #+cljs
  (:require-macros [cemerick.cljs.test
                    :refer (is deftest with-test run-tests testing test-var)]
                   [cats.core :refer (mlet with-context lift)])
  #+cljs
  (:require [cemerick.cljs.test :as ts]
            [cats.core :as m]
            [cats.types :as t])
  #+clj
  (:require [clojure.test :refer :all]
            [cats.core :as m :refer [mlet with-context lift]]
            [cats.types :as t]))


(deftest common
  (testing "Basic maybe operations."
    (is (= 1 (t/from-maybe (t/just 1))))
    (is (= nil (t/from-maybe (t/nothing))))))

(deftest test-mlet
  (testing "It supports regular let inside its bindings")
    (is (= (t/just 2)
           (mlet [i (t/just 1)
                  :let [i (inc i)]]
                 (m/return i))))
  (testing "It supports :when guards inside its bindings")
    (is (= (t/nothing)
           (mlet [i (t/just 2)
                  :when (> i 2)]
                 (m/return i))))
    (is (= [3 4 5]
           (mlet [i [1 2 3 4 5]
                  :when (> i 2)]
                 (m/return i))))
  (testing "The body runs in an implicit do"
    (is (= (t/just 3)
           (mlet [i (t/just 2)
                  :let [x (inc i)]]
                 (assert (= x 3))
                 (m/return x))))))

(deftest test-sequence
  (testing "It works with vectors"
    (is (= (m/sequence [[1 2] [3 4]])
           [[1 3] [1 4] [2 3] [2 4]])))
  (testing "It works with lazy seqs"
    (is (= (m/sequence [(lazy-seq [1 2]) (lazy-seq [3 4])])
           '([1 3] [1 4] [2 3] [2 4]))))
  (testing "It works with sets"
    (is (= (m/sequence [#{1 2} #{3 4}])
           #{[1 3] [1 4] [2 3] [2 4]})))
  (testing "It works with Maybe values"
    (is (= (m/sequence [(t/just 2) (t/just 3)])
           (t/just [2 3])))
    (is (= (m/sequence [(t/just 2) (t/nothing)])
           (t/nothing)))))

(deftest test-mapseq
  (testing "It works with maybe values"
    (is (= (m/mapseq t/just [1 2 3 4 5])
           (t/just [1 2 3 4 5])))
    (is (= (t/nothing)
           (m/mapseq (fn [v]
                        (if (odd? v)
                          (t/just v)
                          (t/nothing)))
                      [1 2 3 4 5])))))

(deftest test-lift
  (let [monad+ (lift 2 +)]
    (testing "It can lift a function to the vector monad"
      (is (= [1 2 3 4 5 6]
             (monad+ [0 2 4] [1 2]))))
    (testing "It can lift a function to the Maybe monad"
      (is (= (t/just 6)
             (monad+ (t/just 2) (t/just 4))))
      (is (= (t/nothing)
             (monad+ (t/just 1) (t/nothing)))))))

(deftest test-filter
  (testing "It can filter Maybe monadic values"
    (let [bigger-than-4 (partial < 4)]
      (is (= (t/just 6)
             (m/filter bigger-than-4 (t/just 6))))
      (is (= (t/nothing)
             (m/filter bigger-than-4 (t/just 3))))))
  (testing "It can filter vectors"
    (is (= [1 3 5]
           (m/filter odd? [1 2 3 4 5 6])))))

(deftest test-when
  (testing "It returns the monadic value unchanged when the condition is true"
    (is (= (t/just 3)
           (m/when true (t/just 3)))))
  (testing "It returns nil in the monadic context when the condition is false"
    (is (= [nil]
           (m/when false [])))))

(deftest test-maybe
  (testing "Test predicates"
    (let [m1 (t/just 1)]
      (is (t/maybe? m1))
      (is (t/just? m1))))

  (testing "Test fmap"
    (let [m1 (t/just 1)
          m2 (t/nothing)]
      (is (= (m/fmap inc m1) (t/just 2)))
      (is (= (m/fmap inc m2) (t/nothing)))))

  (testing "The first monad law: left identity"
    (is (= (t/just 2)
           (with-context (t/just 0)
             (m/>>= (m/return 2) t/just)))))

  (testing "The second monad law: right identity"
    (is (= (t/just 2)
           (m/>>= (t/just 2) m/return))))

  (testing "The third monad law: associativity"
    (is (= (m/>>= (mlet [x  (t/just 2)
                         y  (t/just (inc x))]
                        (m/return y))
                  (fn [y] (t/just (inc y))))
           (m/>>= (t/just 2)
                  (fn [x] (m/>>= (t/just (inc x))
                                (fn [y] (t/just (inc y))))))))))

(deftest test-continuation-monad
  (let [cont-42 (t/continuation (fn [c] (c 42)))
        inc-cont-fn (fn [x]
                      (t/continuation (fn [c] (c (inc x)))))]

    (testing "The first monad law: left identity"
      (is (= (m/run-cont cont-42)
             (m/run-cont
               (with-context (t/continuation #())
                 (m/>>= (m/return 42)
                        (fn [v] (t/continuation (fn [c] c v)))))))))

    (testing "The second monad law: right identity"
      (is (= (m/run-cont cont-42)
             (m/run-cont
               (m/>>= cont-42 m/return)))))

    (testing "The third monad law: associativity"
      (is (= (m/>>= (mlet [x  cont-42
                           y  inc-cont-fn]
                         (m/return y))
                         inc-cont-fn))
             (m/>>= cont-42
                    (fn [x] (m/>>= (t/continuation (fn [c] (c (inc x))))
                                   inc-cont-fn)))))

    (testing "call-cc allows the creation of resumable computations."
      (let [cc (atom nil)]
        (is (= 44
               (m/run-cont (mlet [x cont-42
                                  y (m/call-cc (fn [k]
                                                 (reset! cc k)
                                                 (k 2)))]
                                 (m/return (+ x y))))))
        (is (= 45
               (m/run-cont (@cc 3))))
        (is (= 46
               (m/run-cont (@cc 4))))))))

(deftest test-lazy-seq
  (let [s (lazy-seq [2])
        val->lazyseq (fn [x] (lazy-seq [x]))]
    (testing "The first monad law: left identity"
      (is (= s
             (with-context s
               (m/>>= (m/return 2)
                      val->lazyseq)))))

    (testing "The second monad law: right identity"
      (is (= s
             (m/>>= s
                    m/return))))

    (testing "The third monad law: associativity"
      (is (= (m/>>= (mlet [x  s
                           y  (val->lazyseq (inc x))]
                          (m/return y))
                    (fn [y] (val->lazyseq (inc y))))
             (m/>>= s
                    (fn [x] (m/>>= (val->lazyseq (inc x))
                                  (fn [y] (val->lazyseq (inc y)))))))))))

(deftest test-vector
  (testing "The first monad law: left identity"
    (is (= [1 2 3 4 5]
           (m/>>= [0 1 2 3 4]
                  (fn [x] [(inc x)])))))
  (testing "The second law: right identity"
    (is (= [1 2 3]
           (m/>>= [1 2 3]
                  m/return))))
  (testing "The third law: associativity"
    (is (= (m/>>= (mlet [x [1 2 3 4 5]
                         y [(inc x)]]
                        (m/return y))
                  (fn [z] [(inc z)]))

           (m/>>= [1 2 3 4 5]
                  (fn [x] (m/>>= [(inc x)]
                                (fn [y] [(inc y)]))))))))


(deftest state-monad
  (testing "get-state should return the identity."
    (let [computation (m/get-state)]
      (is (= :foo (m/exec-state computation :foo)))))

  (testing "swap-state should should apply function to state and return it."
    (let [computation (m/swap-state inc)]
      (is (= 2 (m/exec-state computation 1)))))

  (testing "State monad compositio with mlet should return state"
    (let [res (mlet [s (m/get-state)]
                (m/return (inc s)))]
      (is (t/state? res))
      (let [res (m/run-state res 1)]
        (is (t/pair? res))
        (is (= 2 (first res)))
        (is (= 1 (second res)))))))



