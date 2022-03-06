(ns clojure-lsp.handlers
  (:require
   [clojure-lsp.config :as config]
   [clojure-lsp.crawler :as crawler]
   [clojure-lsp.db :as db]
   [clojure-lsp.feature.call-hierarchy :as f.call-hierarchy]
   [clojure-lsp.feature.clojuredocs :as f.clojuredocs]
   [clojure-lsp.feature.code-actions :as f.code-actions]
   [clojure-lsp.feature.code-lens :as f.code-lens]
   [clojure-lsp.feature.completion :as f.completion]
   [clojure-lsp.feature.document-symbol :as f.document-symbol]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.feature.format :as f.format]
   [clojure-lsp.feature.hover :as f.hover]
   [clojure-lsp.feature.linked-editing-range :as f.linked-editing-range]
   [clojure-lsp.feature.refactor :as f.refactor]
   [clojure-lsp.feature.rename :as f.rename]
   [clojure-lsp.feature.semantic-tokens :as f.semantic-tokens]
   [clojure-lsp.feature.signature-help :as f.signature-help]
   [clojure-lsp.feature.workspace-symbols :as f.workspace-symbols]
   [clojure-lsp.kondo :as lsp.kondo]
   [clojure-lsp.parser :as parser]
   [clojure-lsp.producer :as producer]
   [clojure-lsp.queries :as q]
   [clojure-lsp.settings :as settings]
   [clojure-lsp.shared :as shared]
   [clojure.pprint :as pprint]
   [taoensso.timbre :as log])
  (:import
   [java.net
    URL
    JarURLConnection]))

(set! *warn-on-reflection* true)

(defmacro process-after-changes [& body]
  `(let [~'_time (System/nanoTime)]
     (loop [backoff# 1]
       (if (> (quot (- (System/nanoTime) ~'_time) 1000000) 60000) ; one minute timeout
         (log/warn "Timeout waiting for changes for body")
         (if (:processing-changes @db/db)
           (do
             (Thread/sleep backoff#)
             (recur (min 200 (* 2 backoff#)))) ; 2^0, 2^1, ..., up to 200ms
           ~@body)))))

(defn initialize [project-root-uri client-capabilities client-settings work-done-token]
  (swap! db/db assoc :project-analysis-type :project-and-deps)
  (when project-root-uri
    (crawler/initialize-project
      project-root-uri
      client-capabilities
      client-settings
      {}
      work-done-token
      db/db)))

(defn did-open [{:keys [textDocument]}]
  (let [uri (:uri textDocument)
        text (:text textDocument)]
    (f.file-management/did-open uri text db/db true)
    (producer/refresh-test-tree (:producer @db/db) [uri]))
  nil)

(defn did-save [{:keys [textDocument]}]
  (swap! db/db #(assoc-in % [:documents textDocument :saved-on-disk] true)))

;; TODO wait for lsp4j release
#_(defn did-delete-files [{:keys [textDocument]}]
    (when (get-in @db/db [:documents textDocument :saved-on-disk])
      (swap! db/db #(update % :documents dissoc textDocument))))

(defn did-change [{:keys [textDocument contentChanges]}]
  (f.file-management/did-change (:uri textDocument) contentChanges (:version textDocument) db/db))

(defn did-close [{:keys [textDocument]}]
  (f.file-management/did-close textDocument db/db))

(defn did-change-watched-files [{:keys [changes]}]
  (f.file-management/did-change-watched-files changes db/db))

(defn completion [{:keys [textDocument position]}]
  (let [row (-> position :line inc)
        col (-> position :character inc)]
    (f.completion/completion textDocument row col db/db)))

(defn references [{:keys [textDocument position context]}]
  (let [row (-> position :line inc)
        col (-> position :character inc)]
    (mapv (fn [reference]
            {:uri (shared/filename->uri (:filename reference) db/db)
             :range (shared/->range reference)})
          (q/find-references-from-cursor (:analysis @db/db) (shared/uri->filename textDocument) row col (:includeDeclaration context) db/db))))

(defn completion-resolve-item [item]
  (f.completion/resolve-item item db/db))

(defn prepare-rename [{:keys [textDocument position]}]
  (let [[row col] (shared/position->line-column position)]
    (f.rename/prepare-rename textDocument row col db/db)))

(defn rename [{:keys [textDocument position newName]}]
  (let [[row col] (shared/position->line-column position)]
    (f.rename/rename textDocument newName row col db/db)))

(defn definition [{:keys [textDocument position]}]
  (let [[line column] (shared/position->line-column position)]
    (when-let [definition (q/find-definition-from-cursor (:analysis @db/db) (shared/uri->filename textDocument) line column db/db)]
      {:uri (shared/filename->uri (:filename definition) db/db)
       :range (shared/->range definition)})))

(defn declaration [{:keys [textDocument position]}]
  (let [[line column] (shared/position->line-column position)]
    (when-let [declaration (q/find-declaration-from-cursor (:analysis @db/db) (shared/uri->filename textDocument) line column db/db)]
      {:uri (shared/filename->uri (:filename declaration) db/db)
       :range (shared/->range declaration)})))

(defn implementation [{:keys [textDocument position]}]
  (let [[row col] (shared/position->line-column position)]
    (mapv (fn [implementation]
            {:uri (shared/filename->uri (:filename implementation) db/db)
             :range (shared/->range implementation)})
          (q/find-implementations-from-cursor (:analysis @db/db) (shared/uri->filename textDocument) row col db/db))))

(defn document-symbol [{:keys [textDocument]}]
  (let [filename (shared/uri->filename textDocument)
        analysis (:analysis @db/db)
        namespace-definition (->> (get analysis filename)
                                  (q/find-first (comp #{:namespace-definitions} :bucket)))]
    [{:name (or (some-> namespace-definition :name name)
                filename)
      :kind (f.document-symbol/element->symbol-kind namespace-definition)
      :range (shared/full-file-range)
      :selection-range (if namespace-definition
                         (shared/->scope-range namespace-definition)
                         (shared/full-file-range))
      :children (->> (q/find-var-definitions analysis filename true)
                     (mapv (fn [e]
                             (shared/assoc-some
                               {:name (-> e :name name)
                                :kind (f.document-symbol/element->symbol-kind e)
                                :range (shared/->scope-range e)
                                :selection-range (shared/->range e)
                                :tags (cond-> []
                                        (:deprecated e) (conj 1))}
                               :detail (when (:private e)
                                         "private")))))}]))

(defn document-highlight [{:keys [textDocument position]}]
  (process-after-changes
    (let [line (-> position :line inc)
          column (-> position :character inc)
          filename (shared/uri->filename textDocument)
          scoped-analysis (select-keys (:analysis @db/db) [filename])
          references (q/find-references-from-cursor scoped-analysis filename line column true db/db)]
      (mapv (fn [reference]
              {:range (shared/->range reference)})
            references))))

(defn workspace-symbols [{:keys [query]}]
  (f.workspace-symbols/workspace-symbols query db/db))

(defn ^:private server-info []
  (let [db-value @db/db]
    {:project-root-uri (:project-root-uri db-value)
     :project-settings (:project-settings db-value)
     :classpath-settings (:classpath-settings db-value)
     :classpath (:classpath db-value)
     :client-settings (:client-settings db-value)
     :final-settings (settings/all db/db)
     :cljfmt-raw (binding [*print-meta* true]
                   (with-out-str (pr (f.format/resolve-user-cljfmt-config db/db))))
     :port (or (:port db-value)
               "NREPL only available on :debug profile (`make debug-cli`)")
     :server-version (config/clojure-lsp-version)
     :clj-kondo-version (lsp.kondo/clj-kondo-version)
     :log-path (:log-path db-value)}))

(defn server-info-log []
  (producer/show-message
    (:producer @db/db)
    (with-out-str (pprint/pprint (server-info)))
    :info
    nil))

(def server-info-raw #'server-info)

(defn ^:private cursor-info [[doc-id line character]]
  (let [analysis (:analysis @db/db)
        elements (q/find-all-elements-under-cursor analysis (shared/uri->filename doc-id) (inc line) (inc character))]
    (shared/assoc-some {}
                       :elements (mapv (fn [e]
                                         (shared/assoc-some
                                           {:element e}
                                           :definition (q/find-definition analysis e db/db)
                                           :semantic-tokens (f.semantic-tokens/element->token-type e)))
                                       elements))))

(defn cursor-info-log [{:keys [textDocument position]}]
  (producer/show-message
    (:producer @db/db)
    (with-out-str (pprint/pprint (cursor-info [textDocument (:line position) (:character position)])))
    :info
    nil))

(defn cursor-info-raw [{:keys [textDocument position]}]
  (cursor-info [textDocument (:line position) (:character position)]))

(defn clojuredocs-raw [{:keys [symName symNs]}]
  (f.clojuredocs/find-docs-for symName symNs db/db))

(defn ^:private refactor [refactoring [doc-id line character & args] db]
  (let [row (inc (int line))
        col (inc (int character))
        ;; TODO Instead of v=0 should I send a change AND a document change
        v (get-in @db [:documents doc-id :v] 0)
        loc (some-> (parser/zloc-of-file @db doc-id)
                    (parser/to-pos row col))]
    (f.refactor/call-refactor {:refactoring (keyword refactoring)
                               :loc         loc
                               :uri         doc-id
                               :row         row
                               :col         col
                               :args        args
                               :version     v}
                              db)))

(defn execute-command [{:keys [command arguments]}]
  (cond
    (= command "server-info")
    (server-info-log)

    (= command "cursor-info")
    (cursor-info-log {:textDocument (nth arguments 0)
                      :position {:line (nth arguments 1)
                                 :character (nth arguments 2)}})

    (some #(= % command) f.refactor/available-refactors)
    (when-let [{:keys [edit show-document-after-edit]} (refactor command arguments db/db)]
      (producer/publish-workspace-edit (:producer @db/db) edit)
      (when show-document-after-edit
        (producer/show-document-request (:producer @db/db) show-document-after-edit))
      edit)))

(defn hover [{:keys [textDocument position]}]
  (let [[line column] (shared/position->line-column position)
        filename (shared/uri->filename textDocument)]
    (f.hover/hover filename line column db/db)))

(defn signature-help [{:keys [textDocument position _context]}]
  (let [[line column] (shared/position->line-column position)]
    (f.signature-help/signature-help textDocument line column db/db)))

(defn formatting [{:keys [textDocument]}]
  (f.format/formatting textDocument db/db))

(defn range-formatting [doc-id format-pos]
  (process-after-changes
    (f.format/range-formatting doc-id format-pos db/db)))

(defmulti extension (fn [method _] method))

(defmethod extension "dependencyContents"
  [_ doc-id]
  (let [url (URL. doc-id)
        connection ^JarURLConnection (.openConnection url)
        jar (.getJarFile connection)
        entry (.getJarEntry connection)]
    (with-open [stream (.getInputStream jar entry)]
      (slurp stream))))

(defn code-actions
  [{:keys [range context textDocument]}]
  (process-after-changes
    (let [diagnostics (-> context :diagnostics)
          line (-> range :start :line)
          character (-> range :start :character)
          row (inc line)
          col (inc character)
          root-zloc (parser/safe-zloc-of-file @db/db textDocument)
          client-capabilities (get @db/db :client-capabilities)]
      (f.code-actions/all root-zloc textDocument row col diagnostics client-capabilities db/db))))

(defn code-lens
  [{:keys [textDocument]}]
  (process-after-changes
    (f.code-lens/reference-code-lens textDocument db/db)))

(defn code-lens-resolve
  [{[text-document row col] :data range :range}]
  (f.code-lens/resolve-code-lens text-document row col range db/db))

(defn semantic-tokens-full
  [{:keys [textDocument]}]
  (process-after-changes
    (let [data (f.semantic-tokens/full-tokens textDocument db/db)]
      {:data data})))

(defn semantic-tokens-range
  [{:keys [textDocument] {:keys [start end]} :range}]
  (process-after-changes
    (let [range {:name-row (inc (:line start))
                 :name-col (inc (:character start))
                 :name-end-row (inc (:line end))
                 :name-end-col (inc (:character end))}
          data (f.semantic-tokens/range-tokens textDocument range db/db)]
      {:data data})))

(defn prepare-call-hierarchy
  [{:keys [textDocument position]}]
  (f.call-hierarchy/prepare textDocument
                            (inc (:line position))
                            (inc (:character position)) db/db))

(defn call-hierarchy-incoming
  [{:keys [item]}]
  (let [uri (:uri item)
        row (inc (-> item :range :start :line))
        col (inc (-> item :range :start :character))]
    (f.call-hierarchy/incoming uri row col db/db)))

(defn call-hierarchy-outgoing
  [{:keys [item]}]
  (let [uri (:uri item)
        row (inc (-> item :range :start :line))
        col (inc (-> item :range :start :character))]
    (f.call-hierarchy/outgoing uri row col db/db)))

(defn linked-editing-ranges
  [{:keys [textDocument position]}]
  (let [row (-> position :line inc)
        col (-> position :character inc)]
    (f.linked-editing-range/ranges textDocument row col db/db)))
