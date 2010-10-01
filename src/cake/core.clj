(ns cake.core
  (:use cake cake.utils.useful cake.file
        [clojure.string :only [join trim]])
  (:require cake.project
            [cake.ant :as ant]
            [cake.server :as server])
  (:import [java.io File FileReader InputStreamReader OutputStreamWriter BufferedReader FileNotFoundException]
           [org.apache.tools.ant.taskdefs ExecTask]
           [java.net Socket SocketException]))

(defn newer? [& args]
  (apply > (for [arg args]
             (if (number? arg)
               arg
               (.lastModified (if (string? arg) (file arg) arg))))))

(defmacro try-load [form]
  `(try ~form
        (catch Exception e#
          (when (seq (.listFiles (file "lib")))
            (print-stacktrace e#)))))

(defmacro defproject [name version & args]
  (let [opts (apply hash-map args)
        [tasks task-opts] (split-with symbol? (:tasks opts))
        task-opts (apply hash-map task-opts)]
    `(do (alter-var-root #'*project* (fn [_#] (cake.project/create '~name ~version '~opts)))
         (alter-var-root #'*context* (fn [_#] (get-context nil)))
         (require '~'[cake.tasks help jar test compile dependencies release swank core version])
         ~@(for [ns tasks]
             `(try-load (require '~ns)))
         (undeftask ~@(:exclude task-opts)))))

(defn update-task [task deps doc action]
  (let [task (or task {:actions [] :deps #{} :doc []})]
    (-> task
        (update :deps    into deps)
        (update :doc     into doc)
        (update :actions conj action))))

(defonce tasks (atom {}))
(def run? nil)

(def implicit-tasks
  {'repl     ["Start an interactive shell with history and tab completion."]
   'stop     ["Stop cake jvm processes."]
   'start    ["Start cake jvm processes."]
   'restart  ["Restart cake jvm processes."]
   'reload   ["Reload any .clj files that have changed or restart."]
   'upgrade  ["Upgrade cake to the most current version."]
   'ps       ["List running cake jvm processes for all projects."]
   'kill     ["Kill running cake jvm processes. Use -9 to force or all for all projects."]
   'eval     ["Eval the given forms in the project JVM." "Read forms from stdin if - is provided."]
   'run      ["Execute a script in the project jvm."]
   'filter   ["Thread each line in stdin through the given forms, printing the results."
              "The line is passed as a string with a trailing newline, and println is called with the result of the final form."]})

(defn parse-task-opts [forms]
  (let [[deps forms] (if (set? (first forms))
                      [(first forms) (rest forms)]
                      [#{} forms])
        deps (map #(if-not (symbol? %) (eval %) %) deps)
        [doc forms] (split-with string? forms)]
    {:deps deps :body forms :doc doc}))

(defn parse-body [forms]
  (let [[destruct forms] (if (map? (first forms))
                           [(first forms) (rest forms)]
                           [{} forms])
        [pred forms] (if (= :when (first forms))
                       `[~(second forms) ~(drop 2 forms)]
                       [true forms])]
    {:destruct destruct :pred pred :actions forms}))

(defmacro deftask
  "Define a cake task. Each part of the body is optional. Task definitions can
   be broken up among multiple deftask calls and even multiple files:
   (deftask foo #{bar baz} ; a set of prerequisites for this task
     \"Documentation for task.\"
     {foo :foo} ; destructuring of *opts*
     (do-something)
     (do-something-else))"
  [name & forms]
  (verify (not (implicit-tasks name)) (str "Cannot redefine implicit task: " name))
  (let [{:keys [deps body doc] :as parsed-opts} (parse-task-opts forms)
        {:keys [destruct pred actions]} (parse-body body)]
    `(swap! tasks update '~name update-task '~deps '~doc (fn [~destruct] (when ~pred ~@actions)))))

(defmacro defile
  "Define a file task. Uses the same syntax as deftask, however the task name
   is a string representing the name of the file to be generated by the body.
   Source files may be specified in the dependencies set, in which case
   the file task will only be ran if the source is newer than the destination.
   (defile \"main.o\" #{\"main.c\"}
     (sh \"cc\" \"-c\" \"-o\" \"main.o\" \"main.c\"))"
  [name & forms]
  (let [{:keys [deps body doc]} (parse-task-opts forms)
        {:keys [destruct pred actions]} (parse-body body)
        {file-deps true task-deps false} (group-by string? deps)]
    `(swap! tasks update '~name update-task '~deps '~doc
            (fn [~destruct]
              (let [f# (file ~name)]
                (when (and (or (not (.exists f#))
                               (seq (filter (partial mtime< f#)
                                            (into ~file-deps
                                                  (map #(file ".cake" "run" (name %))
                                                       '~task-deps))))
                               (nil? (seq ~deps))))
                           ~pred)
                  ~@actions)))))

(defmacro undeftask [& names]
  `(swap! tasks dissoc ~@(map #(list 'quote %) names)))

(defmacro remove-dep! [task dep]
  `(swap! tasks update-in ['~task :deps] disj '~dep))

(defn run-task
  "Execute the specified task after executing all prerequisite tasks."
  [name]
  (let [task (@tasks name)]
    (if (and (nil? task)
             (not (string? name)))
      (println "unknown task:" name)
      (verify (not= :in-progress (run? name)) (str "circular dependency found in task: " name)
        (when-not (run? name)
          (set! run? (assoc run? name :in-progress))
          (doseq [dep (:deps task)] (run-task dep))
          (binding [*current-task* name
                    *File* (if-not (symbol? name) (file name))]
            (doseq [action (:actions task)] (action *opts*)))
          (set! run? (assoc run? name true))
          (if (symbol? name)
            (touch (file ".cake" "run" name))))))))

(defmacro invoke [name & [opts]]
  `(binding [*opts* (or ~opts *opts*)]
     (run-task '~name)))

(defn- bake-connect [port]
  (loop []
    (if-let [socket (try (Socket. "localhost" (int port)) (catch SocketException e))]
      socket
      (recur))))

(defn- quote-if
  "We need to quote the binding keys so they are not evaluated within the bake syntax-quote and the
   binding values so they are not evaluated in the bake* syntax-quote. This function makes that possible."
  [pred bindings]
  (reduce
   (fn [v form]
     (if (pred (count v))
       (conj v (list 'quote form))
       (conj v form)))
   [] bindings))

(defn bake-port []
  (try (Integer/parseInt
        (trim (second (.split (slurp (file ".cake" "bake.pid")) "\n"))))
       (catch FileNotFoundException e
         nil)))

(defn bake* [ns-forms bindings body]
  (let [port (bake-port)]
    (verify port "bake not supported. perhaps you don't have a project.clj")
    (let [ns     (symbol (str "bake.task." (name *current-task*)))
          body  `(~'let ~(quote-if odd? bindings) ~@body)
          socket (bake-connect port)
          reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
          writer (OutputStreamWriter. (.getOutputStream socket))]
      (doto writer
        (.write (prn-str [ns ns-forms body] *vars*))
        (.flush))
      (loop [line (.readLine reader)]
        (when-not (or (nil? line) (= ":bake.core/result" line))
          (println line)
          (recur (.readLine reader))))
      (let [line   (.readLine reader)
            result (rescue (read-string line) line)]
        (flush)
        (.close socket)
        result))))

(defmacro bake
  "Execute code in a separate jvm with the classpath of your projects. Bindings allow
   passing state to the project jvm. Namespace forms like use and require must be
   specified before bindings."
  {:arglists '([ns-forms* bindings body*])}
  [& forms]
  (let [[ns-forms [bindings & body]] (split-with (complement vector?) forms)]
    `(bake* '~ns-forms ~(quote-if even? bindings) '~body)))

(defn cake-exec [& args]
  (ant/ant ExecTask {:executable "ruby" :dir *root* :failonerror true}
    (ant/args *script* args (str "--project=" *root*))))

(defn bake-restart []
  (ant/log "Restarting project jvm.")
  (cake-exec "restart" "project"))

(defn git [& args]
  (if (.exists (file ".git"))
    (ant/ant ExecTask {:executable "git" :dir *root* :failonerror true}
      (ant/args args))
    (println "warning:" *root* "is not a git repository")))

(def *readline-marker* nil)

(defn process-command [[task readline-marker]]
  (binding [*readline-marker* readline-marker, run? {}]
    (ant/in-project
     (doseq [dir ["lib" "classes" "build"]]
       (.mkdirs (file dir)))
     (run-task (symbol (name task))))))

(defn task-file? [file]
  (some (partial re-matches #".*\(deftask .*|.*\(defproject .*")
        (line-seq (BufferedReader. (FileReader. file)))))

(defn skip-task-files [load-file]
  (fn [file]
    (if (task-file? file)
      (println "reload-failed: unable to reload file with deftask or defproject in it:" file)
      (load-file file))))

(defn reload-files []
  (binding [clojure.core/load-file (skip-task-files load-file)]
    (server/reload-files)))

(defn repl []
  (binding [*current-task* "repl"]
    (ant/in-project (server/repl))))

(defn prompt-read [prompt & opts]
  (let [opts (apply hash-map opts)
        echo (if (false? (:echo opts)) "@" "")]
    (println (str echo *readline-marker* prompt))
    (read-line)))

(defn start-server [port]
  (in-ns 'cake.core)
  (cake.project/init "project.clj")
  (try-load (cake.project/init "tasks.clj"))
  (let [global-project (File. (System/getProperty "user.home") ".cake")]
    (when-not (= (.getPath global-project) (System/getProperty "cake.project"))
      (cake.project/init (.getPath (File. global-project "tasks.clj")))))
  (when-not *project* (require '[cake.tasks help new]))
  (when (= "global" (:artifact-id *project*))
    (undeftask test autotest jar uberjar war uberwar install release)
    (require '[cake.tasks new]))
  (server/init-multi-out ".cake/cake.log")
  (server/create port process-command :reload reload-files :repl repl)
  nil)
