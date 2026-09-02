(ns digdir.config.ui.styles
  "CSS styles for the V2 config management UI.")

;; =============================================================================
;; Base Styles
;; =============================================================================

(def input-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"
   :font-family "monospace"})

(def select-style
  {:padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :background "white"
   :font-size "0.875rem"})

(def badge-style
  {:padding "0.125rem 0.5rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"})

(def textarea-style
  {:width       "100%"
   :padding     "0.5rem"
   :border      "1px solid #d1d5db"
   :border-radius "4px"
   :font-family "monospace"
   :font-size   "0.8rem"
   :resize      "vertical"
   :min-height  "150px"})

;; =============================================================================
;; Colors
;; =============================================================================

(def sensitivity-colors
  {:public {:bg "#dcfce7" :text "#166534"}
   :internal {:bg "#dbeafe" :text "#1e40af"}
   :admin-only {:bg "#fef3c7" :text "#92400e"}
   :secret {:bg "#fee2e2" :text "#991b1b"}})

(def resolution-colors
  "Colors for resolution level badges using multi-dimensional model.
   Organized from least specific (global) to most specific (pipeline+tenant+env)."
  {;; 0 dimensions
   :global {:bg "#f3f4f6" :text "#6b7280"}
   ;; 1 dimension
   :environment {:bg "#e0e7ff" :text "#3730a3"}
   :tenant {:bg "#dbeafe" :text "#1e40af"}
   :pipeline {:bg "#6ee7b7" :text "#064e3b"}
   ;; 2 dimensions
   :tenant-env {:bg "#dcfce7" :text "#166534"}
   :pipeline-env {:bg "#99f6e4" :text "#115e59"}
   :pipeline-tenant {:bg "#bfdbfe" :text "#1e3a8a"}
   ;; 3 dimensions
   :pipeline-tenant-env {:bg "#bbf7d0" :text "#14532d"}})

(def preview-status-colors
  {:create {:bg "#dcfce7" :text "#166534" :border "#22c55e"}
   :update {:bg "#fef3c7" :text "#92400e" :border "#f59e0b"}
   :skip   {:bg "#f3f4f6" :text "#6b7280" :border "#d1d5db"}
   :delete {:bg "#fee2e2" :text "#991b1b" :border "#ef4444"}})

;; =============================================================================
;; Layout & Component Styles
;; =============================================================================

(def modal-backdrop-style
  {:position   "fixed"
   :top        "0"
   :left       "0"
   :right      "0"
   :bottom     "0"
   :background "rgba(0,0,0,0.5)"
   :display    "flex"
   :align-items "center"
   :justify-content "center"
   :z-index    "1000"})

(def modal-content-style
  {:background     "white"
   :border-radius  "8px"
   :padding        "1.5rem"
   :max-width      "600px"
   :width          "90%"
   :max-height     "80vh"
   :overflow-y     "auto"})

(def ops-panel-style
  {:background    "#f0f9ff"
   :border        "1px solid #bae6fd"
   :border-radius "8px"
   :padding       "1rem"
   :margin-bottom "1rem"})

(def ops-card-style
  {:background    "white"
   :border        "1px solid #e5e7eb"
   :border-radius "6px"
   :padding       "1rem"
   :min-width     "220px"
   :flex          "1"})

(def ops-card-title-style
  {:font-weight   "600"
   :font-size     "0.875rem"
   :color         "#1e40af"
   :margin-bottom "0.75rem"})

(def diagnostics-card-style
  {:background "white"
   :border "1px solid #e5e7eb"
   :border-radius "8px"
   :padding "1rem"
   :margin-bottom "1rem"})

(def diagnostics-grid-style
  {:display "grid"
   :grid-template-columns "repeat(auto-fit, minmax(220px, 1fr))"
   :gap "0.75rem"
   :margin-bottom "1rem"})

(def diagnostics-label-style
  {:display "block"
   :font-size "0.75rem"
   :font-weight "600"
   :color "#374151"
   :margin-bottom "0.25rem"})

(def tab-style
  {:padding "0.75rem 1.5rem"
   :border "none"
   :background "transparent"
   :cursor "pointer"
   :font-size "0.875rem"
   :color "#6b7280"
   :border-bottom "2px solid transparent"})

(def tab-active-style
  (merge tab-style
         {:color "#3b82f6"
          :font-weight "600"
          :border-bottom "2px solid #3b82f6"}))

;; =============================================================================
;; Button Style Factory
;; =============================================================================

(def ^:private button-base-style
  {:border        "none"
   :border-radius "4px"
   :cursor        "pointer"})

(def ^:private button-variants
  {:primary   {:background "#3b82f6" :color "white"}
   :secondary {:background "#e5e7eb" :color "#374151"}
   :danger    {:background "#fee2e2" :color "#991b1b"}
   :success   {:background "#22c55e" :color "white"}})

(def ^:private button-sizes
  {:normal {:padding "0.5rem 1rem" :font-size "0.875rem" :border-radius "4px"}
   :small  {:padding "0.125rem 0.375rem" :font-size "0.625rem" :border-radius "2px"}})

(defn button-style
  "Generate button style.
   variant: :primary, :secondary, :danger, :success
   size: :normal (default), :small"
  ([variant] (button-style variant :normal))
  ([variant size]
   (merge button-base-style
          (get button-sizes size (:normal button-sizes))
          (get button-variants variant (:secondary button-variants)))))
