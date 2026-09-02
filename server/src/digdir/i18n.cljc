(ns digdir.i18n
  "Internationalization support for the application.

   This namespace provides translation functions and loads UI text strings
   from resources/i18n/base.edn. Currently supports Norwegian (nb) and English (en)."
  (:require [clojure.string :as str]
            #?@(:clj [[clojure.java.io :as io]
                      [clojure.edn :as edn]])
            #?@(:cljs [[goog.string :as gstring]
                       [goog.string.format]])))

#?(:clj
   (defn load-translations-clj
     "Load translations from base.edn file on the server."
     []
     (try
       (let [resource (io/resource "i18n/base.edn")]
         (if resource
           (let [translations (edn/read-string (slurp resource))]
             (println "i18n loaded successfully. Languages:" (keys translations))
             translations)
           (do
             (println "WARNING: Could not find i18n resource: i18n/base.edn")
             {:nb {} :en {}})))
       (catch Exception e
         (println "ERROR loading i18n translations:" (.getMessage e))
         (.printStackTrace e)
         {:nb {} :en {}}))))

;; Shared translations atom
(defonce translations
  #?(:clj (delay (load-translations-clj))
     :cljs (atom {:nb {}})))

(def supported-languages #{:en :nb})

(defn normalize-language
  "Normalize a language value to a supported keyword, defaulting to English."
  [lang]
  (let [lang-kw (cond
                  (keyword? lang) lang
                  (string? lang) (some-> lang str/trim str/lower-case keyword)
                  :else nil)]
    (if (contains? supported-languages lang-kw) lang-kw :en)))

#?(:clj
   (def ^:dynamic *current-language* :en))

;; The client installs the persisted locale before rendering translated UI.
;; Keeping this atom available in both builds lets shared .cljc components
;; refer to one stable var while the server uses the dynamic binding above.
(defonce !current-language (atom :en))

(defn current-language
  "Return the currently active language."
  []
  #?(:clj (normalize-language *current-language*)
     :cljs (normalize-language @!current-language)))

#?(:cljs
   (defn set-language-cljs!
     "Set the active client-side language."
     [lang]
     (reset! !current-language (normalize-language lang)))
   :clj
   (defn set-language-cljs!
     [_]
     nil))

#?(:cljs
   (defn load-translations-cljs!
     "Load translations on the client side from the server-provided map."
     [trans]
     ;; Avoid invalidating the UI when Electric sends the same map again.
     (when (not= trans @translations)
       (reset! translations trans))
     (boolean (seq trans)))
   :clj
   (defn load-translations-cljs!
     [_]
     true))

(defn translate
  "Translate a key in an explicitly requested language."
  [lang k & args]
  (let [trans #?(:clj @translations :cljs @translations)
        lang (normalize-language lang)
        translation (get-in trans [lang k])]
    (if translation
      (if (seq args)
        #?(:clj (apply format translation args)
           :cljs (apply gstring/format translation args))
        translation)
      (str k))))

(defn t
  "Translate a key to the current language.

   Usage:
     (t :auth/login-title)
     => \"Logg inn\"

     (t :sources/no-results \"søkeord\")
     => \"Fant ingenting om \\\"søkeord\\\" blant kildene dine.\"

   Args:
     k - Translation key (keyword)
     args - Optional format arguments for string interpolation

   Returns:
     Translated string, or the key as a string if translation not found"
  [k & args]
  (apply translate (current-language) k args))

(defn t-exists?
  "Check if a translation key exists for the current language.

   Args:
     k - Translation key (keyword)

   Returns:
     Boolean indicating if translation exists"
  [k]
  (let [trans #?(:clj @translations :cljs @translations)]
    (contains? (get trans (current-language)) k)))

;; Convenience functions for commonly used patterns
(defn t-count
  "Translate with count-aware formatting.
   Useful for singular/plural forms.

   Usage:
     (t-count :sources/match-counter 1 5)
     => \"1 av 5 treff\"

     (t-count :sources/match-counter 3)
     => \"3 treff\""
  [base-key & args]
  (apply t base-key args))

(comment
  ;; Usage examples
  (t :auth/login-title)
  ;; => "Logg inn"

  (t :auth/code-sent-to)
  ;; => "Vi har sendt din påloggingskode til"

  (t :sources/no-results "testord")
  ;; => "Fant ingenting om \"testord\" blant kildene dine."

  (t :filters/select-label "dokumenttyper")
  ;; => "Velg dokumenttyper"

  (t-count :sources/match-counter-single 5)
  ;; => "5 treff"

  (t-count :sources/match-counter-multiple 2 10)
  ;; => "2 av 10 treff"

  (t-exists? :auth/login-title)
  ;; => true

  (t-exists? :nonexistent/key)
  ;; => false
  )
