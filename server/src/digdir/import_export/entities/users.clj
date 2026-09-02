(ns digdir.import-export.entities.users
  "User import/export helpers."
  (:require [datahike.api :as d]
            [digdir.import-export.report :as report]))

(def ^:private user-pull-pattern
  '[:user/id
    :user/email
    :user/preferred-language
    :user/created
    {:user/permissions [:permission/id]}])

(defn export-users
  [db]
  (->> (d/q '[:find [?user-id ...]
              :where
              [?e :user/id ?user-id]]
            db)
       sort
       (mapv (fn [user-id]
               (d/pull db user-pull-pattern [:user/id user-id])))))

(defn- user-eid
  [db user-id]
  (d/q '[:find ?e .
         :in $ ?user-id
         :where
         [?e :user/id ?user-id]]
       db user-id))

(defn- retract-user!
  [conn user-id]
  (when-let [eid (user-eid @conn user-id)]
    (d/transact conn {:tx-data [[:db/retractEntity eid]]})))

(defn- permission-id-of
  "Pull/export shape may give either a string id or a {:permission/id ...} map.
   Accept both."
  [entry]
  (if (map? entry)
    (:permission/id entry)
    entry))

(defn import-users!
  [conn users on-conflict]
  (reduce (fn [acc user]
            (let [user-id (:user/id user)
                  exists? (some? (user-eid @conn user-id))
                  overwrite? (and exists? (= on-conflict :overwrite))
                  permission-eids (->> (:user/permissions user)
                                       (keep (fn [entry]
                                               (let [permission-id (permission-id-of entry)]
                                                 (or (d/q '[:find ?e .
                                                            :in $ ?permission-id
                                                            :where
                                                            [?e :permission/id ?permission-id]]
                                                          @conn permission-id)
                                                     (throw (ex-info "Permission not found during user import"
                                                                     {:permission-id permission-id
                                                                      :user-id user-id}))))))
                                       vec)]
              (if (and exists? (= on-conflict :skip))
                (update acc :skipped inc)
                (do
                  (when overwrite?
                    (retract-user! conn user-id))
                  (d/transact conn
                              {:tx-data [(cond-> {:user/id user-id
                                                  :user/email (:user/email user)}
                                           (:user/preferred-language user) (assoc :user/preferred-language (:user/preferred-language user))
                                           (:user/created user) (assoc :user/created (:user/created user))
                                           (seq permission-eids) (assoc :user/permissions permission-eids))]})
                  (update acc (if overwrite? :overwritten :created) inc)))))
          {:created 0 :skipped 0 :overwritten 0}
          users))

(defn preview-import-users
  [db users on-conflict]
  (report/preview-existing-items users
                                 #(some? (user-eid db (:user/id %)))
                                 on-conflict))
