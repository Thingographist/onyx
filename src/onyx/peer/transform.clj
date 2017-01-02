(ns ^:no-doc onyx.peer.transform
  (:require [onyx.types :refer [->Result ->Results]]
            [taoensso.timbre :refer [tracef trace]]
            [onyx.protocol.task-state :refer :all]
            [clj-tuple :as t]))

(defn collect-next-segments [f input]
  (let [segments (try (f input)
                      (catch Throwable e
                        (ex-info "Segment threw exception"
                                 {:exception e :segment input})))]
    (if (sequential? segments) segments (t/vector segments))))

(defn apply-fn-single [f {:keys [onyx.core/batch] :as event}]
  (assoc
   event
   :onyx.core/results
    (->Results (doall
                 (map
                   (fn [leaf]
                     (->Result leaf (collect-next-segments f leaf)))
                   batch))
               nil
               nil)))

(defn collect-next-segments-batch [f input]
  (try (f input)
       (catch Throwable e
         (mapv (fn [segment] 
                 (ex-info "Batch threw exception"
                          {:exception e 
                           :segment segment}))
               input))))

(defn apply-fn-batch [f {:keys [onyx.core/batch] :as event}]
  (let [batch-results (collect-next-segments-batch f batch)] 
    (when-not (= (count batch-results) (count batch))
      (throw (ex-info ":onyx/batch-fn? functions must return the same number of elements as its input argment."
                      {:input-elements batch
                       :output-elements batch-results
                       :task (:onyx/name (:onyx.core/task-map event))})))
    (assoc
     event
     :onyx.core/results
     (->Results (doall
                 (map
                  (fn [leaf output]
                    (let [segments (if (sequential? output) output (t/vector output))]
                      (->Result leaf segments)))
                  batch
                  batch-results))
                nil
                nil))))

(defn curry-params [f params]
  (reduce partial f params))

(defn apply-fn [state]
  (-> state
      (set-event! (let [event (get-event state) 
                        f (:onyx.core/fn event)
                        g (curry-params f (:onyx.core/params event))
                        rets ((:apply-fn event) g event)]
                    (tracef "[%s / %s] Applied fn to %s segments, returning %s new segments"
                            (:onyx.core/id rets)
                            (:onyx.core/lifecycle-id rets)
                            (count (:onyx.core/batch event))
                            (count (mapcat :leaves (:tree (:onyx.core/results rets)))))
                    rets))
      (advance)))
