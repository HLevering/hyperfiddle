(ns contrib.ui
  (:refer-clojure :exclude [keyword long])
  (:require
    [cats.core :as cats]
    [contrib.css :refer [css]]
    [contrib.data :refer [orp update-existing]]
    [contrib.pprint :refer [pprint-str]]
    [contrib.reactive :as r]
    [contrib.reader]
    [contrib.string :refer [blank->nil]]
    [contrib.try$ :refer [try-either]]
    [contrib.ui.codemirror :refer [-codemirror]]
    [contrib.ui.tooltip :refer [tooltip]]
    [contrib.ui.remark :as remark]
    [goog.functions :as functions]
    [re-com.core :as re-com]
    [reagent.core :as reagent]
    [taoensso.timbre :as timbre]))


(def default-debounce-ms 350)

(comment
  ; component-did-update
  (for [[bs os] [[["aaa" nil "xxx"] ["aaa" nil]]
                 [["aaa"] ["aaa"]]
                 [["bbb"] ["aaa"]]]
        b bs]
    (let [[l r] (split-with #(not= % b) os)]
      {:b b
       :os os
       :discards l
       :rest r}))
  ; =>
  {:b "aaa", :os ["aaa" nil], :discards (), :rest ("aaa" nil)}
  {:b nil, :os ["aaa" nil], :discards ("aaa"), :rest (nil)}
  {:b "xxx", :os ["aaa" nil], :discards ("aaa" nil), :rest ()}
  {:b "aaa", :os ["aaa"], :discards (), :rest ("aaa")}
  {:b "bbb", :os ["aaa"], :discards ("aaa"), :rest ()})

(let [debounce (memoize functions/debounce)
      debounced-adapter (fn [os-ref f n]
                          (let [o (last (:old-values @os-ref))]
                            (swap! os-ref update :old-values conj n)
                            (when f (f o n))))
      on-change (fn [os-ref n]                              ; letfn not working #470
                  (swap! os-ref assoc :value n)
                  n)]
  (defn debounced [props comp & args]
    (let [os-ref (r/atom {:value (:value props)
                          :old-values [(:value props)]})]
      (reagent/create-class
        {:reagent-render
         (fn [props comp & args]
           (let [props (-> (if-some [value @(r/cursor os-ref [:value])]
                             (assoc props :value value)
                             (dissoc props :value))
                           (dissoc :debounce/interval)
                           (update :on-change (fn [f]
                                                (let [f (debounce (r/partial debounced-adapter os-ref f)
                                                                  (or (:debounce/interval props) default-debounce-ms))]
                                                  (r/comp f (r/partial on-change os-ref))))))]
             (into [comp props] args)))
         :component-did-update
         (fn [this]
           (let [[_ props comp & args] (reagent/argv this)
                 b (:value props)
                 {os :old-values a :value} @os-ref]
             (let [[discards rest] (split-with #(not= % b) os)]
               (if (empty? rest)
                 (do
                   (when (< 1 (count discards))
                     ; this is either not a big deal, e.g. default values have been applied
                     ; or multiple users are editing the same value and changes are probably being lost
                     (timbre/warn "Potential conflicting concurrent edits detected, discarding local state" {:a a :b b :os os}))
                   (reset! os-ref {:old-values [b] :value b}))
                 (swap! os-ref assoc :old-values (vec rest))))))}))))

(let [on-change (fn [os-ref f n]                            ; letfn not working #470
                  (let [o (last (:old-values @os-ref))]
                    (swap! os-ref (fn [m]
                                    (-> (assoc m :value n)
                                        (update :old-values conj n))))
                    (when f (f o n))))]
  (defn optimistic-updates [props comp & args]
    (let [os-ref (r/atom {:value (:value props)
                          :old-values [(:value props)]})]
      (reagent/create-class
        {:reagent-render
         (fn [props comp & args]
           (let [props (-> props
                           (assoc :value @(r/cursor os-ref [:value]))
                           (update :on-change #(r/partial on-change os-ref %)))]
             (into [comp props] args)))
         :component-did-update
         (fn [this]
           (let [[_ props comp & args] (reagent/argv this)
                 b (:value props)
                 {os :old-values a :value} @os-ref]
             (let [[discards rest] (split-with #(not= % b) os)]
               (if (empty? rest)
                 (do
                   (when (< 1 (count discards))
                     ; this is either not a big deal, e.g. default values have been applied
                     ; or multiple users are editing the same value and changes are probably being lost
                     (timbre/warn "Potential conflicting concurrent edits detected, discarding local state" {:a a :b b :os os}))
                   (reset! os-ref {:old-values [b] :value b}))
                 (swap! os-ref assoc :old-values (vec rest))))))}))))

(let [on-change (fn [f state parse-string new-s-value]
                  (swap! state assoc :s-value new-s-value)
                  (->> (try-either
                         (let [new-value (parse-string new-s-value)] ; todo this should be atomic, but we still want to throw
                           (swap! state assoc :last-valid-value new-value)
                           new-value))
                       (cats/fmap f))
                  nil)
      initial-state-val (fn [to-string props]
                          {:s-value (to-string (:value props))
                           :last-valid-value (:value props)})
      on-blur (fn [state f e]
                (f (:last-valid-value @state)))]
  (defn validated-cmp [props parse-string to-string cmp & args]
    (let [state (r/atom (initial-state-val to-string props))]
      (reagent/create-class
        {:reagent-render
         (fn [props parse-string to-string cmp & args]
           (let [s-value @(r/cursor state [:s-value])
                 props (-> (assoc props :value s-value)
                           (assoc :is-invalid (or (try (parse-string s-value) false (catch :default e true))
                                                  (:is-invalid props)))
                           (update-existing :on-blur (fn [f]
                                                       (r/partial on-blur state f)))
                           (update :on-change (fn [f]
                                                (r/partial on-change f state parse-string)))
                           (dissoc :magic-new-mode))]
             (into [cmp props] args)))
         :component-did-update
         (fn [this]
           (let [[_ props parse-string to-string cmp & args] (reagent/argv this)]
             (when-not (:magic-new-mode props)              ; https://github.com/hyperfiddle/hyperfiddle/issues/586
               (when-not (= (:last-valid-value @state) (:value props))
                 (reset! state (initial-state-val to-string props))))))}))))

(let [target-value (fn [e] (.. e -target -value))]          ; letfn not working #470
  (defn textarea [props]
    [:textarea (update-existing props :on-change r/comp target-value)]))

(let [checked (fn [e] (.. e -target -checked))]             ; letfn not working #470
  (defn checkbox [props]
    [:input (-> (assoc props :type "checkbox")
                (update-existing :on-change r/comp checked))]))

(let [target-value (fn [e] (.. e -target -value))]          ; letfn not working #470
  (defn text [props]
    [:input (-> (assoc props :type "text")
                (dissoc :is-invalid)
                (cond-> (:is-invalid props) (update :class css "invalid"))
                (update-existing :on-change r/comp target-value))]))

(let [parse-string (fn [s]                                  ; letfn not working #470
                     (let [v (some-> s contrib.reader/read-edn-string!)]
                       (assert (or (nil? v) (keyword? v)))
                       v))
      to-string (fn [v] (some-> v pr-str))]
  (defn keyword [props]
    [validated-cmp props parse-string to-string text]))

(let [parse-string (fn [s] (some-> s contrib.reader/read-edn-string!)) ; letfn not working #470
      to-string (fn [v] (some-> v pr-str))]
  (defn edn [props]
    [validated-cmp props parse-string to-string text]))

(let [parse-string (fn [s]                                  ; letfn not working #470
                     (when-let [s (blank->nil s)]
                       (let [v (js/parseInt s 10)]
                         (assert (integer? v))
                         v)))]
  (defn long [props]
    [validated-cmp props parse-string str text]))

(defn easy-checkbox [props & [label]]
  (let [control [checkbox props]]
    (if (blank->nil label)
      [:label (-> props
                  (assoc :style {:font-weight "400"})
                  (dissoc :on-change :checked))
       control " " label]
      control)))

(defn ^:export easy-checkbox-boolean [label r & [props]]
  [easy-checkbox (assoc props
                   :checked (boolean @r)
                   :on-change (r/partial swap! r not))
   label])

(defn ^:export code [props]                                 ; Adapt props to codemirror props
  (let [defaults {:lineNumbers true
                  :matchBrackets true
                  :autoCloseBrackets true
                  :viewportMargin js/Infinity}
        props (-> props
                  (assoc :read-only (:disabled props))      ; (if (:disabled props) "nocursor" false) -- nocursor disables copy/paste
                  (dissoc :disabled))
        props (into defaults props)]
    ; There is nothing to be done about invalid css down here.
    ; You'd have to write CodeMirror implementation-specific css.
    [-codemirror props]))

(defn ^:export code-inline-block [props]
  (let [showing? (r/atom false)]
    (fn [props]
      [:div.truncate.code-inline-block
       [re-com/popover-anchor-wrapper
        :showing? showing?
        :position :below-center
        :anchor [:a {:href "javascript:void 0;" :on-click (r/partial swap! showing? not)}
                 [:span (when (:is-invalid props) {:class "invalid"})
                  (orp seq (:value props) (:default-value props) "-")]]
        :popover [re-com/popover-content-wrapper
                  :close-button? true
                  :on-cancel (r/partial reset! showing? false)
                  :no-clip? true
                  :width "600px"
                  :body (code props)]]])))

(defn ^:export cm-edn [props]
  [validated-cmp (assoc props :mode "clojure") contrib.reader/read-edn-string! pprint-str code])

(defn ^:export cm-edn-inline-block [props]
  [validated-cmp (assoc props :mode "clojure") contrib.reader/read-edn-string! pprint-str code-inline-block])

(def ^:export markdown (remark/remark!))

(let [on-change (fn [value e]
                  ; ideally use e -target -value, but that is not very useful since it would require string serialization
                  value)]
  (defn radio [props]
    [:input (-> (assoc props :type "radio")
                (update-existing :value str)
                (update-existing :on-change r/comp (r/partial on-change (:value props))))]))

(defn radio-with-label [props]
  [tooltip {:label (:tooltip props)}
   [:label.radio-option {:class (if (:disabled props) "disabled")}
    [radio (-> props
               (dissoc :tooltip :label)
               (update :style merge {:width "auto"}))]
    (or (:label props) (str (:value props)))]])
