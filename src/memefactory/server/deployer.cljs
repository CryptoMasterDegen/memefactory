(ns memefactory.server.deployer
  (:require
    [cljs-web3.core :as web3]
    [cljs-web3.eth :as web3-eth]
    [district.cljs-utils :refer [rand-str]]
    [district.server.config :refer [config]]
    [district.server.smart-contracts :refer [contract-address deploy-smart-contract! write-smart-contracts!]]
    [district.server.web3 :refer [web3]]
    [memefactory.server.contract.dank-token :as dank-token]
    [memefactory.server.contract.ds-auth :as ds-auth]
    [memefactory.server.contract.ds-guard :as ds-guard]
    [memefactory.server.contract.eternal-db :as eternal-db]
    [memefactory.server.contract.registry :as registry]
    [mount.core :as mount :refer [defstate]]))

(declare deploy)
(defstate ^{:on-reload :noop} deployer
  :start (deploy (merge (:deployer @config)
                        (:deployer (mount/args)))))

(def registry-placeholder "feedfeedfeedfeedfeedfeedfeedfeedfeedfeed")
(def dank-token-placeholder "deaddeaddeaddeaddeaddeaddeaddeaddeaddead")
(def forwarder-target-placeholder "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef")
(def deposit-collector-placeholder "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd")

(defn deploy-dank-token! [default-opts]
  (deploy-smart-contract! :DANK (merge default-opts {:gas 2200000
                                                     :arguments [(contract-address :minime-token-factory)
                                                                 (web3/to-wei 1000000000 :ether)]})))

(defn deploy-minime-token-factory! [default-opts]
  (deploy-smart-contract! :minime-token-factory (merge default-opts {:gas 2300000})))

(defn deploy-ds-guard! [default-opts]
  (deploy-smart-contract! :ds-guard (merge default-opts {:gas 1000000})))

(defn deploy-meme-registry-db! [default-opts]
  (deploy-smart-contract! :meme-registry-db (merge default-opts {:gas 1700000})))

(defn deploy-param-change-registry-db! [default-opts]
  (deploy-smart-contract! :param-change-registry-db (merge default-opts {:gas 1700000})))


(defn deploy-meme-registry! [default-opts]
  (deploy-smart-contract! :meme-registry (merge default-opts {:gas 1000000})))

(defn deploy-param-change-registry! [default-opts]
  (deploy-smart-contract! :param-change-registry (merge default-opts {:gas 1700000})))

(defn deploy-meme-registry-fwd! [default-opts]
  (deploy-smart-contract! :meme-registry-fwd (merge default-opts {:gas 500000
                                                                  :placeholder-replacements
                                                                  {forwarder-target-placeholder :meme-registry}})))

(defn deploy-param-change-registry-fwd! [default-opts]
  (deploy-smart-contract! :param-change-registry-fwd (merge default-opts
                                                            {:gas 500000
                                                             :placeholder-replacements
                                                             {forwarder-target-placeholder :param-change-registry}})))

(defn deploy-meme-token! [default-opts]
  (deploy-smart-contract! :meme-token (merge default-opts {:gas 1300000})))

(defn deploy-meme! [{:keys [:deposit-collector] :as default-opts}]
  (deploy-smart-contract! :meme (merge default-opts {:gas 3700000
                                                     :placeholder-replacements
                                                     {dank-token-placeholder :DANK
                                                      registry-placeholder :meme-registry-fwd
                                                      forwarder-target-placeholder :meme-token
                                                      deposit-collector-placeholder deposit-collector}})))

(defn deploy-param-change! [default-opts]
  (deploy-smart-contract! :param-change (merge default-opts {:gas 3700000
                                                             :placeholder-replacements
                                                             {dank-token-placeholder :DANK
                                                              registry-placeholder :param-change-registry-fwd}})))


(defn deploy-meme-factory! [default-opts]
  (deploy-smart-contract! :meme-factory (merge default-opts {:gas 1000000
                                                             :arguments [(contract-address :meme-registry-fwd)
                                                                         (contract-address :DANK)]
                                                             :placeholder-replacements
                                                             {forwarder-target-placeholder :meme}})))

(defn deploy-param-change-factory! [default-opts]
  (deploy-smart-contract! :param-change-factory (merge default-opts {:gas 1000000
                                                                     :arguments [(contract-address :param-change-registry-fwd)
                                                                                 (contract-address :DANK)]
                                                                     :placeholder-replacements
                                                                     {forwarder-target-placeholder :param-change}})))


(defn deploy [{:keys [:write? :initial-registry-params :transfer-dank-token-to-accounts]
               :as deploy-opts}]
  (let [accounts (web3-eth/accounts @web3)
        deploy-opts (merge {:from (first accounts)
                            :deposit-collector (first accounts)}
                           deploy-opts)]
    (deploy-ds-guard! deploy-opts)
    (ds-auth/set-authority :ds-guard (contract-address :ds-guard))

    (deploy-minime-token-factory! deploy-opts)
    (deploy-dank-token! deploy-opts)
    (deploy-meme-registry-db! deploy-opts)
    (deploy-param-change-registry-db! deploy-opts)

    (deploy-meme-registry! deploy-opts)
    (deploy-param-change-registry! deploy-opts)

    (deploy-meme-registry-fwd! deploy-opts)
    (deploy-param-change-registry-fwd! deploy-opts)

    (registry/construct [:meme-registry :meme-registry-fwd]
                        {:db (contract-address :meme-registry-db)}
                        deploy-opts)

    (registry/construct [:param-change-registry :param-change-registry-fwd]
                        {:db (contract-address :param-change-registry-db)}
                        deploy-opts)

    (ds-guard/permit {:src (contract-address :param-change-registry-fwd)
                      :dst (contract-address :ds-guard)
                      :sig ds-guard/ANY}
                     deploy-opts)

    (deploy-meme-token! deploy-opts)

    (deploy-meme! deploy-opts)
    (deploy-param-change! deploy-opts)

    (deploy-meme-factory! deploy-opts)
    (deploy-param-change-factory! deploy-opts)

    (eternal-db/set-uint-values :meme-registry-db (:meme-registry initial-registry-params) deploy-opts)
    (eternal-db/set-uint-values :param-change-registry-db (:param-change-registry initial-registry-params) deploy-opts)

    (ds-auth/set-authority :meme-registry-db (contract-address :ds-guard) deploy-opts)
    (ds-auth/set-authority :param-change-registry-db (contract-address :ds-guard) deploy-opts)
    (ds-auth/set-owner :meme-registry-db 0)
    (ds-auth/set-owner :param-change-registry-db 0)

    (ds-guard/permit {:src (contract-address :meme-registry-fwd)
                      :dst (contract-address :meme-registry-db)
                      :sig ds-guard/ANY}
                     deploy-opts)

    (ds-guard/permit {:src (contract-address :param-change-registry-fwd)
                      :dst (contract-address :meme-registry-db)
                      :sig ds-guard/ANY}
                     deploy-opts)

    (ds-guard/permit {:src (contract-address :param-change-registry-fwd)
                      :dst (contract-address :param-change-registry-db)
                      :sig ds-guard/ANY}
                     deploy-opts)

    (registry/set-factory [:meme-registry :meme-registry-fwd]
                          {:factory (contract-address :meme-factory) :factory? true})

    (registry/set-factory [:param-change-registry :param-change-registry-fwd]
                          {:factory (contract-address :param-change-factory) :factory? true})

    (when (pos? transfer-dank-token-to-accounts)
      (doseq [account (take transfer-dank-token-to-accounts (rest accounts))]
        (dank-token/transfer {:to account :amount (web3/to-wei 15000 :ether)}
                             {:from (first accounts)})))

    (when write?
      (write-smart-contracts!))))