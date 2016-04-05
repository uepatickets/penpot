(ns uxbox.ui.workspace
  (:refer-clojure :exclude [dedupe])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.state.project :as stpr]
            [uxbox.data.workspace :as dw]
            [uxbox.data.projects :as dp]
            [uxbox.data.pages :as udp]
            [uxbox.data.history :as udh]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer (classnames)]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.messages :as uum]
            [uxbox.ui.confirm]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.workspace.canvas.scroll :as scroll]
            [uxbox.ui.workspace.base :as uuwb]
            [uxbox.ui.workspace.shortcuts :as wshortcuts]
            [uxbox.ui.workspace.header :refer (header)]
            [uxbox.ui.workspace.rules :refer (horizontal-rule vertical-rule)]
            [uxbox.ui.workspace.sidebar :refer (left-sidebar right-sidebar)]
            [uxbox.ui.workspace.colorpalette :refer (colorpalette)]
            [uxbox.ui.workspace.canvas :refer (viewport)]))

;; --- Workspace

(defn- workspace-will-mount
  [own]
  (let [[projectid pageid] (:rum/props own)]
    (rs/emit! (dw/initialize projectid pageid)
              (udp/fetch-pages projectid)
              (udh/watch-page-changes)
              (udh/fetch-page-history pageid)
              (udh/fetch-pinned-page-history pageid))
    own))

(defn- workspace-did-mount
  [own]
  (let [[projectid pageid] (:rum/props own)
        sub1 (scroll/watch-scroll-interactions own)
        sub2 (udp/watch-page-changes pageid)
        dom (mx/get-ref-dom own "workspace-canvas")]

    ;; Set initial scroll position
    (set! (.-scrollLeft dom) uuwb/canvas-start-scroll-x)
    (set! (.-scrollTop dom) uuwb/canvas-start-scroll-y)

    (assoc own ::sub1 sub1 ::sub2 sub2)))

(defn- workspace-will-unmount
  [own]
  (rs/emit! (udh/clean-page-history))

  ;; Close subscriptions
  (.close (::sub1 own))
  (.close (::sub2 own))

  (dissoc own ::sub1 ::sub2))

(defn- workspace-transfer-state
  [old-state state]
  (let [[projectid pageid] (:rum/props state)
        [oldprojectid oldpageid] (:rum/props old-state)]
    (if (not= pageid oldpageid)
      (do
        (rs/emit! (dw/initialize projectid pageid))
        (.close (::sub2 old-state))
        (assoc state
               ::sub1 (::sub1 old-state)
               ::sub2 (udp/watch-page-changes pageid)))
      (assoc state
             ::sub1 (::sub1 old-state)
             ::sub2 (::sub2 old-state)))))

(defn- on-scroll
  [event]
  (let [target (.-target event)
        top (.-scrollTop target)
        left (.-scrollLeft target)]
    (rx/push! uuwb/scroll-b (gpt/point left top))))


(def ^:const ^:private zoom-l
  (-> (l/in [:workspace :zoom])
      (l/focus-atom st/state)))

(defn- on-wheel
  [own event]
  (when (kbd/ctrl? event)
    (dom/prevent-default event)
    (dom/stop-propagation event)
    (if (pos? (.-deltaY event))
      (rs/emit! (dw/increase-zoom))
      (rs/emit! (dw/decrease-zoom)))

    (let [dom (mx/get-ref-dom own "workspace-canvas")]
      (set! (.-scrollLeft dom) (* uuwb/canvas-start-scroll-x (or @zoom-l 1)))
      (set! (.-scrollTop dom) (* uuwb/canvas-start-scroll-y (or @zoom-l 1))))))

(defn- workspace-render
  [own projectid]
  (let [{:keys [flags zoom] :as workspace} (rum/react uuwb/workspace-l)
        left-sidebar? (not (empty? (keep flags [:layers :sitemap
                                                :document-history])))
        right-sidebar? (not (empty? (keep flags [:icons :drawtools
                                                 :element-options])))
        local (:rum/local own)
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?)
                 :scrolling (:scrolling @local false))]
    (html
     [:div
      (header)
      (colorpalette)
      (uum/messages)

      [:main.main-content

       [:section.workspace-content
        {:class classes
         :on-scroll on-scroll
         :on-wheel (partial on-wheel own)}

        ;; Rules
        (horizontal-rule zoom)
        (vertical-rule zoom)

        ;; Canvas
        [:section.workspace-canvas
         {:ref "workspace-canvas"}
         (viewport)]]

       ;; Aside
       (when left-sidebar?
         (left-sidebar))
       (when right-sidebar?
         (right-sidebar))]])))

(def ^:static workspace
  (mx/component
   {:render workspace-render
    :transfer-state workspace-transfer-state
    :will-mount workspace-will-mount
    :will-unmount workspace-will-unmount
    :did-mount workspace-did-mount
    :name "workspace"
    :mixins [mx/static rum/reactive wshortcuts/mixin
             (mx/local)]}))
