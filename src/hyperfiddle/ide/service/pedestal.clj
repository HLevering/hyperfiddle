(ns hyperfiddle.ide.service.pedestal
  (:refer-clojure :exclude [sync])
  (:require
    [hyperfiddle.service.resolve :as R]
    [contrib.do :refer :all]
    [hyperfiddle.domain :as domain]
    [hyperfiddle.ide.authenticate]
    [hyperfiddle.ide.directory :as ide-directory]           ; immoral
    [hyperfiddle.ide.domain :as ide-domain]
    [hyperfiddle.ide.service.core :as ide-service]
    [hyperfiddle.io.core :as io]
    [hyperfiddle.io.datomic.hydrate-requests :refer [hydrate-requests]]
    [hyperfiddle.io.datomic.sync :refer [sync]]
    [hyperfiddle.io.datomic.transact :refer [transact!]]
    [hyperfiddle.route :as route]
    [hyperfiddle.service.cookie :as cookie]
    [hyperfiddle.service.dispatch :as dispatch]
    [hyperfiddle.service.pedestal :as hf-http]
    [promesa.core :as p]
    [taoensso.timbre :as timbre]
    [contrib.base-64-url-safe :as base64-url-safe])
  (:import
    [hyperfiddle.ide.domain IdeDomain]))


(defmethod dispatch/via-domain IdeDomain [context]
  (-> context
      (hf-http/via
        (let [domain (:domain (R/from context))
              env (-> (R/from context) :config :env)
              cookie-domain (::ide-directory/ide-domain domain)]
          #_#_{:keys [cookie-name jwt-secret jwt-issuer]} (-> (get-in context [:request :domain])
                                                              domain/environment-secure :jwt)
          (hf-http/with-user-id ide-service/cookie-name
            cookie-domain
            (:AUTH0_CLIENT_SECRET env)
            (:AUTH0_DOMAIN env))))

      (hf-http/via
        (fn [context]
          (let [domain (get-in context [:request :domain])
                [method path] (-> context :request (select-keys [:request-method :path-info]) vals)
                route (domain/api-match-path domain path :request-method method)]

            (when-not (= (:handler route) :static-resource)
              (timbre/info "ide-router:" (pr-str (:handler route)) (pr-str method) (pr-str path)))

            (case (namespace (:handler route))
              nil (-> context
                      (assoc-in [:request :handler] (:handler route))
                      (assoc-in [:request :route-params] (:route-params route))
                      dispatch/endpoint)

              "user" (dispatch/endpoint
                       (update context :request #(-> (dissoc % :jwt) ; this is brittle
                                                     (assoc :domain (from-result (::ide-domain/user-domain+ domain))
                                                            :handler (keyword (name (:handler route)))
                                                            :route-params (:route-params route))))))))
        )))

(defmethod dispatch/endpoint :hyperfiddle.ide/auth0-redirect [context]
  (R/via context R/run-IO
    (fn [context]
      (let [{:keys [domain] :as request} context
            env (-> (R/from context) :config :env)
            io (reify io/IO
                 (hydrate-requests [io local-basis partitions requests]
                   ; todo who is this executed on behalf of? system/root or the end user?
                   (p/do* (hydrate-requests domain local-basis requests partitions nil)))

                 (sync [io dbnames]
                   ; todo who is this executed on behalf of? system/root or the end user?
                   (p/do* (sync domain dbnames)))

                 (transact! [io tx-groups]
                   ; todo who is this executed on behalf of? system/root or the end user?
                   (p/do* (transact! domain nil tx-groups))))

            jwt (from-async (hyperfiddle.ide.authenticate/login env domain (R/via context R/uri-for :/) io (-> request :query-params :code)))]

        {:status  302
         :headers {"Location" (-> (get-in request [:query-params :state]) base64-url-safe/decode)}
         :cookies {ide-service/cookie-name (-> domain ::ide-directory/ide-domain
                                               (cookie/jwt-options-pedestal)
                                               (assoc :value jwt
                                                      #_#_:expires expiration))}
         :body    ""})
      )))

(defmethod dispatch/endpoint :hyperfiddle.ide/logout [context]
  (hf-http/response context
    (fn [{:keys [request]}]
      {:status  302
       :headers {"Location" "/"}
       :cookies {ide-service/cookie-name (-> request :domain ::ide-directory/ide-domain
                                             (cookie/jwt-options-pedestal)
                                             (assoc :value (get-in request [:cookies ide-service/cookie-name :value])
                                                    :expires "Thu, 01 Jan 1970 00:00:00 GMT"))}
       :body    ""})))