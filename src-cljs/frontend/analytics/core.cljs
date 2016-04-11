(ns frontend.analytics.core
  (:require [frontend.analytics.segment :as segment]
            [frontend.analytics.common :as common-analytics]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.utils :refer [merror]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.intercom :as intercom]
            [frontend.utils.vcs-url :as vcs-url]
            [schema.core :as s]
            [om.core :as om :include-macros true]
            [goog.style]
            [goog.string :as gstr]))

(def CoreAnalyticsEvent
  {:event-type s/Keyword
   (s/optional-key :properties) (s/maybe {s/Keyword s/Any})})

(defn analytics-event-schema
  ([] (analytics-event-schema {}))
  ([schema]
   (s/conditional :owner (merge schema CoreAnalyticsEvent {:owner s/Any})
                  :current-state (merge schema CoreAnalyticsEvent {:current-state {s/Any s/Any}}))))

(def AnalyticsEvent
  (analytics-event-schema))

(def PageviewEvent
  (analytics-event-schema {:navigation-point s/Keyword}))

(def ExternalClickEvent
  (analytics-event-schema {:event s/Keyword}))

(def BuildEvent
  (analytics-event-schema {:build {s/Keyword s/Any}}))

;; Below are the lists of our supported events.
;; Events should NOT be view specific. They should be view agnostic and
;; include a view in the properties.
;; Add new events here and keep each list of event types sorted alphabetically
(def supported-click-and-impression-events
  ;; These are the click and impression events.
  ;; They are in the fomat <item>-<clicked or impression>.
  #{:add-more-containers-clicked
    :beta-accept-terms-clicked
    :beta-join-clicked
    :beta-leave-clicked
    :branch-clicked
    :build-insights-upsell-clicked
    :build-insights-upsell-impression
    :build-timing-upsell-clicked
    :build-timing-upsell-impression
    :change-image-clicked
    :cancel-plan-clicked
    :insights-bar-clicked
    :login-clicked
    :new-plan-clicked
    :no-plan-banner-impression
    :oauth-authorize-clicked
    :parallelism-clicked
    :pr-link-clicked
    :project-clicked
    :project-settings-clicked
    :revision-link-clicked
    :select-plan-clicked
    :set-up-junit-clicked
    :signup-clicked
    :signup-impression
    :start-trial-clicked
    :update-plan-clicked})

(def supported-controller-events
  ;; These are the api response events.
  ;; They are in the format of <object>-<action take in the past tense>
  #{:project-branch-changed
    :project-builds-stopped
    :project-followed
    :project-unfollowed})

(def SupportedEvents
  (apply s/enum
         (concat supported-click-and-impression-events
                 supported-controller-events)))

(defn- add-properties-to-track-from-state [current-state]
  "Get a map of the mutable properties we want to track out of the
  state. Also add a timestamp."
  {:user (get-in current-state state/user-login-path) 
   :view (get-in current-state state/current-view-path)
   :repo (get-in current-state state/navigation-repo-path)
   :org (get-in current-state state/navigation-org-path)})

(defn- add-properties-to-track-from-owner [owner]
  "Get a map of the mutable properties we want to track out of the
  owner."
  (let [app-state @(om/get-shared owner [:_app-state-do-not-use])]
    (add-properties-to-track-from-state app-state)))

(defn- supplement-tracking-properties-from-owner [props owner]
  "Fill in any unsuppplied property values with those supplied
  in the app state via the owner."
  (-> owner
      (add-properties-to-track-from-owner)
      (merge props)))

(defn- supplement-tracking-properties-from-state [props current-state]
  "Fill in any unsuppplied property values with those supplied
  in the current app state."
  (-> current-state
      (add-properties-to-track-from-state)
      (merge props)))

(defn build-properties [build]
  (merge {:running (build-model/running? build)
          :build-num (:build_num build)
          :repo (vcs-url/repo-name (:vcs_url build))
          :org (vcs-url/org-name (:vcs_url build))
          :oss (boolean (:oss build))
          :outcome (:outcome build)}
         (when (:stop_time build)
           {:elapsed_hours (/ (- (.getTime (js/Date.))
                                 (.getTime (js/Date. (:stop_time build))))
                              1000 60 60)})))

(defn merror-unsupported-event [event]
  (merror "Cannot log unsupported event type "event", please add it to the list of supported events"))

(defmulti track (fn [data]
                  (when (frontend.config/analytics-enabled?)
                    (:event-type data))))

(defn- supplement-tracking-properties [{:keys [properties owner current-state]}]
  (if owner
    (supplement-tracking-properties-from-owner properties owner)
    (supplement-tracking-properties-from-state properties current-state)))

(s/defmethod track :default [{:keys [event-type :- SupportedEvents properties owner current-state] :as event-data :- AnalyticsEvent}]
    (segment/track-event event-type (supplement-tracking-properties {:properties properties
                                                                     :owner owner
                                                                     :current-state current-state})))

(s/defmethod track :external-click [event-data :- ExternalClickEvent]
  (let [{:keys [event properties owner current-state]} event-data]
    (if (supported-click-and-impression-events (:event event-data))
      (segment/track-external-click event (supplement-tracking-properties {:properties properties
                                                                           :owner owner
                                                                           :current-state current-state}))
      (merror-unsupported-event (:event event-data)))))

(s/defmethod track :pageview [event-data :- PageviewEvent]
  (let [{:keys [navigation-point properties owner current-state]} event-data]
    (segment/track-pageview navigation-point (supplement-tracking-properties {:properties properties
                                                                              :owner owner
                                                                              :current-state current-state}))))

(s/defmethod track :build-triggered [event-data :- BuildEvent]
  (let [{:keys [build properties owner current-state]} event-data
        props (merge {:project (vcs-url/project-name (:vcs_url build))
                      :build-num (:build_num build)
                      :retry? true}
                     properties)]
    (segment/track-event :build-triggered (supplement-tracking-properties {:properties properties
                                                                           :owner owner
                                                                           :current-state current-state}))))

(s/defmethod track :view-build [event-data :- BuildEvent]
  (let [{:keys [build properties owner current-state]} event-data
        props (merge (build-properties build) properties)
        user (get-in current-state state/user-path)]
    (segment/track-event :view-build (supplement-tracking-properties {:properties properties
                                                                      :owner owner
                                                                      :current-state current-state}))
    (when (and (:oss build) (build-model/owner? build user))
      (intercom/track :viewed-self-triggered-oss-build
                      {:vcs-url (vcs-url/project-name (:vcs_url build))
                       :outcome (:outcome build)}))))

(defn- get-user-properties-from-state [current-state]
  (let [analytics-id (get-in current-state state/user-analytics-id-path)
        user-data (get-in current-state state/user-path)]
    {:id analytics-id
     :user-properties (select-keys user-data (keys common-analytics/UserProperties))}))

(s/defmethod track :init-user [event-data :- AnalyticsEvent]
  (segment/identify (get-user-properties-from-state (:current-state event-data))))
