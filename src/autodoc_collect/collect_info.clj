(ns autodoc-collect.collect-info
  (:use [autodoc-collect.load-files :only (load-namespaces)]))

;; Build a single structure representing all the info we care about concerning
;; namespaces and their members
;;
;; Assumes that all the relevant namespaces have already been loaded

;; namespace: { :full-name :short-name :doc :author :members :subspaces :see-also}
;; vars: {:name :doc :arglists :var-type :file :line :added :deprecated :dynamic :forms}

;; collect-info is special in that it is run as a separate process to run in
;; environment of the code being documented. What's particularly important is
;; that it needs to run in various versions of Clojure and depend as little
;; as possible on the differences.

;; Because of this, it's important that it doesn't use any AOT'ed code.


(def post-1-2? (let [{:keys [major minor]} *clojure-version*]
                 (or (>= major 2) (and (= major 1) (>= minor 2)))))

(def post-1-3? (let [{:keys [major minor]} *clojure-version*]
                 (or (>= major 2) (and (= major 1) (>= minor 3)))))

(def post-1-8? (let [{:keys [major minor]} *clojure-version*]
                 (or (>= major 2) (and (= major 1) (>= minor 8)))))

(defmacro defdynamic [var init]
  `(do
     (def  ~var ~init)
     (when post-1-3? (.setDynamic #'~var))))

(defdynamic saved-out nil)

(defn debug [& args]
  (binding [*out* (or saved-out *out*)]
    (apply println args)))

(if post-1-2?
  (do
    (load "reflect")
    (refer 'autodoc-collect.reflect :only '[reflect]))
  (defn reflect [obj & options]))

(if post-1-8?
  (require '[clojure.spec :refer [fn-specs describe]])
  (do
    (defn fn-specs [v])
    (defn describe [spec])))

(defn ns-to-class-name
  "Convert the namespece name into a class root name"
  [ns]
  (.replace (name (ns-name ns)) "-" "_"))

(defn class-to-ns-name
  "Convert a class to the corresponding namespace name"
  [ns]
  (.replace (name (ns-name ns)) "_" "-"))

(defn remove-leading-whitespace
  "Find out what the minimum leading whitespace is for a doc block and remove it.
We do this because lots of people indent their doc blocks to the indentation of the
string, which looks nasty when you display it."
  [s]
  (when s
    (let [lines (.split s "\\n")
          prefix-lens (map #(count (re-find #"^ *" %))
                           (filter #(not (= 0 (count %)))
                                   (next lines)))
          min-prefix (when (seq prefix-lens) (apply min prefix-lens))
          regex (when min-prefix (apply str "^" (repeat min-prefix " ")))]
      (if regex
        (apply str (interpose "\n" (map #(.replaceAll % regex "") lines)))
        s))))

(defn my-every-pred
  "every-pred didn't exist before clojure-1.3, so we replicate it here"
  [& ps]
  (fn [& args]
    (every? #(every? % args) ps)))

(defn protocol?
  "Return true if the var is a protocol definition. The only way we can tell
this is by looking at the map and seeing if it has the right keys, which
may not be foolproof."
  [v]
  (and v
       (.isBound v)
       (map? @v)
       (try
         ((my-every-pred :on-interface :on :sigs :var :method-map :method-builders) @v)
         (catch UnsupportedOperationException e
           false))))

(defn class-to-var
  "Take a class object that points to a var and return the Var object"
  [cls]
  (let [className (.replace (.getName cls) "_" "-")
        dot (.lastIndexOf className ".")
        ns (.substring className 0 dot)
        sym (.substring className (inc dot))]
    (when (find-ns (symbol ns))
      (find-var (symbol ns sym)))))

(defn protocol-class?
  "Return true if the class represents a protocol. We resolve this by finding the
associated var"
  [cls]
  (protocol? (class-to-var cls)))

(defn var-type
  "Determing the type (var, function, macro, protocol) of a var from the metadata and
return it as a string."
  [v]
  (cond (:special-form (meta v)) "special form"
        (:macro (meta v)) "macro"
        (instance? clojure.lang.MultiFn @v) "multimethod"
        (:arglists (meta v)) "function"
        (:forms (meta v)) "type alias"
        (protocol? v) "protocol"
        :else "var"))

(defn has-vals
  "If any of the vals in m are non-nil, return m, else nil"
  [m]
  (if (seq (disj (set (vals m)) nil))
    m
    nil))

(defn var-specs
  "Get {:args ..., :ret ..., :fn } spec for v or nil if none"
  [v]
  (when-let [s (has-vals (fn-specs v))]
    (reduce (fn [m [k v]] (assoc m k (describe v))) {} s)))

(defn vars-for-ns
  "Returns a seq of vars in ns that should be documented"
  [ns]
  (for [v (sort-by (comp :name meta) (vals (ns-interns ns)))
        :when (and (or (:wiki-doc (meta v)) (:doc (meta v)))
                   (not (protocol? v))
                   (not (:protocol (meta v)))
                   (not (:skip-wiki (meta v)))
                   (not (:private (meta v))))]
    v))

;; This is copied from clojure.repl and provides the documentation
;; for the "undocumented" special forms.
(def special-doc-map
  '{. {:url "java_interop#dot"
       :forms [(.instanceMember instance args*)
               (.instanceMember Classname args*)
               (Classname/staticMethod args*)
               Classname/staticField]
       :doc "The instance member form works for both fields and methods.
They all expand into calls to the dot operator at macroexpansion time."}
    def {:forms [(def symbol doc-string? init?)]
         :doc "Creates and interns a global var with the name
of symbol in the current namespace (*ns*) or locates such a var if
it already exists.  If init is supplied, it is evaluated, and the
root binding of the var is set to the resulting value.  If init is
not supplied, the root binding of the var is unaffected."}
    do {:forms [(do exprs*)]
        :doc "Evaluates the expressions in order and returns the value of
the last. If no expressions are supplied, returns nil."}
    if {:forms [(if test then else?)]
        :doc "Evaluates test. If not the singular values nil or false,
evaluates and yields then, otherwise, evaluates and yields else. If
else is not supplied it defaults to nil."}
    monitor-enter {:forms [(monitor-enter x)]
                   :doc "Synchronization primitive that should be avoided
in user code. Use the 'locking' macro."}
    monitor-exit {:forms [(monitor-exit x)]
                  :doc "Synchronization primitive that should be avoided
in user code. Use the 'locking' macro."}
    new {:forms [(Classname. args*) (new Classname args*)]
         :url "java_interop#new"
         :doc "The args, if any, are evaluated from left to right, and
passed to the constructor of the class named by Classname. The
constructed object is returned."}
    quote {:forms [(quote form)]
           :doc "Yields the unevaluated form."}
    recur {:forms [(recur exprs*)]
           :doc "Evaluates the exprs in order, then, in parallel, rebinds
the bindings of the recursion point to the values of the exprs.
Execution then jumps back to the recursion point, a loop or fn method."}
    set! {:forms[(set! var-symbol expr)
                 (set! (. instance-expr instanceFieldName-symbol) expr)
                 (set! (. Classname-symbol staticFieldName-symbol) expr)]
          :url "vars#set"
          :doc "Used to set thread-local-bound vars, Java object instance
fields, and Java class static fields."}
    throw {:forms [(throw expr)]
           :doc "The expr is evaluated and thrown, therefore it should
yield an instance of some derivee of Throwable."}
    try {:forms [(try expr* catch-clause* finally-clause?)]
         :doc "catch-clause => (catch classname name expr*)
finally-clause => (finally expr*)

Catches and handles Java exceptions."}
    var {:forms [(var symbol)]
         :doc "The symbol must resolve to a var, and the Var object
itself (not its value) is returned. The reader macro #'x
expands to (var x)."}})

(defn specials
  "Adds the special form and syntax info to clojure.core docs (just returns
nil everywhere else)."
  [ns]
  (when (= (ns-name ns) 'clojure.core)
    (concat
     (for [[special-name special-meta] special-doc-map]
       {:name (str special-name)
        :var-type "special form"
        :doc (str (:doc special-meta)
                  (if (contains? special-meta :url)
                    (when-let [url (:url special-meta)]
                      (str "\n\nPlease see http://clojure.org/" url))
                    (str "\n\nPlease see http://clojure.org/special_forms#"
                         special-name)))
        :forms (:forms special-meta)
        :added "1.0"})
     (for [[special-syntax special-name] '{& fn catch try finally try}]
       {:name (str special-syntax)
        :var-type "special syntax"
        :doc (str "Syntax for use with " special-name
                  ".\n\nPlease see http://clojure.org/special_forms#"
                  special-name)
        :added "1.0"}))))

(defn var-info
  "Get the metadata info for a single var v"
  [v]
  (merge (select-keys (meta v) [:arglists :file :line
                                :added :deprecated :dynamic
                                :forms])
         {:name (name (:name (meta v)))
          :doc (remove-leading-whitespace (:doc (meta v))),
          :var-type (var-type v)
          :specs (var-specs v)}))

(defn vars-info
  "Get a seq of var-info for all the vars in a namespace that should be documented."
  [ns]
  (sort-by
   :name
   (concat
    (for [v (vars-for-ns ns)]
      (var-info v))
    (specials ns))))

(let [primitive-map {Boolean/TYPE 'booleans,
                     Character/TYPE 'characters,
                     Byte/TYPE 'bytes,
                     Short/TYPE 'shorts,
                     Integer/TYPE 'ints,
                     Long/TYPE 'longs,
                     Float/TYPE 'floats,
                     Double/TYPE 'doubles,
                     Void/TYPE 'voids}]
  (defn expand-array-types
    "Expand array types to create a symbol like array-of-bytes so that it can parse
   (since [B doesn't parse as a symbol). Non-arrays are returned as is."
    [#^Class cls]
    (cond
     (nil? cls) nil
     (.isArray cls) (symbol
                     (str "array-of-"
                          (name (expand-array-types
                                 (.getComponentType cls)))))
     (= cls java.lang.Object) 'Object
     (contains? primitive-map cls) (primitive-map cls)
     :else cls)))

(defn protos-for-ns
  "Find all the protocols in the namespace"
  [ns]
  (for [v (sort-by (comp :name meta) (vals (ns-interns ns)))
        :when (and (protocol? v)
                   (or (:wiki-doc (meta v)) (:doc (meta v))
                       (seq (filter identity
                                    (map (comp :doc second)
                                         (:sigs @v))))))]
    v))

(defn proto-vars-info
  "Get the expanded list of functions for this protocol"
  [proto-var ns]
  (for [v (sort-by (comp :name meta) (vals (ns-interns ns)))
        :when (= (:protocol (meta v)) proto-var)]
    (var-info v)))

(defn protos-info
  "Build the info structure for the protocols"
  [ns]
  (for [p (protos-for-ns ns)]
    (merge (select-keys (meta p) [:file :line :added :deprecated])
           {:name (name (:name (meta p)))
            :doc (remove-leading-whitespace (:doc (meta p))),
            :var-type (var-type p)
            :fns (proto-vars-info p ns)
            :known-impls (map expand-array-types (keys (:impls @p)))})))

(defn types-for-ns
  "Discover the types and records in ns"
  [ns]
  ;; We rely on the fact that deftype creates a factory function in
  ;; the form ->TypeName to find the defined types in this namespace.
  (let [names (map #(.substring % 2)
                   (filter #(.startsWith % "->")
                           (sort
                            (map name
                                 (keys (ns-interns ns))))))
        ns-prefix (ns-to-class-name ns)
        ns-map (into
                {}
                (filter
                 second
                 (for [n names]
                   [n (try
                        (when-let [cls (Class/forName (str ns-prefix "." n))]
                          (reflect cls))
                        (catch Exception e))])))]
    (sort-by first ns-map)))

(def interfaces-to-skip #{'clojure.lang.IType 'clojure.lang.IRecord})

(defn types-info
  "Create the info structure for all the types in the namespace"
  [ns]
  (for [[type-name reflect-info] (types-for-ns ns)]
    (let [protocols (set (filter protocol-class? (:bases reflect-info)))
          record? ((:bases reflect-info) 'clojure.lang.IRecord)]
      {:name type-name
       :protocols (sort protocols)
       :interfaces (sort
                    (filter #(and (not (interfaces-to-skip %))
                                  (not (protocols %))
                                  (.isInterface (Class/forName (name %))))
                            (:bases reflect-info)))
       :var-type (if record? "record" "type")
       ;; Get the fields from the constructor function so they're in the right order
       :fields (first (:arglists (meta (get (ns-interns ns) (symbol (str "->" type-name))))))})))

(defn add-vars [ns-info]
  (merge ns-info {:members (vars-info (:ns ns-info))
                  :protocols (protos-info (:ns ns-info))
                  :types (types-info (:ns ns-info))}))

(defn relevant-namespaces [namespaces-to-document]
  (filter #(not (:skip-wiki (meta %)))
          (map #(find-ns (symbol %))
               (filter #(some (fn [n] (or (= % n) (.startsWith % (str n "."))))
                              (seq (.split namespaces-to-document ":")))
                       (sort (map #(name (ns-name %)) (all-ns)))))))

(defn trim-ns-name [s trim-prefix]
  (if (and trim-prefix (.startsWith s trim-prefix))
    (subs s (count trim-prefix))
    s))

(defn base-namespace
  "A nasty function that finds the shortest prefix namespace of this one"
  [ns relevant]
  (first
   (drop-while
    (comp not identity)
    (map #(let [ns-part (find-ns (symbol %))]
            (if (and (not (:skip-wiki (meta ns-part)))
                     (relevant ns-part))
              ns-part))
         (let [parts (seq (.split (name (ns-name ns)) "\\."))]
           (map #(apply str (interpose "." (take (inc %) parts)))
                (range 0 (count parts)))))))) ;; TODO first arg to range was 0 for contrib

(defn base-relevant-namespaces [namespaces-to-document]
  (let [relevant (relevant-namespaces namespaces-to-document)
        relevant-set (set relevant)]
    (filter #(= % (base-namespace % relevant-set)) relevant)))

(defn sub-namespaces
  "Find the list of namespaces that are sub-namespaces of this one. That is they
have the same prefix followed by a . and then more components"
  [ns]
  (let [pat (re-pattern (str (.replaceAll (name (ns-name ns)) "\\." "\\.") "\\..*"))]
    (sort-by
     #(name (ns-name %))
     (filter #(and (not (:skip-wiki (meta %)))
                   (re-matches pat (name (ns-name %))))
             (all-ns)))))

(defn ns-short-name [ns trim-prefix]
  (trim-ns-name (name (ns-name ns)) trim-prefix))

(defn build-ns-entry [ns trim-prefix]
  (merge (select-keys (meta ns) [:author :see-also :added :deprecated])
         {:full-name (name (ns-name ns)) :short-name (ns-short-name ns trim-prefix)
          :doc (remove-leading-whitespace (:doc (meta ns))) :ns ns}))

(defn build-ns-list [nss trim-prefix]
  (sort-by :short-name (map add-vars (map #(build-ns-entry % trim-prefix) nss))))

(defn add-subspaces [info trim-prefix]
     (assoc info :subspaces
            (filter #(or (:doc %) (seq (:members %))
                         (seq (:types %)) (seq (:protocols %)))
                    (build-ns-list (sub-namespaces (:ns info)) trim-prefix))))

(defn add-base-ns-info [ns]
  (assoc ns
    :base-ns (:short-name ns)
    :subspaces (map #(assoc % :base-ns (:short-name ns)) (:subspaces ns))))

(defn clean-ns-info
  "Remove the back pointers to the namespace from the ns-info"
  [ns-info]
  (map (fn [ns] (assoc (dissoc ns :ns)
                  :subspaces (map #(dissoc % :ns) (:subspaces ns))))
       ns-info))

(defn project-info [namespaces-to-document trim-prefix]
  (clean-ns-info
   (map add-base-ns-info
        (map #(add-subspaces % trim-prefix)
             (build-ns-list (base-relevant-namespaces namespaces-to-document) trim-prefix)))))

(defn writer
  "A version of duck-streams/writer that only handles file strings. Moved here for
versioning reasons"
  [s]
  (java.io.PrintWriter.
   (java.io.BufferedWriter.
    (java.io.OutputStreamWriter.
     (java.io.FileOutputStream. (java.io.File. s) false)
     "UTF-8"))))


(defn collect-info-to-file
  "build the file out-file with all the namespace info for the project described by the arguments"
  [root source-path namespaces-to-document load-except-list trim-prefix out-file branch-name]
  (load-namespaces root source-path load-except-list)
  (with-open [w (writer "/tmp/autodoc-debug.clj")] ; this is basically spit, but we do it
                                        ; here so we don't have clojure version issues
    (binding [saved-out *out*]
      (binding [*out* w]
        (pr (project-info namespaces-to-document trim-prefix)))))
  (with-open [w (writer out-file)] ; this is basically spit, but we do it
                                        ; here so we don't have clojure version issues
    (binding [saved-out *out*]
      (binding [*out* w]
        (pr (project-info namespaces-to-document trim-prefix))))))
