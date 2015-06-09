(ns eulalie.sqs
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk-extras]
            [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.set :as set]
            [eulalie]
            [eulalie.service-util :as service-util]
            [eulalie.sign :as sign]
            [eulalie.util :as util]
            [eulalie.util.query :as q]
            [eulalie.util.xml   :as x]))

(def target->seq-spec
  {:add-permission
   {:accounts [:list "AWSAccountId"]
    :actions  [:list :action-name]}
   :change-message-visibility-batch
   {:messages [:maps :change-message-visibility-batch-request-entry]}
   :create-queue
   {:attrs   [:kv :attribute :name :value]}
   :delete-message-batch
   {:messages [:maps :delete-message-batch-request-entry]}
   :get-queue-attributes
   {:attrs   [:list :attribute-name]}
   :receive-message
   {:meta   [:list :attribute-name]
    :attrs  [:list :message-attribute-name]}})

(def enum-keys-out
  #{:action-name
    :attribute-name
    #(and (= (first %) :attribute) (= (last %) :name))})

(defmulti  prepare-body (fn [target req] target))
(defmethod prepare-body :default [_ req] req)

(defmethod prepare-body :get-queue-url [_ body]
  ;; The case deviates from the other keys
  (set/rename-keys body {:queue-owner-aws-account-id
                         "QueueOwnerAWSAccountId"}))

(defn nested-json-out [m]
  (->> m
       (csk-extras/transform-keys csk/->camelCaseString)
       json/encode))

(defmethod prepare-body :create-queue
  [_ {{:keys [policy redrive-policy] :as attrs} :attrs :as body}]
  (assoc body
         :attrs
         (cond-> attrs
           policy (assoc :policy (nested-json-out policy))
           redrive-policy (assoc :redrive-policy
                                 (nested-json-out redrive-policy)))))

(defn prepare-message-attrs [a-name a-type a-value]
  (let [data-type ({:string :String
                    :number :Number
                    :binary :Binary} a-type a-type)
        value-type ({:Number :String} data-type data-type)]
    {:name a-name
     [:value :data-type] data-type
     [:value (str (name value-type) "Value")] a-value}))

(defn message-attrs->dotted [attrs]
  (q/map-list->dotted
   :message-attribute
   (for [[a-name [a-type a-value]] attrs]
     (prepare-message-attrs a-name a-type a-value))))

(defmethod prepare-body :send-message [_ {:keys [attrs] :as body}]
  (conj body (message-attrs->dotted attrs)))

(defn prepare-batch-message [{:keys [attrs] :as message}]
  (conj message (message-attrs->dotted attrs)))

(defmethod prepare-body :send-message-batch [_ {:keys [messages] :as body}]
  (conj body
        (q/map-list->dotted
         :send-message-batch-request-entry
         (map prepare-batch-message messages))))

(defmethod prepare-body :set-queue-attributes [_ {:keys [name value] :as body}]
  (assoc body
         [:attribute :name] name
         [:attribute :value] (cond-> value
                               (= name :policy) nested-json-out
                               (= name :redrive-policy) nested-json-out)))

(defmulti  restructure-response (fn [target body] target))
(defmethod restructure-response :default [_ body] body)

(defn attributes->map [body & [{:keys [parent] :or {parent :attribute}}]]
  (into {}
    (for [attr (x/children body parent)]
      [(-> attr
           (x/child-content :name)
           csk/->kebab-case-keyword)
       (x/child-content attr :value)])))

(defn nested-json-in [s]
  (csk-extras/transform-keys csk/->kebab-case-keyword (json/decode s true)))

(defmethod restructure-response :get-queue-attributes [_ body]
  (let [{:keys [policy redrive-policy] :as attrs} (attributes->map body)]
    (cond-> attrs
      policy (assoc :policy (nested-json-in policy))
      redrive-policy (assoc :redrive-policy (nested-json-in redrive-policy)))))

(defmethod restructure-response :send-message [_ body]
  (x/child-content->map body {:message-id :id :md-5-of-message-body :md5}))

(defn message-attr->kv [attr]
  (let [a-name (x/child-content attr :name)
        {[type value] :value} (x/child attr :value)
        type (keyword (x/child-content type :data-type))
        in-type ({:Binary :binary
                  :String :string
                  :Number :number} type type)]
    [(keyword a-name) [in-type (x/content value)]]))

(defn message-attrs->map [message]
  (into {}
    (for [attr (x/children message :message-attribute)]
      (message-attr->kv attr))))

(defn restructure-message [message]
  (-> message
      ;; Meta/AWS-level attributes
      (conj (attributes->map message))
      (conj (x/child-content->map
             message
             {:body :body :md-5-of-body :md5
              :receipt-handle :receipt :message-id :id}))
      ;; User-defined attributes
      (assoc :attrs (message-attrs->map message))
      (dissoc :message)))

(defmethod restructure-response :receive-message [_ body]
  (for [message (x/children body :message)]
    (restructure-message message)))

(defn children->id-set [node children-of]
  (->> (x/children node children-of)
       (map #(x/child-content % :id))
       (into #{})))

(defn children-by-attr [parent unique-attr attrs]
  (->> (map #(x/child-content->map % attrs) parent)
       (group-by unique-attr)
       (util/mapvals first)))

(defn restructure-failed-batch [body]
  (children-by-attr
   (x/children body :batch-result-error-entry)
   :id #{:code :id :message :sender-fault}))

(defmethod restructure-response :delete-message-batch [_ body]
  {:succeeded
   (children->id-set body :delete-message-batch-result-entry)
   :failed
   (restructure-failed-batch body)})

(defn restructure-successful-sends [body]
  (children-by-attr
   (x/children body :send-message-batch-result-entry)
   :batch-id
   {:id :batch-id :message-id :id :md-5-of-message-attributes :attr-md5
    :md-5-of-message-body :body-md5}))

(defn restructure-failed-sends [body]
  (children-by-attr
   (x/child body :batch-result-error-entry)))

(defmethod restructure-response :send-message-batch [_ body]
  {:succeeded
   (restructure-successful-sends body)
   :failed
   (restructure-failed-batch body)})

(defmethod restructure-response :change-message-visibility-batch [_ body]
  {:succeeded
   (children->id-set body :change-message-visibility-batch-result-entry)
   :failed
   (restructure-failed-batch body)})

(let [target->elem
      {:create-queue  [:one :queue-url]
       :get-queue-url [:one :queue-url]
       :list-queues   [:many :queue-url]
       :list-dead-letter-source-queues [:many :queue-url]}]
  (defn extract-response-value [target resp]
    (if-let [[tag elem] (target->elem target)]
      (case tag
        :one  (x/child-content resp elem)
        :many (map x/content (x/children resp elem)))
      resp)))

(defrecord SQSService [endpoint version max-retries]
  eulalie/AmazonWebService

  (prepare-request [service {:keys [target] :as req}]
    (let [{:keys [body] :as req} (q/prepare-query-request service req)]
      (assoc req :body
             (as-> body %
               (prepare-body target %)
               (q/expand-sequences  % (target->seq-spec target))
               (q/translate-enums   % enum-keys-out)))))

  (transform-request [_ body]
    (-> body q/format-query-request url/map->query))

  (transform-response [_ body]
    ;; FIXME we want the request also here, for target
    (let [elem   (x/string->xml-map body)
          [tag]  (keys elem)
          target (keyword (util/to-first-match (name tag) "-response"))]
      (->> elem
           (extract-response-value target)
           (restructure-response target))))

  (transform-response-error [_ {:keys [body] :as resp}]
    (x/parse-xml-error body))

  (request-backoff [_ retry-count error]
    (service-util/default-retry-backoff retry-count error))

  (sign-request [_ req]
    (sign/aws4-sign "sqs" req)))

(def service
  (SQSService.
   (url/url "https://sqs.us-east-1.amazonaws.com")
   "2012-11-05"
   3))