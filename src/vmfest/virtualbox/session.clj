(ns vmfest.virtualbox.session
  (:use [clojure.contrib.logging :as log]
        [vmfest.virtualbox.conditions :as conditions]
        [vmfest.virtualbox.model :as model])
  (:import [com.sun.xml.ws.commons.virtualbox_3_2
            IWebsessionManager
            IVirtualBox]
           [vmfest.virtualbox.model
            Server
            Machine]))

(defn- ^IWebsessionManager create-session-manager
   "Creates a IWebsessionManager. Note that the default port is 18083"
   [url]
   (log/debug (str "Creating session manager for " url))
   (IWebsessionManager. url))

(defn- ^IVirtualBox create-vbox
 "TODO"
 ([^IWebsessionManager mgr username password]
    (log/debug (str "creating new vbox with a logon for user " username))
    (try 
      (.logon mgr username password)
      (catch com.sun.xml.internal.ws.client.ClientTransportException e
        (log-and-raise e :error
                       (format "Cannot connect to virtualbox server: '%s'" (.getMessage e))
                       :connection-error))))
 ([^Server server]
    (let [{:keys [url username password]} server
          mgr (create-session-manager url)]
      (create-vbox mgr username password))))

(defn create-mgr-vbox
  ([^Server server]
     (let [{:keys [url username password]} server]
       (create-mgr-vbox url username password)))
  ([url username password]
     (let [mgr (create-session-manager url)
           vbox (create-vbox mgr username password)]
       [mgr vbox])))

(defmacro with-vbox [^Server server [mgr vbox] & body]
  `(let [[~mgr ~vbox] (create-mgr-vbox ~server)]
     (try
       ~@body
       (finally (when ~vbox
                  (try (.logoff ~mgr ~vbox)
                       (catch Exception e#
                         (conditions/log-and-raise e# :warn "unable to close session" :communication))))))))

(defmacro with-direct-session
  [machine [session vb-m] & body]
  `(try
     (let [machine-id# (:id ~machine)]
       (with-vbox (:server ~machine) [mgr# vbox#]
         (with-open [~session (.getSessionObject mgr# vbox#)]
           (.openSession vbox# ~session machine-id#)
           (log/trace (format "direct session is open for machine-id='%s'" machine-id#))
           (let [~vb-m (.getMachine ~session)]
             (try
               ~@body
               (catch java.lang.IllegalArgumentException e#
                 (conditions/log-and-raise e#
                                :error
                                (format "Called a method that is not available with a direct session in '%s'" '~body)
                                :invalid-method)))))))
     (catch Exception e# 
       (conditions/log-and-raise e# :error
                      (format "Cannot open session with machine '%s' reason:%s"
                              (:id ~machine)
                              (.getMessage e#))
                      :connection-error))))

(defmacro with-no-session
  [^Machine machine [vb-m] & body]
  `(try
     (with-vbox (:server ~machine) [_# vbox#]
       (let [~vb-m (soak ~machine vbox#)] 
         ~@body))
      (catch java.lang.IllegalArgumentException e#
         (conditions/log-and-raise e# :error "Called a method that is not available without a session"
                        :method-not-available))
       (catch Exception e#
         (conditions/log-and-raise e# :error "An error occurred" :unknown))))

(defmacro with-remote-session
  [^Machine machine [session console] & body]
  `(try
     (let [machine-id# (:id ~machine)]
       (with-vbox (:server ~machine) [mgr# vbox#]
         (with-open [~session (.getSessionObject mgr# vbox#)]
           (.openExistingSession vbox# ~session machine-id#)
           (trace (str "new remote session is open for machine-id=" machine-id#))
           (let [~console (.getConsole ~session)]
             (try
               ~@body
               (catch java.lang.IllegalArgumentException e#
                 (conditions/log-and-raise e# :error "Called a method that is not available without a session"
                                :method-not-available)))))))
     (catch Exception e#
       (conditions/log-and-raise e# :error "An error occurred" :unknown))))