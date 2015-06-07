(ns eulalie.sqs
  (:require [camel-snake-kebab.core :as csk]
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
    :actions  [:list "ActionName" #{:enum}]}
   :change-message-visibility-batch
   {:entries [:list "ChangeMessageVisibilityBatchRequestEntry"]}
   :create-queue
   {:attrs   [:kv   "Attribute" "Name" "Value" #{:enum}]}
   :delete-message-batch
   {:entries [:map  "DeleteMessageBatchRequestEntry"]}
   :get-queue-attributes
   {:attrs   [:list "AttributeName" #{:enum}]}
   :set-queue-attributes
   {:attrs   [:list "Attribute" #{:enum}]}
   :receive-message
   {:meta   [:list "AttributeName" #{:enum}]
    :attrs  [:list "MessageAttributeName"]}})

(defmulti  prepare-body (fn [target req] target))
(defmethod prepare-body :default [_ req] req)

(defmethod prepare-body :get-queue-url [_ body]
  (set/rename-keys body {:queue-owner-aws-account-id
                         "QueueOwnerAWSAccountId"}))

(defmethod prepare-body :create-queue [_ {:keys [policy] :as body}]
  (cond-> body
    policy (assoc :policy (json/encode policy))))

(defn prepare-message-attrs [a-name a-type a-value]
  (let [data-type ({:string :String
                    :number :Number
                    :binary :Binary} a-type a-type)
        value-type ({:Number :String} data-type data-type)]
    {"Name" a-name
     "Value.DataType" data-type
     (str "Value." (name value-type) "Value") a-value}))

(defmethod prepare-body :send-message [_ {:keys [attrs] :as body}]
  ;; The API is pretty messed up
  (conj body
        (q/map-list->map
         "MessageAttribute"
         (for [[a-name [a-type a-value]] attrs]
           (prepare-message-attrs a-name a-type a-value)))))

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

(defmulti  restructure-response (fn [target body] target))
(defmethod restructure-response :default [_ body] body)

(defn attributes->map [body & [{:keys [parent] :or {parent :attribute}}]]
  (into {}
    (for [attr (x/children body parent)]
      [(-> attr
           (x/child-content :name)
           csk/->kebab-case-keyword)
       (x/child-content attr :value)])))

(defmethod restructure-response :get-queue-attributes [_ body]
  (attributes->map body))
(defmethod restructure-response :send-message [_ body]
  (x/child-content->map body {:message-id :id :md-5-of-message-body :md5}))

(defn message-attributes [message]
  (into {}
    (for [attr (x/children message :message-attribute)]
      (let [a-name (x/child-content attr :name)
            {[type value] :value} (x/child attr :value)
            type (keyword (x/child-content type :data-type))
            in-type ({:Binary :binary
                      :String :string
                      :Number :number} type type)]
        [(keyword a-name) [in-type (-> value vals ffirst)]]))))

(defn restructure-message [message]
  (-> message
      ;; Meta/AWS-level attributes
      (conj (attributes->map message))
      (conj (x/child-content->map
             message
             {:body :body :md-5-of-body :md5
              :receipt-handle :receipt :message-id :id}))
      ;; User-defined attributes
      (assoc :attrs (message-attributes message))))

(defmethod restructure-response :receive-message [_ body]
  (for [message (x/children body :message)]
    (restructure-message message)))

(def enum-keys-out #{})

(defrecord SQSService [endpoint version max-retries]
  eulalie/AmazonWebService

  (prepare-request [service {:keys [target] :as req}]
    (let [{:keys [body] :as req} (q/prepare-query-request service req)]
      (assoc req :body
             (as-> body %
               (q/expand-sequences  % (target->seq-spec target))
               (q/translate-enums   % enum-keys-out)
               (prepare-body target %)))))

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
