(ns nexus.async-dispatch-test
  (:require [cljs.test :refer [deftest is testing async]]
            [nexus.core :as nexus]))

(deftest async-dispatch-sees-stale-state-test
  "Demonstrates that dispatch called from promise .then() sees stale state.

   Expected behavior: Actions should see current state when dispatched.
   Actual behavior: Actions see state from when the original dispatch started,
   not state updated by intervening dispatches."
  (async done
    (let [;; Setup
          store (atom {:counter 0})
          observed-state (atom nil)

          ;; Track what state the action sees
          nexus-config
          {:nexus/system->state deref

           :nexus/actions
           {:actions/read-counter
            (fn [state]
              (reset! observed-state (:counter state))
              [])}

           :nexus/effects
           {:effects/save
            (fn [_ store path v]
              (swap! store assoc-in path v))

            ;; This effect stores dispatch and calls it from a promise
            :effects/async-then-dispatch
            (fn [{:keys [dispatch]} _store delay-ms on-success]
              (-> (js/Promise. (fn [resolve] (js/setTimeout resolve delay-ms)))
                  (.then (fn [_] (dispatch on-success)))))}}

          ;; Function to dispatch synchronously (simulates another event)
          sync-dispatch! (fn [actions]
                           (nexus/dispatch nexus-config store {} actions))]

      ;; Step 1: Start async effect that will dispatch [:actions/read-counter] after 50ms
      (nexus/dispatch nexus-config store {}
        [[:effects/async-then-dispatch 50 [[:actions/read-counter]]]])

      ;; Step 2: While promise is pending, update state via another dispatch
      (sync-dispatch! [[:effects/save [:counter] 42]])

      ;; Verify state is actually updated
      (is (= 42 (:counter @store)) "Store should be updated immediately")

      ;; Step 3: Wait for async dispatch to complete
      (js/setTimeout
        (fn []
          ;; BUG: Action sees counter=0 (stale) instead of counter=42 (current)
          ;; If Nexus captured fresh state, @observed-state would be 42
          (is (= 42 @observed-state)
              "Action dispatched from .then() should see current state (42), not stale state (0)")
          (done))
        1000))))
