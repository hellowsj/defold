;; Copyright 2020-2024 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.game-object
  (:require [clojure.set :as set]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.build-target :as bt]
            [editor.defold-project :as project]
            [editor.game-object-common :as game-object-common]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.outline :as outline]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-dialog :as resource-dialog]
            [editor.resource-node :as resource-node]
            [editor.scene :as scene]
            [editor.scene-tools :as scene-tools]
            [editor.sound :as sound]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.util :as util])
  (:import [com.dynamo.gameobject.proto GameObject$PrototypeDesc]
           [com.dynamo.gamesys.proto Sound$SoundDesc]
           [javax.vecmath Vector3d]))

(set! *warn-on-reflection* true)

(def unknown-icon "icons/32/Icons_29-AT-Unknown.png")

(defn- gen-ref-ddf [id position rotation scale source-resource ddf-properties]
  {:id id
   :position position
   :rotation rotation
   :scale scale
   :component (resource/resource->proj-path source-resource)
   :properties ddf-properties})

(defn- gen-embed-ddf [id position rotation scale save-data]
  {:id id
   :type (or (and (:resource save-data) (:ext (resource/resource-type (:resource save-data))))
             "unknown")
   :position position
   :rotation rotation
   :scale scale
   :data (or (:content save-data) "")})

(defn- build-raw-sound [resource dep-resources user-data]
  (let [pb (:pb user-data)
        pb (assoc pb :sound (resource/proj-path (second (first dep-resources))))]
    {:resource resource :content (protobuf/map->bytes Sound$SoundDesc pb)}))

(defn- wrap-if-raw-sound [target _node-id]
  (let [resource (:resource (:resource target))
        source-path (resource/proj-path resource)
        ext (resource/type-ext resource)]
    (if (sound/supported-audio-formats ext)
      (let [workspace (project/workspace (project/get-project _node-id))
            res-type  (workspace/get-resource-type workspace "sound")
            pb        {:sound source-path}
            target    (bt/with-content-hash
                        {:node-id _node-id
                         :resource (workspace/make-build-resource (resource/make-memory-resource workspace res-type (protobuf/map->str Sound$SoundDesc pb)))
                         :build-fn build-raw-sound
                         :deps [target]})]
        target)
      target)))

(defn- source-outline-subst [err]
  (if-let [resource (get-in err [:user-data :resource])]
    (let [rt (resource/resource-type resource)
          label (or (:label rt) (:ext rt) "unknown")
          icon (or (:icon rt) unknown-icon)]
      {:node-id (:node-id err)
       :node-outline-key label
       :label label
       :icon icon})
    {:node-id -1
     :node-outline-key ""
     :icon ""
     :label ""}))

(defn- supported-transform-properties [component-resource-type]
  (assert (some? component-resource-type))
  (if-not (contains? (:tags component-resource-type) :component)
    #{}
    (let [supported-properties (-> component-resource-type :tag-opts :component :transform-properties)]
      (assert (set? supported-properties))
      (assert (every? (partial contains? game-object-common/identity-transform-properties) supported-properties))
      supported-properties)))

(defn- select-transform-properties
  "Used to strip unsupported transform properties when loading a component.
  Unsupported properties will be initialized to the identity transform.
  If the resource type is unknown to us, keep all transform properties."
  [component-resource-type component]
  (merge game-object-common/identity-transform-properties
         (select-keys component
                      (if (some? component-resource-type)
                        (supported-transform-properties component-resource-type)
                        (keys game-object-common/identity-transform-properties)))))

(g/defnk produce-component-transform-properties
  "Determines which transform properties we allow the user to edit using the
  Property Editor. This also affects which manipulators work with the component.
  If the resource type is unknown to us, show no transform properties."
  [source-resource]
  (if-let [component-resource-type (some-> source-resource resource/resource-type)]
    (supported-transform-properties component-resource-type)
    #{}))

(g/defnk produce-component-properties
  [_declared-properties source-properties transform-properties]
  (assert (set? transform-properties))
  (-> _declared-properties
      (update :properties (fn [properties]
                            (let [stripped-transform-properties (remove transform-properties (keys game-object-common/identity-transform-properties))
                                  component-node-properties (apply dissoc properties stripped-transform-properties)
                                  source-resource-properties (:properties source-properties)]
                              (merge-with (fn [_ _]
                                            (let [duplicate-keys (set/intersection (set (keys component-node-properties))
                                                                                   (set (keys source-resource-properties)))]
                                              (throw (ex-info (str "Conflicting properties in source resource: " duplicate-keys)
                                                              {:duplicate-keys duplicate-keys}))))
                                          component-node-properties
                                          source-resource-properties))))
      (update :display-order (fn [display-order]
                               (vec (distinct (concat display-order (:display-order source-properties))))))))

(g/defnk produce-component-build-targets [_node-id build-resource ddf-message pose resource-property-build-targets source-build-targets]
  ;; Create a build-target for the referenced or embedded component. Also tag on
  ;; :instance-data with the overrides for this instance. This will later be
  ;; extracted and compiled into the GameObject - the overrides do not end up in
  ;; the resulting component binary.
  ;;
  ;; TODO: We can avoid some of this processing & input dependencies for embedded components. Separate into individual production functions?
  ;; For example, embedded components do not have resource-property-build-targets, and cannot refer to raw sounds.
  (when-some [source-build-target (first source-build-targets)]
    (if-some [errors (not-empty (keep :error (:properties ddf-message)))]
      (g/error-aggregate errors :_node-id _node-id :_label :build-targets)
      (let [is-embedded (contains? ddf-message :data)
            build-target (-> source-build-target
                             (assoc :resource build-resource)
                             (wrap-if-raw-sound _node-id))
            build-resource (:resource build-target) ; The wrap-if-raw-sound call might have changed this.
            instance-data (if is-embedded
                            (game-object-common/embedded-component-instance-data build-resource ddf-message pose)
                            (let [proj-path->resource-property-build-target (bt/make-proj-path->build-target resource-property-build-targets)]
                              (game-object-common/referenced-component-instance-data build-resource ddf-message pose proj-path->resource-property-build-target)))
            build-target (assoc build-target :instance-data instance-data)]
        [(bt/with-content-hash build-target)]))))

(g/defnode ComponentNode
  (inherits scene/SceneNode)
  (inherits outline/OutlineNode)

  (property id g/Str
            (dynamic error (g/fnk [_node-id id id-counts]
                                  (or (validation/prop-error :fatal _node-id :id validation/prop-empty? id "Id")
                                      (validation/prop-error :fatal _node-id :id (partial validation/prop-id-duplicate? id-counts) id))))
            (dynamic read-only? (g/fnk [_node-id]
                                  (g/override? _node-id))))
  (property url g/Str
            (value (g/fnk [base-url id] (format "%s#%s" (or base-url "") id)))
            (dynamic read-only? (g/constantly true)))

  (display-order [:id :url :path scene/SceneNode])

  (input source-resource resource/Resource)
  (input source-properties g/Properties :substitute {:properties {}})
  (input scene g/Any)
  (input source-build-targets g/Any)
  (input resource-property-build-targets g/Any)
  (input base-url g/Str)
  (input id-counts g/Any)

  (input source-outline outline/OutlineData :substitute source-outline-subst)

  (output transform-properties g/Any produce-component-transform-properties)
  (output component-id g/IdPair (g/fnk [_node-id id] [id _node-id]))
  (output node-outline outline/OutlineData :cached
    (g/fnk [_node-id id source-outline source-properties source-resource]
      (let [source-outline (or source-outline {:icon unknown-icon})
            source-id (when-let [source-id (:node-id source-outline)]
                        (and (not= source-id -1) source-id))
            overridden? (boolean (some (fn [[_ p]] (contains? p :original-value)) (:properties source-properties)))]
        (-> {:node-id _node-id
             :node-outline-key id
             :label id
             :icon (or (not-empty (:icon source-outline)) unknown-icon)
             :outline-overridden? overridden?
             :children (:children source-outline)}
          (cond->
            (resource/openable-resource? source-resource) (assoc :link source-resource :outline-reference? true)
            source-id (assoc :alt-outline source-outline))))))
  (output ddf-message g/Any :abstract)
  (output scene g/Any :cached (g/fnk [_node-id id transform scene]
                                (game-object-common/component-scene _node-id id transform scene)))
  (output build-resource resource/Resource :abstract)
  (output build-targets g/Any :cached produce-component-build-targets)
  (output resource-property-build-targets g/Any (gu/passthrough resource-property-build-targets))
  (output _properties g/Properties :cached produce-component-properties))

(g/defnode EmbeddedComponent
  (inherits ComponentNode)

  (input embedded-resource-id g/NodeID :cascade-delete)
  (input save-data g/Any)
  (output ddf-message g/Any (g/fnk [id position rotation scale save-data]
                              (gen-embed-ddf id position rotation scale save-data)))
  (output build-resource resource/Resource (g/fnk [source-build-targets]
                                             (some-> source-build-targets first bt/make-content-hash-build-resource))))

;; -----------------------------------------------------------------------------
;; Currently some source resources have scale properties. This was done so
;; that particular component types such as the Label component could support
;; scaling. This is not ideal, since the scaling cannot differ between
;; instances of the component. We probably want to remove this and move the
;; scale attribute to the Component instance in the future.
;;
;; Here we delegate scaling to the embedded resource node. To support scaling,
;; the ResourceNode needs to implement both manip-scalable? and manip-scale.

(defmethod scene-tools/manip-scalable? ::EmbeddedComponent [node-id]
  (or (some-> (g/node-value node-id :embedded-resource-id) scene-tools/manip-scalable?)
      (contains? (g/node-value node-id :transform-properties) :scale)))

(defmethod scene-tools/manip-scale ::EmbeddedComponent [evaluation-context node-id ^Vector3d delta]
  (let [embedded-resource-id (g/node-value node-id :embedded-resource-id evaluation-context)]
    (cond
      (some-> embedded-resource-id scene-tools/manip-scalable?)
      (scene-tools/manip-scale evaluation-context embedded-resource-id delta)

      (contains? (g/node-value node-id :transform-properties evaluation-context) :scale)
      (let [[sx sy sz] (g/node-value node-id :scale evaluation-context)
            new-scale [(* sx (.x delta)) (* sy (.y delta)) (* sz (.z delta))]]
        (g/set-property node-id :scale (properties/round-vec new-scale)))

      :else
      nil)))

;; -----------------------------------------------------------------------------

(g/defnode ReferencedComponent
  (inherits ComponentNode)

  (property path g/Any
            (dynamic edit-type (g/fnk [source-resource]
                                      {:type resource/Resource
                                       :ext (some-> source-resource resource/resource-type :ext)
                                       :to-type (fn [v] (:resource v))
                                       :from-type (fn [r] {:resource r :overrides []})}))
            (value (g/fnk [source-resource ddf-properties]
                          {:resource source-resource
                           :overrides ddf-properties}))
            (set (fn [evaluation-context self old-value new-value]
                   (concat
                     (when-some [old-source (g/node-value self :source-id evaluation-context)]
                       (g/delete-node old-source))
                     (let [new-resource (:resource new-value)
                           resource-type (and new-resource (resource/resource-type new-resource))
                           project (project/get-project self)
                           override? (contains? (:tags resource-type) :overridable-properties)

                           [comp-node tx-data]
                           (if override?
                             (let [workspace (project/workspace project)]
                               (when-some [{connect-tx-data :tx-data
                                            comp-node :node-id
                                            created-in-tx :created-in-tx} (project/connect-resource-node evaluation-context project new-resource self [])]
                                 [comp-node
                                  (concat
                                    connect-tx-data
                                    (g/override comp-node {:traverse-fn g/always-override-traverse-fn}
                                                (fn [evaluation-context id-mapping]
                                                  (let [or-comp-node (get id-mapping comp-node)
                                                        comp-props (if created-in-tx
                                                                     {}
                                                                     (:properties (g/node-value comp-node :_properties evaluation-context)))]
                                                    (concat
                                                      (let [outputs (g/output-labels (:node-type (resource/resource-type new-resource)))]
                                                        (for [[from to] [[:_node-id :source-id]
                                                                         [:resource :source-resource]
                                                                         [:node-outline :source-outline]
                                                                         [:_properties :source-properties]
                                                                         [:scene :scene]
                                                                         [:build-targets :source-build-targets]
                                                                         [:resource-property-build-targets :resource-property-build-targets]]
                                                              :when (contains? outputs from)]
                                                          (g/connect or-comp-node from self to)))
                                                      (properties/apply-property-overrides workspace id-mapping comp-props (:overrides new-value)))))))]))
                             (let [old-resource (:resource old-value)
                                   new-resource (:resource new-value)
                                   connections [[:resource :source-resource]
                                                [:node-outline :source-outline]
                                                [:scene :scene]
                                                [:build-targets :source-build-targets]]
                                   disconnect-tx-data (when old-resource
                                                        (project/disconnect-resource-node evaluation-context project old-resource self connections))
                                   {connect-tx-data :tx-data comp-node :node-id} (when new-resource
                                                                                   (project/connect-resource-node evaluation-context project new-resource self connections))]
                               [comp-node
                                (concat disconnect-tx-data
                                        connect-tx-data)]))]
                       (concat
                         tx-data
                         (when-some [alter-referenced-component-fn (some-> resource-type :tag-opts :component :alter-referenced-component-fn)]
                           (alter-referenced-component-fn self comp-node)))))))
            (dynamic error (g/fnk [_node-id source-resource]
                                  (or (validation/prop-error :info _node-id :path validation/prop-nil? source-resource "Path")
                                      (validation/prop-error :fatal _node-id :path validation/prop-resource-not-exists? source-resource "Path")))))

  (input source-id g/NodeID :cascade-delete)
  (output build-resource resource/Resource (g/fnk [source-build-targets] (:resource (first source-build-targets))))
  (output ddf-properties g/Any :cached
          (g/fnk [source-properties]
                 (let [prop-order (into {} (map-indexed (fn [i k] [k i])) (:display-order source-properties))]
                   (->> source-properties
                     :properties
                     (filter (fn [[_ p]] (contains? p :original-value)))
                     (sort-by (comp prop-order first))
                     (into [] (keep properties/property-entry->go-prop))))))
  (output ddf-message g/Any (g/fnk [id position rotation scale source-resource ddf-properties]
                              (gen-ref-ddf id position rotation scale source-resource ddf-properties))))

(g/defnk produce-proto-msg [ref-ddf embed-ddf]
  {:components ref-ddf
   :embedded-components embed-ddf})

(defn- strip-default-scale-from-component-descs [component-descs]
  (mapv game-object-common/strip-default-scale-from-component-desc component-descs))

(defn strip-default-scale-from-components-in-prototype-desc [prototype-desc]
  ;; GameObject$PrototypeDesc in map format.
  (-> prototype-desc
      (update :components strip-default-scale-from-component-descs)
      (update :embedded-components strip-default-scale-from-component-descs)))

(g/defnk produce-save-value [proto-msg]
  (strip-default-scale-from-components-in-prototype-desc proto-msg))

(g/defnk produce-build-targets [_node-id resource dep-build-targets id-counts]
  (or (let [dup-ids (keep (fn [[id count]] (when (> count 1) id)) id-counts)]
        (game-object-common/maybe-duplicate-id-error _node-id dup-ids))
      (let [build-resource (workspace/make-build-resource resource)
            component-instance-build-targets (flatten dep-build-targets)
            component-instance-datas (mapv :instance-data component-instance-build-targets)
            component-build-targets (into []
                                          (comp (map #(dissoc % :instance-data))
                                                (util/distinct-by (comp resource/proj-path :resource)))
                                          component-instance-build-targets)]
        [(game-object-common/game-object-build-target build-resource _node-id component-instance-datas component-build-targets)])))

(g/defnk produce-scene [_node-id child-scenes]
  (game-object-common/game-object-scene _node-id child-scenes))

(defn- attach-component [self-id comp-id ddf-input resolve-id?]
  ;; self-id: GameObjectNode
  ;; comp-id: EmbeddedComponent, ReferencedComponent
  (concat
    (when resolve-id?
      (->> (g/node-value self-id :component-ids)
           keys
           (g/update-property comp-id :id outline/resolve-id)))
    (for [[from to] [[:node-outline :child-outlines]
                     [:_node-id :nodes]
                     [:build-targets :dep-build-targets]
                     [:resource-property-build-targets :resource-property-build-targets]
                     [:ddf-message ddf-input]
                     [:component-id :component-id-pairs]
                     [:scene :child-scenes]]]
      (g/connect comp-id from self-id to))
    (for [[from to] [[:base-url :base-url]
                     [:id-counts :id-counts]]]
      (g/connect self-id from comp-id to))))

(defn- attach-ref-component [self-id comp-id]
  ;; self-id: GameObjectNode
  ;; comp-id: ReferencedComponent
  (attach-component self-id comp-id :ref-ddf false))

(defn- attach-embedded-component [self-id comp-id]
  ;; self-id: GameObjectNode
  ;; comp-id: EmbeddedComponent
  (attach-component self-id comp-id :embed-ddf false))

(defn- outline-attach-ref-component [self-id comp-id]
  ;; self-id: GameObjectNode
  ;; comp-id: ReferencedComponent
  (attach-component self-id comp-id :ref-ddf true))

(defn- outline-attach-embedded-component [self-id comp-id]
  ;; self-id: GameObjectNode
  ;; comp-id: EmbeddedComponent
  (attach-component self-id comp-id :embed-ddf true))

(g/defnk produce-go-outline [_node-id child-outlines]
  {:node-id _node-id
   :node-outline-key "Game Object"
   :label "Game Object"
   :icon game-object-common/game-object-icon
   :children (outline/natural-sort child-outlines)
   :child-reqs [{:node-type ReferencedComponent
                 :tx-attach-fn outline-attach-ref-component}
                {:node-type EmbeddedComponent
                 :tx-attach-fn outline-attach-embedded-component}]})

(g/defnode GameObjectNode
  (inherits resource-node/ResourceNode)

  (input ref-ddf g/Any :array)
  (input embed-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input component-id-pairs g/IdPair :array)
  (input dep-build-targets g/Any :array)
  (input resource-property-build-targets g/Any :array)
  (input base-url g/Str)

  (output base-url g/Str (gu/passthrough base-url))
  (output node-outline outline/OutlineData :cached produce-go-outline)
  (output proto-msg g/Any produce-proto-msg)
  (output save-value g/Any produce-save-value)
  (output build-targets g/Any :cached produce-build-targets)
  (output resource-property-build-targets g/Any (gu/passthrough resource-property-build-targets))
  (output scene g/Any :cached produce-scene)
  (output component-ids g/Dict :cached (g/fnk [component-id-pairs] (reduce conj {} component-id-pairs)))
  (output ddf-component-properties g/Any :cached
          (g/fnk [ref-ddf]
            (into []
                  (keep (fn [component-ddf]
                          (when-some [properties (not-empty (:properties component-ddf))]
                            {:id (:id component-ddf)
                             :properties properties})))
                  ref-ddf)))
  (output id-counts g/Any :cached (g/fnk [component-id-pairs]
                                         (reduce (fn [res id]
                                                   (update res id (fn [id] (inc (or id 0)))))
                                                 {} (map first component-id-pairs)))))

(defn- gen-component-id [go-node base]
  (let [ids (map first (g/node-value go-node :component-ids))]
    (loop [postfix 0]
      (let [id (if (= postfix 0) base (str base postfix))]
        (if (empty? (filter #(= id %) ids))
          id
          (recur (inc postfix)))))))

(defn- add-component [self source-resource id {:keys [position rotation scale]} properties select-fn]
  (let [path {:resource source-resource
              :overrides properties}]
    (g/make-nodes (g/node-id->graph-id self)
                  [comp-node [ReferencedComponent :id id :position position :rotation rotation :scale scale]]
                  (g/set-property comp-node :path path)
                  (attach-ref-component self comp-node)
                  (when select-fn
                    (select-fn [comp-node])))))

(defn add-referenced-component! [go-id resource select-fn]
  (let [id (gen-component-id go-id (resource/base-name resource))]
    (g/transact
      (concat
        (g/operation-label "Add Component")
        (add-component go-id resource id game-object-common/identity-transform-properties [] select-fn)))))

(defn add-component-handler [workspace project go-id select-fn]
  (let [component-exts (keep (fn [[ext {:keys [tags :as _resource-type]}]]
                               (when (or (contains? tags :component)
                                         (contains? tags :embeddable))
                                 ext))
                             (workspace/get-resource-type-map workspace))]
    (when-let [resource (first (resource-dialog/make workspace project {:ext component-exts :title "Select Component File"}))]
      (add-referenced-component! go-id resource select-fn))))

(defn- selection->game-object [selection]
  (g/override-root (handler/adapt-single selection GameObjectNode)))

(handler/defhandler :add-from-file :workbench
  (active? [selection] (selection->game-object selection))
  (label [] "Add Component File")
  (run [workspace project selection app-view]
       (add-component-handler workspace project (selection->game-object selection) (fn [node-ids] (app-view/select app-view node-ids)))))

(defn- add-embedded-component [self project type data id {:keys [position rotation scale]} select-fn]
  (let [graph (g/node-id->graph-id self)
        resource (project/make-embedded-resource project :editable type data)
        node-type (project/resource-node-type resource)]
    (g/make-nodes graph [comp-node [EmbeddedComponent :id id :position position :rotation rotation :scale scale]
                         resource-node [node-type :resource resource]]
                  (project/load-node project resource-node node-type resource)
                  (project/connect-if-output node-type resource-node comp-node
                                             [[:_node-id :embedded-resource-id]
                                              [:resource :source-resource]
                                              [:_properties :source-properties]
                                              [:node-outline :source-outline]
                                              [:undecorated-save-data :save-data]
                                              [:scene :scene]
                                              [:build-targets :source-build-targets]])
                  (attach-embedded-component self comp-node)
                  (when select-fn
                    (select-fn [comp-node])))))

(defn add-embedded-component! [go-id resource-type select-fn]
  (let [project (project/get-project go-id)
        workspace (project/workspace project)
        template (workspace/template workspace resource-type)
        id (gen-component-id go-id (:ext resource-type))]
    (g/transact
      (concat
        (g/operation-label "Add Component")
        (add-embedded-component go-id project (:ext resource-type) template id game-object-common/identity-transform-properties select-fn)))))

(defn- add-embedded-component-handler [user-data select-fn]
  (let [go-id (:_node-id user-data)
        resource-type (:resource-type user-data)]
    (add-embedded-component! go-id resource-type select-fn)))

(defn add-embedded-component-label [user-data]
  (if-not user-data
    "Add Component"
    (let [rt (:resource-type user-data)]
      (or (:label rt) (:ext rt)))))

(defn embeddable-component-resource-types [workspace]
  (keep (fn [[_ext {:keys [tags] :as resource-type}]]
          (when (and (contains? tags :component)
                     (not (contains? tags :non-embeddable))
                     (workspace/has-template? workspace resource-type))
            resource-type))
        (workspace/get-resource-type-map workspace)))

(defn add-embedded-component-options [self workspace user-data]
  (when (not user-data)
    (->> (embeddable-component-resource-types workspace)
         (map (fn [res-type] {:label (or (:label res-type) (:ext res-type))
                              :icon (:icon res-type)
                              :command :add
                              :user-data {:_node-id self :resource-type res-type :workspace workspace}}))
         (sort-by :label)
         vec)))

(handler/defhandler :add :workbench
  (label [user-data] (add-embedded-component-label user-data))
  (active? [selection] (selection->game-object selection))
  (run [user-data app-view] (add-embedded-component-handler user-data (fn [node-ids] (app-view/select app-view node-ids))))
  (options [selection user-data]
           (let [self (selection->game-object selection)
                 workspace (:workspace (g/node-value self :resource))]
             (add-embedded-component-options self workspace user-data))))

(defn load-game-object [project self resource prototype]
  (let [workspace (project/workspace project)
        ext->embedded-component-resource-type (workspace/get-resource-type-map workspace)]
    (concat
      (for [component (:components prototype)
            :let [source-path (:component component)
                  source-resource (workspace/resolve-resource resource source-path)
                  resource-type (some-> source-resource resource/resource-type)
                  transform-properties (select-transform-properties resource-type component)
                  properties (:properties component)]]
        (add-component self source-resource (:id component) transform-properties properties nil))
      (for [{:keys [id type data] :as embedded-component-desc} (:embedded-components prototype)
            :let [resource-type (ext->embedded-component-resource-type type)
                  transform-properties (select-transform-properties resource-type embedded-component-desc)]]
        (add-embedded-component self project type data id transform-properties false)))))

(defn- sanitize-game-object [workspace prototype-desc]
  ;; GameObject$PrototypeDesc in map format.
  (let [ext->embedded-component-resource-type (workspace/get-resource-type-map workspace)]
    (game-object-common/sanitize-prototype-desc prototype-desc ext->embedded-component-resource-type :embed-data-as-strings)))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "go"
    :label "Game Object"
    :node-type GameObjectNode
    :ddf-type GameObject$PrototypeDesc
    :load-fn load-game-object
    :dependencies-fn (game-object-common/make-game-object-dependencies-fn #(workspace/get-resource-type-map workspace))
    :sanitize-fn (partial sanitize-game-object workspace)
    :icon game-object-common/game-object-icon
    :view-types [:scene :text]
    :view-opts {:scene {:grid true}}))
