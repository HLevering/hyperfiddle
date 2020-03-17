(ns hyperfiddle.domain
  (:refer-clojure :exclude [memoize])
  (:require
    [hyperfiddle.service.resolve :as resolve]
    [hyperfiddle.route :as route]
    [hyperfiddle.system-fiddle :as system-fiddle]
    [hyperfiddle.database.color :as color]
    [cognitect.transit :as t]
    [hypercrud.transit :as hc-t]
    #?(:clj [hyperfiddle.io.datomic.core :as d])
    [hypercrud.browser.router-bidi :as router-bidi]

    [bidi.bidi :as bidi]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [contrib.uri :refer [is-uri?]]
    [cats.monad.either :as either]
    [contrib.try$ :refer [try-either]]))


; todo these db specs belong somewhere else
(def dbname-spec (s/and string? #(string/starts-with? % "$")))
(s/def :database/db-name string?)
(s/def :database/uri is-uri?)
(def database-spec (s/keys :opt [:database/db-name :database/uri]))

(s/def ::basis some?)
(s/def ::type-name some?)
(s/def ::fiddle-dbname dbname-spec)
(s/def ::databases (s/map-of dbname-spec database-spec))
(s/def ::environment map?)


(defprotocol Domain
  (basis [domain])
  (type-name [domain])
  (fiddle-dbname [domain])
  (databases [domain])
  (environment [domain])
  (url-decode [domain s])
  (url-encode [domain route])
  (api-routes [domain])

  (system-fiddle? [domain fiddle-ident])
  (hydrate-system-fiddle [domain fiddle-ident])
  #?(:clj (connect [domain dbname]))
  (memoize [domain f]))


(defn database [domain dbname]
  (get (databases domain) dbname))

(defn database-color [domain dbname]
  (or (:database/color (database domain dbname)) (color/color-for-name dbname)))

(defn api-path-for [domain handler & {:as params}]
  (apply bidi/path-for (api-routes domain) handler (apply concat params)))

(defn api-match-path [domain path & {:as options}]
  (apply bidi/match-route (api-routes domain) path (apply concat options)))

(defn valid-dbname? [domain dbname] (some? (database domain dbname)))

(defn valid-dbnames? [domain dbnames] (set/subset? (set dbnames) (set (keys (databases domain)))))

(defn dbname-label [dbname]
  (if (= "$" dbname)
    "$ (default)"
    dbname))


(s/def ::home-route (s/spec :hyperfiddle/route))

(def spec-ednish
  (s/and
    (s/keys :req-un [::basis
                     ::fiddle-dbname
                     ::databases
                     ::environment
                     ::home-route])
    #(contains? (:databases %) (:fiddle-dbname %))))

(declare api routes)

(defrecord EdnishDomain [basis fiddle-dbname databases environment home-route ?datomic-client memoize-cache]
  Domain
  (basis [domain] basis)
  (type-name [domain] (str *ns* "/" "EdnishDomain"))
  (fiddle-dbname [domain] fiddle-dbname)
  (databases [domain] databases)
  (environment [domain] environment)
  (url-decode [domain s] (route/url-decode s home-route))
  (url-encode [domain route] (route/url-encode route home-route))
  (api-routes [domain] routes)
  (system-fiddle? [domain fiddle-ident] (system-fiddle/system-fiddle? fiddle-ident))
  (hydrate-system-fiddle [domain fiddle-ident] (system-fiddle/hydrate fiddle-ident))
  #?(:clj (connect [domain dbname] (d/dyna-connect (database domain dbname) ?datomic-client)))
  (memoize [domain f]
    (if-let [f (get @memoize-cache f)]
      f
      (let [ret (clojure.core/memoize f)]
        (swap! memoize-cache assoc f ret)
        ret))))

(defrecord BidiDomain [basis fiddle-dbname databases environment router ?datomic-client memoize-cache]
  Domain
  (basis [domain] basis)
  (type-name [domain] (str *ns* "/" "BidiDomain"))
  (fiddle-dbname [domain] fiddle-dbname)
  (databases [domain] databases)
  (environment [domain] environment)
  (url-decode [domain s]
    (either/branch
      (try-either (router-bidi/decode router s))
      (fn [e] (route/decoding-error e s))
      identity))
  (url-encode [domain route] (router-bidi/encode router route))
  (api-routes [domain] routes)
  (system-fiddle? [domain fiddle-ident] (system-fiddle/system-fiddle? fiddle-ident))
  (hydrate-system-fiddle [domain fiddle-ident] (system-fiddle/hydrate fiddle-ident))
  #?(:clj (connect [domain dbname] (d/dyna-connect (database domain dbname) ?datomic-client)))
  (memoize [domain f]
    (if-let [f (get @memoize-cache f)]
      f
      (let [ret (clojure.core/memoize f)]
        (swap! memoize-cache assoc f ret)
        ret))))


(def ^:private read-handlers (atom hc-t/read-handlers))
(def ^:private write-handlers (atom hc-t/write-handlers))

(defn register-handlers [c tag rep-fn from-rep]
  (swap! read-handlers assoc tag (t/read-handler from-rep))
  (swap! write-handlers assoc c (t/write-handler (constantly tag) rep-fn)))

(defn decode
  ([s]
   (hc-t/decode s :opts {:handlers @read-handlers}))
  ([s type]
   (hc-t/decode s :opts {:handlers @read-handlers} :type type)))

(defn encode
  ([x]
   (hc-t/encode x :opts {:handlers @write-handlers}))
  ([x type]
   (hc-t/encode x :opts {:handlers @write-handlers} :type type)))

(register-handlers
  EdnishDomain
  (str 'hyperfiddle.domain/EdnishDomain)
  #(-> (into {} %) (dissoc :?datomic-client :memoize-cache))
  #(-> (into {} %) (assoc :memoize-cache (atom nil)) map->EdnishDomain))

(register-handlers
  BidiDomain
  (str 'hyperfiddle.domain/BidiDomain)
  #(-> (into {} %) (dissoc :?datomic-client :memoize-cache))
  #(-> (into {} %) (assoc :memoize-cache (atom nil)) map->BidiDomain))


(defn api [route-ns]
  {"global-basis" {:get (keyword route-ns "global-basis")
                   #".+" (keyword route-ns "404")
                   true (keyword route-ns "405")}

   ["hydrate-requests/" [#"[^/]*" :local-basis]] {:post (keyword route-ns "hydrate-requests")
                                                  #".+" (keyword route-ns "404")
                                                  true (keyword route-ns "405")}

   ["hydrate-route/" [#"[^/]*" :local-basis] "/" [#"[^/]*" :partition-id] "/" [#".*" :encoded-route]]
   {:get (keyword route-ns "hydrate-route")
    :post (keyword route-ns "hydrate-route")
    true (keyword route-ns "405")}

   ["local-basis/" [#"[^/]*" :global-basis] "/" [#".*" :encoded-route]]
   {:get (keyword route-ns "local-basis")
    :post (keyword route-ns "local-basis")
    true (keyword route-ns "405")}

   "sync" {:post (keyword route-ns "sync")
           #".+" (keyword route-ns "404")
           true (keyword route-ns "405")}

   "transact" {:post (keyword route-ns "transact")
               #".+" (keyword route-ns "404")
               true (keyword route-ns "405")}

   true (keyword route-ns "404")})

(def routes
  ["/" {"api/" {(str resolve/api-version-tag "/") (api nil)
                [[#"[^/]*" :version] "/"] {true :force-refresh}
                true                      :404}
        "static/" {[:build "/" [#".+" :resource-name]] {:get :static-resource
                                                        true :405}
                   true :404}
        "favicon.ico" :favicon
        true {:get :ssr
              true :405}}])
