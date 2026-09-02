(ns digdir.playground.ui.styles
  "CSS styles for the RAG Playground UI."
  (:require [digdir.playground.ui.components :as base]))

;; =============================================================================
;; Base Styles
;; =============================================================================

(def card-style
  {:margin-bottom "1rem"
   :border "1px solid #e2e8f0"
   :border-radius "8px"
   :background "white"})

(def label-style
  {:display "block"
   :font-weight "500"
   :margin-bottom "0.25rem"
   :font-size "0.875rem"
   :color "#374151"})

(def input-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"})

(def select-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :background "white"
   :font-size "0.875rem"})

(def textarea-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-family "monospace"
   :font-size "0.8rem"
   :resize "vertical"})

(def modal-backdrop-style base/modal-backdrop-style)
(def modal-content-style base/modal-content-style)

;; =============================================================================
;; Chat UI Styles
;; =============================================================================

(def message-bubble-base-style
  {:padding "0.75rem 1rem"
   :border-radius "12px"
   :margin-bottom "0.5rem"
   :max-width "85%"
   :line-height "1.5"})

(def user-message-style
  (merge message-bubble-base-style
         {:background "#3b82f6"
          :color "white"
          :margin-left "auto"
          :border-bottom-right-radius "4px"}))

(def assistant-message-style
  (merge message-bubble-base-style
         {:background "#f3f4f6"
          :color "#1f2937"
          :margin-right "auto"
          :border-bottom-left-radius "4px"}))

(def message-thread-style
  {:flex "1"
   :overflow-y "auto"
   :padding "1rem"
   :display "flex"
   :flex-direction "column"
   :gap "0.5rem"})

(def chat-input-style
  {:padding "0.75rem"
   :border-top "1px solid #e5e7eb"
   :background "white"})

(def conversation-sidebar-style
  {:width "280px"
   :border-right "1px solid #e5e7eb"
   :background "#fafafa"
   :overflow-y "auto"
   :display "flex"
   :flex-direction "column"})

(def sidebar-item-style
  {:padding "0.75rem 1rem"
   :cursor "pointer"
   :border-bottom "1px solid #e5e7eb"
   :font-size "0.875rem"
   :transition "background 0.15s"})

;; =============================================================================
;; Source & Citation Styles
;; =============================================================================

(def source-title-ellipsis-style base/source-title-ellipsis-style)
(def source-heading-ellipsis-style base/source-heading-ellipsis-style)
(def source-modal-title-style base/source-modal-title-style)
(def source-modal-heading-style base/source-modal-heading-style)

(def citation-style
  {:color "#2563eb"
   :font-size "0.75em"
   :vertical-align "super"
   :cursor "pointer"
   :font-weight "600"})
