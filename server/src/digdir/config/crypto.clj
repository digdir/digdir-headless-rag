(ns digdir.config.crypto
  "AES-256-GCM encryption utilities for config secrets.

   Uses:
   - AES-256-GCM for authenticated encryption
   - PBKDF2 with SHA-256 for key derivation (100k iterations)
   - Random salt and IV for each encryption

   Storage format: Base64(salt[16] || iv[12] || ciphertext+tag)"
  (:import [javax.crypto Cipher SecretKeyFactory]
           [javax.crypto.spec GCMParameterSpec PBEKeySpec SecretKeySpec]
           [java.security SecureRandom]
           [java.util Base64]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private algorithm "AES/GCM/NoPadding")
(def ^:private key-algorithm "AES")
(def ^:private kdf-algorithm "PBKDF2WithHmacSHA256")
(def ^:private key-length 256)        ; 256-bit AES key
(def ^:private salt-length 16)        ; 128-bit salt
(def ^:private iv-length 12)          ; 96-bit IV (recommended for GCM)
(def ^:private tag-length 128)        ; 128-bit authentication tag
(def ^:private kdf-iterations 100000) ; PBKDF2 iterations

;; =============================================================================
;; Key Derivation
;; =============================================================================

(defn- derive-key
  "Derive an AES-256 key from master password using PBKDF2-SHA256."
  [^String master-key ^bytes salt]
  (let [factory (SecretKeyFactory/getInstance kdf-algorithm)
        spec (PBEKeySpec. (.toCharArray master-key) salt kdf-iterations key-length)
        secret (.generateSecret factory spec)]
    (SecretKeySpec. (.getEncoded secret) key-algorithm)))

;; =============================================================================
;; Encryption
;; =============================================================================

(defn encrypt
  "Encrypt a plaintext string using AES-256-GCM.

   Args:
     plaintext  - String to encrypt
     master-key - Master key for key derivation

   Returns:
     Base64-encoded string: salt(16) || iv(12) || ciphertext+tag

   Example:
     (encrypt \"my-secret-api-key\" \"master-password-from-env\")"
  [^String plaintext ^String master-key]
  (let [;; Generate random salt and IV
        salt (byte-array salt-length)
        iv (byte-array iv-length)
        random (SecureRandom.)
        _ (.nextBytes random salt)
        _ (.nextBytes random iv)

        ;; Derive key from master password
        key (derive-key master-key salt)

        ;; Encrypt with AES-GCM
        cipher (doto (Cipher/getInstance algorithm)
                 (.init Cipher/ENCRYPT_MODE key (GCMParameterSpec. tag-length iv)))
        ciphertext (.doFinal cipher (.getBytes plaintext "UTF-8"))

        ;; Concatenate: salt || iv || ciphertext+tag
        result (byte-array (+ salt-length iv-length (alength ciphertext)))]
    (System/arraycopy salt 0 result 0 salt-length)
    (System/arraycopy iv 0 result salt-length iv-length)
    (System/arraycopy ciphertext 0 result (+ salt-length iv-length) (alength ciphertext))
    (.encodeToString (Base64/getEncoder) result)))

;; =============================================================================
;; Decryption
;; =============================================================================

(defn decrypt
  "Decrypt a Base64-encoded encrypted value.

   Args:
     encrypted-b64 - Base64-encoded encrypted string from encrypt
     master-key    - Master key for key derivation

   Returns:
     Decrypted plaintext string

   Throws:
     Exception if decryption fails (wrong key, tampered data, etc.)

   Example:
     (decrypt encrypted-value \"master-password-from-env\")"
  [^String encrypted-b64 ^String master-key]
  (let [;; Decode Base64
        combined (.decode (Base64/getDecoder) encrypted-b64)

        ;; Extract salt, iv, and ciphertext
        salt (byte-array salt-length)
        iv (byte-array iv-length)
        ciphertext (byte-array (- (alength combined) salt-length iv-length))]
    (System/arraycopy combined 0 salt 0 salt-length)
    (System/arraycopy combined salt-length iv 0 iv-length)
    (System/arraycopy combined (+ salt-length iv-length) ciphertext 0 (alength ciphertext))

    ;; Derive key and decrypt
    (let [key (derive-key master-key salt)
          cipher (doto (Cipher/getInstance algorithm)
                   (.init Cipher/DECRYPT_MODE key (GCMParameterSpec. tag-length iv)))]
      (String. (.doFinal cipher ciphertext) "UTF-8"))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn try-decrypt
  "Attempt to decrypt a value, returning nil on failure.

   Useful for checking if a value is encrypted or if decryption succeeds."
  [encrypted-b64 master-key]
  (try
    (decrypt encrypted-b64 master-key)
    (catch Exception _
      nil)))

(defn encrypted?
  "Check if a string appears to be an encrypted value.

   Encrypted values are Base64-encoded and have a minimum length
   based on salt + iv + tag overhead."
  [^String s]
  (and (string? s)
       ;; Minimum length: salt(16) + iv(12) + tag(16) = 44 bytes = ~60 base64 chars
       (>= (count s) 60)
       ;; Should be valid Base64
       (try
         (.decode (Base64/getDecoder) s)
         true
         (catch Exception _
           false))))

(defn re-encrypt
  "Re-encrypt a value with a new master key.

   Useful for key rotation."
  [encrypted-b64 old-master-key new-master-key]
  (let [plaintext (decrypt encrypted-b64 old-master-key)]
    (encrypt plaintext new-master-key)))

(defn redact
  "Return a redacted version of a value for audit logs.

   Shows first 4 chars and last 4 chars if long enough, otherwise just '[REDACTED]'."
  [^String value]
  (if (and value (> (count value) 12))
    (str (subs value 0 4) "..." (subs value (- (count value) 4)))
    "[REDACTED]"))

(comment
  ;; Usage examples:

  ;; Encrypt a secret
  (def master-key "my-32-char-master-key-for-test!")
  (def encrypted (encrypt "sk-abc123xyz" master-key))

  ;; Decrypt it back
  (decrypt encrypted master-key)

  ;; Re-encrypt with new key
  (def new-key "new-32-char-master-key-rotation!")
  (def re-encrypted (re-encrypt encrypted master-key new-key))
  (decrypt re-encrypted new-key))
