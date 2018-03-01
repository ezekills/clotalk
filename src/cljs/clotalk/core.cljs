(ns clotalk.core
  (:require [reagent.core :as r]
            [secretary.core :as secretary :include-macros true]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType]
            [markdown.core :refer [md->html]]
            [clotalk.ajax :refer [load-interceptors!]]
            [ajax.core :refer [GET POST]]
            [clotalk.chat :as chat]
            [clojure.string :as s]
            [clotalk.websockets :as ws])

  (:import goog.History))

;; -------------------------
;; Atoms
(defonce session (r/atom {:page
                          :chat
                          :user-name ""
                          :message-input ""
                          :local-chat-history []}))

(def signin-focus? (r/atom true))

(defn update-messages! [{:keys [message]}]
  (println message)
  (swap! session update-in [:local-chat-history] conj message))

; chat impl
(defn send-message! [user-name message-text]
  (def message {:user-name user-name :message message-text :ts (.getTime (js/Date.) :state "send")})
  (ws/send-transit-msg! {:message message}))

(defn reset-key! [key val]
  (swap! session assoc key val))

(defn nav-link [uri title page]
  [:li.nav-item
   {:class (when (= page (:page @session)) "active")}
   [:a.nav-link {:href uri} title]])

(defn navbar []
  [:nav.navbar.navbar-dark.bg-primary.navbar-expand-md
   {:role "navigation"}
   [:button.navbar-toggler.hidden-sm-up
    {:type "button"
     :data-toggle "collapse"
     :data-target "#collapsing-navbar"}
    [:span.navbar-toggler-icon]]
   [:a.navbar-brand {:href "#/"} "clotalk"]
   [:div#collapsing-navbar.collapse.navbar-collapse
    [:ul.nav.navbar-nav.mr-auto
     [nav-link "#/" "Home" :home]
     [nav-link "#/chat" "Chat" :chat]
     [nav-link "#/about" "About" :about]]]])

(defn about-page []
  [:div.container
   [:div.row
    [:div.col-md-12
     [:img {:src (str js/context "/img/warning_clojure.png")}]]]])

(defn home-page []
  [:div.container
   (when-let [docs (:docs @session)]
     [:div.row>div.col-sm-12
      [:div {:dangerouslySetInnerHTML
             {:__html (md->html docs)}}]])])

(defn user-name-input [in-focus]
  [:div.input-group
   [:div.input-group-prepend
    [:span.input-group-text "@"]]
   [:input.form-control {:type "text"
                         :value (:user-name @session)
                         :placeholder "What's your name?"
                         :aria-label "user-name"
                         :aria-describedby "basic-addon1"
                         :on-change #(swap! session assoc :user-name (-> % .-target .-value))
                         :on-key-press #(if (and (= 13 (.-charCode %)) (not (s/blank? (:user-name @session))))
                                          (swap! in-focus not))}]])

(defn message-input []
  [:div.input-group
   [:input.form-control {:type "text"
                         :value (:message-input @session)
                         :placeholder "Message"
                         :aria-label "Message"
                         :aria-describedby "basic-addon2"
                         :on-change #(swap! session assoc :message-input (-> % .-target .-value))
                         :on-key-press #(if (and (= 13 (.-charCode %)) (not (s/blank? (:message-input @session))))
                                           (do
                                             (send-message! (:user-name @session) (:message-input @session))
                                             (reset-key! :message-input "")
                                             (println "history" (:local-chat-history @session))))}]
   [:div.input-group-append
    [:button.btn.btn-outline-secondary {:type "button"
                                        :on-click #(if (not (s/blank? (:message-input @session)))
                                                     (do
                                                       (send-message! (:user-name @session) (:message-input @session))
                                                       (reset-key! :message-input "")
                                                       (println "history" (:local-chat-history @session))))}
     [:i.fas.fa-paper-plane]]]])

(defn avatar [name]
  [:div.card-subtitle [:small "@" name]])
  ;[:div [:span.badge.badge-primary name]])

(defn message-entry [message]
   ^{:key (get message :ts)} ;unique key to make react faster
   [:div.card.w-75.my-2
    {:class (if (= (:user-name @session) (get message :user-name))
             "float-left"
             "float-right")}
    [:div.card-body.d-inline.p-2.bg-primary.text-white.rounded
     {:class (if (= (:user-name @session) (get message :user-name))
              "bg-primary"
              "bg-secondary")}
     (get message :message)
     (avatar (get message :user-name))]])

(defn chat-history [local-chat-history]
  [:div
   (doall (map #(message-entry %) local-chat-history))]) ;wrapped in doall due to deref not supported in lazy seq

(defn chat-page []
  [:div.container
   [:div.row
    [:div.col-sm-12.card
     [:div.card-body
      [:div.scroll-box
       (chat-history (:local-chat-history @session))]
      (if @signin-focus?
        (user-name-input signin-focus?)
        (message-input))]]]])

(def pages
  {:home #'home-page
   :chat #'chat-page
   :about #'about-page})

(defn page []
  [(pages (:page @session))])

;; -------------------------
;; Routes
(secretary/set-config! :prefix "#")

(secretary/defroute "/" []
                    (swap! session assoc :page :home))

(secretary/defroute "/chat" []
                    (swap! session assoc :page :chat))

(secretary/defroute "/about" []
                    (swap! session assoc :page :about))

;; -------------------------
;; History
;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
     HistoryEventType/NAVIGATE
     (fn [event]
       (secretary/dispatch! (.-token event))))
    (.setEnabled true)))

;; -------------------------
;; Initialize app
(defn fetch-docs! []
  (GET "/docs" {:handler #(swap! session assoc :docs %)}))

(defn mount-components []
  (r/render [#'navbar] (.getElementById js/document "navbar"))
  (r/render [#'page] (.getElementById js/document "app")))

(defn start-websocket []
  (ws/make-websocket! (str "ws://" (.-host js/location) "/ws") update-messages!))

(defn init! []
  (start-websocket)
  (load-interceptors!)
  (fetch-docs!)
  (hook-browser-navigation!)
  (mount-components))
