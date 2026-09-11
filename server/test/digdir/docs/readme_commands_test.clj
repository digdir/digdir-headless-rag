(ns digdir.docs.readme-commands-test
  "The commands the README tells a newcomer to run have to be runnable.

   A user walking the documented Docker path hit this: README step 6's
   corpus-fetch command omitted `-f docker-compose.newcomer.yml` and failed
   with `no configuration file provided: not found`. There is no default
   compose file at the repository root, so every compose invocation needs the
   flag, and every other command in that section had it.

   ## Why this is a SECOND guard rather than an extension of the first

   `digdir.setup.setup-env-script-test` already guards exactly this property —
   and could not have caught it. That one reads the commands `scripts/setup-env.sh`
   PRINTS. This one reads the commands `README.md` prints. Same defect, same
   check, two different doors, and the guard on one door says nothing about the
   other. `scripts/setup-env.sh`'s own header even documents this failure mode
   and cites that test as the thing preventing it, which is how the README came
   to carry the broken command while a green test claimed coverage.

   Kept deliberately narrow: it asserts that a compose command names a compose
   file, not that the whole command is correct. A guard that tried to validate
   every documented command against the real CLI would be a test of Docker."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private readme "../README.md")

(defn- shell-commands
  "Runnable shell commands in fenced ```sh blocks, with `\\` continuations
   joined so a command split across lines is read whole.

   Reading raw LINES would be blind here: the setup commands put
   `docker compose …` and the `java -cp …` they run on separate lines, so a
   per-line check sees neither command entire."
  [src]
  (let [lines (str/split-lines src)]
    (loop [[l & more] lines, in-sh? false, cur nil, acc []]
      (cond
        (nil? l) (mapv str/trim (cond-> acc cur (conj cur)))
        (str/starts-with? l "```") (recur more (= (str/trim l) "```sh") nil acc)
        (not in-sh?) (recur more in-sh? nil acc)
        (str/ends-with? (str/trimr l) "\\")
        (recur more in-sh?
               (str (or cur "") (str/replace (str/trimr l) #"\\$" "") " ") acc)
        cur (recur more in-sh? nil (conj acc (str cur l)))
        :else (recur more in-sh? nil (conj acc l))))))

(deftest the-reader-can-see-the-readme-and-its-commands
  (testing "instrument check — without this the guards below pass vacuously"
    (let [f (io/file readme)]
      (is (.exists f) (str "not found at " readme))
      (let [cmds (shell-commands (slurp f))]
        (is (<= 5 (count cmds))
            (str "only " (count cmds) " shell commands parsed from the README"))
        (is (some #(str/includes? % "docker compose") cmds)
            "no docker compose command parsed; the fence reader is broken")))))

(deftest every-documented-compose-command-names-a-compose-file
  (testing "There is no default compose file at the repository root, so a bare
            `docker compose …` fails at 'no configuration file provided' before
            it does anything else."
    (doseq [cmd (filter #(str/starts-with? % "docker compose")
                        (shell-commands (slurp (io/file readme))))]
      (is (str/includes? cmd "-f docker-compose.")
          (str "README documents a compose command with no -f, which cannot "
               "run as written: " cmd)))))
