(ns autodoc-collect.load-files
  (:import [java.util.jar JarFile]
           [java.io File])
  (:require [clojure.set :as set]))

;;; Load all the files from the source. This is a little hacked up 
;;; because we can't just grab them out of the jar, but rather need 
;;; to load the files because of bug in namespace metadata

;;; Because clojure.string/split didn't always exist (re is a string here)
(defn split [s re] (seq (.split s re)))

;;; The following two functions are taken from find-namespaces which in turn is taken
;;; from contrib code. The there for more details.

(defn clojure-source-file?
  "Returns true if file is a normal file with a .clj extension."
  [#^File file]
  (and (.isFile file)
       (.endsWith (.getName file) ".clj")))

(defn find-clojure-sources-in-dir
  "Searches recursively under dir for Clojure source files (.clj).
  Returns a sequence of File objects, in breadth-first sort order."
  [#^File dir]
  ;; Use sort by absolute path to get breadth-first search.
  (sort-by #(.getAbsolutePath %)
           (filter clojure-source-file? (file-seq dir))))

(defn not-in [str regex-seq] 
  (loop [regex-seq regex-seq]
    (cond
      (nil? (seq regex-seq)) true
      (re-find (first regex-seq) str) false
      :else (recur (next regex-seq)))))

(defn file-to-ns [file]
  (find-ns (symbol (-> file
                       (.replaceFirst ".clj$" "")
                       (.replaceAll "/" ".")
                       (.replaceAll "_" "-")))))

(defn ns-to-file [ns]
  (str (-> (name ns)
           (.replaceAll "\\." "/")
           (.replaceAll "-" "_"))
       ".clj"))

(defn basename
  "Strip the .clj extension so we can pass the filename to load"
  [filename]
  (.substring filename 0 (- (.length filename) 4)))

;;; The namespace-lists here have the form {ns-name {var-name [var meta-var], ...}, ...}
;;; where ns-name and var-name are symbols, var is the actual var for the symbol and
;;; meta-var is the metadata seen for the var when it was first discovered.
;;; We filter it to only the defmulti vars

(defn get-multifns
  "Get the information for currently defined multimethods by scanning the namespaces"
  []
  (into {}
        (for [ns (all-ns)]
          [(ns-name ns)
           (into {} (for [[sym-name sym-var] (ns-interns ns)
                          :when (and
                                 (.isBound sym-var) ; bound was added in Clojure 1.2, so we don't use it
                                 (instance? clojure.lang.MultiFn @sym-var))]
                      [sym-name [sym-var (meta sym-var)]]))])))


(defn revert-multimethod-meta
  "Make sure that the metadata on multifunctions doesn't change after the first time we see it"
  [old-namespace-list namespace-list]
  (dorun
     (doseq [[ns-name ns-syms] old-namespace-list]
       (dorun
        (doseq [[sym-name [sym-var sym-meta]] ns-syms]
          ;; We could check to see if it changed, but why bother? The old value is always right.
          (alter-meta! sym-var (constantly sym-meta)))))))

(defn preserve-multifn-meta
  "This function works around the fact that defmulti drops metadata if its reloaded"
  [old-namespace-list]
  (let [namespace-list (get-multifns)]
    (revert-multimethod-meta old-namespace-list namespace-list)
    (let [new-namespaces (set/difference (set (keys namespace-list))
                                         (set (keys old-namespace-list)))]
      (merge old-namespace-list (select-keys namespace-list new-namespaces)))))

(defn load-files [filelist load-except-list]
  (loop [files (filter #(not-in % load-except-list) filelist)
         multi-info nil]
    (when-let [filename (first files)]
      (print (str filename ": "))
      (try
        (load-file filename)
        (println "done.")
        (catch Exception e
          (println  (str "failed (ex = " (.getMessage e) ")"))))
      (recur (next files) (preserve-multifn-meta multi-info)))))

(defn load-namespaces [root source-path load-except-list]
  (let [load-except-list (if (empty? load-except-list)
                           nil
                           (map re-pattern (split load-except-list ":")))]
    ;; The following line lets us load things like JFreeChart without having an X display
    (System/setProperty "java.awt.headless" "true")
    (load-files
     (map #(.getPath %)
          (mapcat
           #(find-clojure-sources-in-dir
             (File. root %))
           (split source-path ":")))
     load-except-list)))
