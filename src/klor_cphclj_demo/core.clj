(ns klor-cphclj-demo.core
  (:require
   [clojure.string :as str]
   [klor.core :refer :all]
   [klor.runtime :refer [play-role]]
   [klor.simulator :refer [simulate-chor]]
   [klor.sockets :refer [with-server with-accept with-client wrap-sockets]]))

(def ip
  "127.0.0.1")

;;; * Starting Out
;;;
;;; <https://github.com/lovrosdu/klor-cphclj-demo>
;;;
;;; - Defining a choreography
;;; - Typed DSL
;;; - Location polymorphism
;;; - Role expressions
;;; - Projection via macroexpansion
;;; - Concurrent execution
;;; - Simulator

(defchor simple-print-1 [A] (-> A) []
  (A (println "Hello world!")))

(comment
  @(simulate-chor simple-print-1)
  )

(defchor simple-print-2 [A B] (-> B) []
  (A (println "Hello!"))
  (B (println "World!"))
  (B 123))

(comment
  @(simulate-chor simple-print-2)
  )

;;; * Communicating
;;;
;;; - Communication basics
;;; - Calling Clojure

(defchor simple-move [A B] (-> A B) [x]
  (A->B x))

(comment
  @(simulate-chor simple-move 42)
  )

(defchor remote-inc-1 [A B] (-> A B) [x]
  (B (inc (A->B x))))

(comment
  @(simulate-chor remote-inc-1 5)
  )

(defchor remote-inc-2 [A B] (-> A A) [x]
  (B->A (B (inc (A->B x)))))

(comment
  @(simulate-chor remote-inc-2 5)
  )

(defchor remote-invoke [A B] (-> B A A) [f x]
  (B->A (f (A->B x))))

(comment
  @(simulate-chor remote-invoke inc 42)
  )

(defchor remote-apply [A B] (-> B A A) [f xs]
  (B->A (B (apply f (A->B xs)))))

(comment
  @(simulate-chor remote-apply + [1 2 3])
  )

;;; * RPC
;;;
;;; - Instantiating choreographies
;;; - Escaping the simulator
;;; - Serialization

(defchor rpc [A B] (-> A A A) [name xs]
  (remote-apply [A B] (B @(resolve (A->B name))) xs))

(defn my+ [& args]
  (apply + args))

(comment
  @(simulate-chor rpc 'my+ [1 2 3])
  )

(def rpc-port 7889)

(comment
  (def rpc-server
    (future
      (with-server [ssc {:port rpc-port}]
        (loop []
          (println "Listening on" (str (.getLocalAddress ssc)))
          (with-accept [ssc sc]
            (println "Got client" (str (.getRemoteAddress sc)))
            (play-role (wrap-sockets {:role 'B} {'A sc} :log true)
                       rpc))
          (recur)))))

  (with-client [sc {:host ip :port rpc-port}]
    (println "Connected to server" (str (.getRemoteAddress sc)))
    (play-role (wrap-sockets {:role 'A} {'B sc} :log true)
               rpc 'my+ [1 2 3]))
  )

;;; * Sharing Knowledge
;;;
;;; - Agreement types
;;; - Conditionals and knowledge of choice

(defchor simple-copy [A B] (-> A #{A B}) [x]
  (A=>B x))

(comment
  @(simulate-chor simple-copy 42)
  @(simulate-chor simple-move 42)
  )

(defchor together-1 [A B] (-> #{A B}) []
  5)

(defchor together-2 [A B] (-> #{A B}) []
  (lifting [A B] 5))

(defchor together-3 [A B] (-> #{A B}) []
  (println "Hello world"))

(comment
  @(simulate-chor together-1)
  @(simulate-chor together-2)
  @(simulate-chor together-3)
  )

(defchor inc-locally-1 [A B] (-> B) []
  (B (inc (narrow [B] (A=>B (A 5))))))

(defchor inc-locally-2 [A B] (-> B) []
  (B (inc (A=>B (A 5)))))

(comment
  @(simulate-chor inc-locally-1)
  @(simulate-chor inc-locally-2)
  )

(defchor maybe-inc [A B] (-> A B) [x]
  (if (A=>B (A (= (rand-int 2) 0)))
    (B (inc (A->B x)))
    (B (println "Nothing!"))))

(comment
  @(simulate-chor maybe-inc 42)
  )

(defchor remote-map [A B] (-> B A A) [f xs]
  (if (A=>B (A (empty? xs)))
    (A nil)
    (A (cons (remote-invoke [A B] f (first xs))
             (remote-map [A B] f (next xs))))))

(comment
  @(simulate-chor remote-map inc [1 2 3])
  )

;;; * Authentication
;;;
;;; - Live redefinition

(defn ask [prompt]
  (print prompt)
  (flush)
  (read-line))

(defchor auth [C A] (-> C #{C A}) [get-creds]
  (or (A=>C (A (= (:password (C->A (get-creds))) "secret")))
      (and (C=>A (C (read-string (ask "Continue? "))))
           (auth [C A] get-creds))))

(defchor get-token [C S A] (-> C C) [get-creds]
  (if (A=>S (auth [C A] get-creds))
    (S->C (S (random-uuid)))
    (C :error)))

(def get-token-ports
  {:C->S 7890
   :A->S 7891
   :C->A 7892})

(comment
  (def get-token-server
    (future
      (with-server [ssc1 {:port (get-token-ports :A->S)}
                    ssc2 {:port (get-token-ports :C->S)}]
        (println "Listening on" (str (.getLocalAddress ssc1)))
        (with-accept [ssc1 sc1]
          (println "Got authenticator" (str (.getRemoteAddress sc1)))
          (loop []
            (println "Listening on" (str (.getLocalAddress ssc2)))
            (with-accept [ssc2 sc2]
              (println "Got client" (str (.getRemoteAddress sc2)))
              (play-role (wrap-sockets {:role 'S} {'A sc1 'C sc2} :log true)
                         get-token))
            (recur))))))

  (def get-token-authenticator
    (future
      (with-client [sc1 {:host ip :port (get-token-ports :A->S)}]
        (println "Connected to server" (str (.getRemoteAddress sc1)))
        (with-server [ssc {:port (get-token-ports :C->A)}]
          (loop []
            (println "Listening on" (str (.getLocalAddress ssc)))
            (with-accept [ssc sc2]
              (println "Got client" (str (.getRemoteAddress sc2)))
              (play-role (wrap-sockets {:role 'A} {'S sc1 'C sc2} :log true)
                         get-token))
            (recur))))))

  (with-client [sc1 {:host ip :port (get-token-ports :C->S)}
                sc2 {:host ip :port (get-token-ports :C->A)}]
    (println "Connected to server" (str (.getRemoteAddress sc1)))
    (println "Connected to authenticator" (str (.getRemoteAddress sc2)))
    (play-role (wrap-sockets {:role 'C} {'S sc1 'A sc2} :log true)
               get-token #(hash-map :password (str/trim (ask "PW: ")))))
  )

;;; * Multiple Values
;;;
;;; - Choreographic tuples
;;; - Forced agreement

(defn modpow [base exp mod]
  (.modPow (biginteger base) (biginteger exp) (biginteger mod)))

(defchor exchange-key-1 [A B] (-> #{A B} #{A B} A B [A B]) [g p sa sb]
  (pack (A (modpow (B->A (B (modpow g sb p))) sa p))
        (B (modpow (A->B (A (modpow g sa p))) sb p))))

(comment
  ;; <https://en.wikipedia.org/wiki/Diffie–Hellman_key_exchange>
  @(simulate-chor exchange-key-1 5 23 4 3)
  )

(defchor secure [A B] (-> A B) [x]
  (unpack [[k1 k2] (exchange-key-1 [A B] 5 23 (A 4) (B 3))]
    (B (.xor k2 (A->B (A (.xor k1 (biginteger x))))))))

(comment
  @(simulate-chor secure 42)
  )

(defchor exchange-key-2 [A B] (-> #{A B} #{A B} A B #{A B}) [g p sa sb]
  (agree! (A (modpow (B->A (B (modpow g sb p))) sa p))
          (B (modpow (A->B (A (modpow g sa p))) sb p))))

(comment
  ;; <https://en.wikipedia.org/wiki/Diffie–Hellman_key_exchange>
  @(simulate-chor exchange-key-2 5 23 4 3)
  )

(def secure-port 7893)

(comment
  (def secure-server
    (future
      (with-server [ssc {:port secure-port}]
        (loop []
          (println "Listening on" (str (.getLocalAddress ssc)))
          (with-accept [ssc sc]
            (println "Got client" (str (.getRemoteAddress sc)))
            (println "Got number"
                     (play-role (wrap-sockets {:role 'B} {'A sc} :log true)
                                secure)))
          (recur)))))

  (with-client [sc {:host ip :port secure-port}]
    (println "Connected to server" (str (.getRemoteAddress sc)))
    (play-role (wrap-sockets {:role 'A} {'B sc} :log true)
               secure (Long/parseLong (ask "Enter a number: "))))
  )

;;; * Higher-Order
;;;
;;; - Choreographies as values
;;; - Type inference
;;; - Auxiliary roles

(defchor compose [A B C] (-> (-> B C) (-> A B) (-> A C | B)) [g f]
  (chor (-> A C) [x] (g (f x))))

(defchor compose-test [A B C] (-> A C) [x]
  ((compose [A B C]
            (chor (-> B C) [x] (B->C (B (+ x 10))))
            (chor (-> A B) [x] (A->B (A (* x 10)))))
   x))

(comment
  @(simulate-chor compose-test 41)
  )

;;; * Tic-Tac-Toe

(def ttt-syms
  '[x o])

(def ttt-none
  '_)

(def ttt-lines
  (concat (for [i (range 3)] (for [j (range 3)] [i j]))
          (for [i (range 3)] (for [j (range 3)] [j i]))
          [(for [i (range 3)] [i i])]
          [(for [i (range 3)] [i (- 3 i 1)])]))

(defn ttt-board []
  (vec (repeat 3 (vec (repeat 3 ttt-none)))))

(defn ttt-place [board loc sym]
  (when (= (get-in board loc) ttt-none) (assoc-in board loc sym)))

(defn ttt-free [board]
  (for [i (range 3) j (range 3)
        :let [loc [i j]]
        :when (= (get-in board loc) ttt-none)]
    loc))

(defn ttt-winner-on [board locs]
  (let [syms (distinct (map #(get-in board %) locs))]
    (when (= (count syms) 1) (first syms))))

(defn ttt-winner [board]
  (or (some (set ttt-syms) (map #(ttt-winner-on board %) ttt-lines))
      (when (empty? (ttt-free board)) :draw)))

(defn ttt-fmt [board]
  (str/join "\n" (map #(str/join " " %) board)))

(defn ttt-index [board]
  (for [i (range 3)]
    (for [j (range 3)
          :let [loc [i j]
                sym (get-in board loc)]]
      (if (= sym ttt-none) (+ (* i 3) j 1) sym))))

(defn ttt-pick [board]
  (let [n (do (println (format "Make a move [1-9] or quit [0]:"))
              (Long/parseLong (read-line)))]
    (if (zero? n) :quit [(quot (dec n) 3) (mod (dec n) 3)])))

(defn ttt-show [board]
  (println (str "\n" (ttt-fmt (ttt-index board)))))

(defchor ttt-play [A B] (-> #{A B} #{A B} #{A B}) [board idx]
  (A (ttt-show board))
  (if-let [winner (ttt-winner board)]
    winner
    (let [loc (A=>B (A (ttt-pick board)))]
      (if (= loc :quit)
        :quit
        (let [board' (ttt-place board loc (get ttt-syms idx))]
          (A (ttt-show board'))
          (ttt-play [B A] board' (- 1 idx)))))))

(defchor ttt-start [A B] (-> #{A B}) []
  (ttt-play [A B] (ttt-board) 0))

(def ttt-port 7894)

(comment
  (def ttt-server
    (future
      (with-server [ssc {:port ttt-port}]
        (loop []
          (println "Listening on" (str (.getLocalAddress ssc)))
          (with-accept [ssc sc]
            (println "Got client" (str (.getRemoteAddress sc)))
            (play-role (wrap-sockets {:role 'A} {'B sc} :log true) ttt-start))
          (recur)))))

  (with-client [sc {:host ip :port ttt-port}]
    (println "Connected to" (str (.getRemoteAddress sc)))
    (play-role (wrap-sockets {:role 'B} {'A sc} :log true) ttt-start))
  )
