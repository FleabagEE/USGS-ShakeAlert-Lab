## Lesson 3: credentials.py — the secret boundary

  The core module is src/shakealert_lab/credentials.py, supported by src/shakealert_lab/security/redaction.py.

  The architectural goal is not merely to keep passwords out of Git. It is to control the complete lifecycle of sensitive information:

  provision
     ↓
  store
     ↓
  reference
     ↓
  validate
     ↓
  read briefly
     ↓
  use in one authorized operation
     ↓
  discard

  Every additional copy increases the attack surface.

  ### Why this module exists

  Configuration must describe where credentials are located, but most of the application has no legitimate reason to know their values.

  This module separates two questions:

  1. Is a credential artifact safe and available?
  2. What is the credential value?

  inspect_credentials() answers only the first question. read_secret() is reserved for the narrow adapter boundary that genuinely needs the second.

  This is an information-flow decision: validation code receives less authority than connection code.

  ### What problem it solves

  Without a dedicated credential boundary, secrets tend to spread through ordinary application structures:

  - Configuration dictionaries
  - Command-line arguments
  - Environment variables
  - Exception messages
  - Debug representations
  - Test fixtures
  - Metrics labels
  - Logs
  - Process listings
  - Crash dumps

  Once a secret becomes an ordinary string passed through several layers, it becomes very difficult to prove where it can appear.

  The repository instead keeps paths in the configuration model and delays reading until an approved adapter needs the value.

  ### What would happen if it were removed

  Every protocol adapter would likely implement credential handling differently.

  One might follow symlinks. Another might accept world-readable files. Another might log its configuration object. Another might put credentials on the command line.

  You would lose a single security contract and replace it with scattered conventions.

  In security architecture, duplicated secret handling is particularly dangerous because the weakest implementation becomes the effective system policy.

  ## The two-stage design

  ### Stage 1: metadata inspection

  inspect_credentials() checks properties such as:

  - The path is configured.
  - The artifact exists.
  - It is a regular file.
  - It is not a symlink.
  - Group and other users have no access.
  - The service can read it.

  It returns only Boolean presence information.

  The status output deliberately says:

  password: present

  It does not report:

  - Length
  - Hash
  - Prefix
  - Suffix
  - Encoding
  - Modification content
  - Whether two credentials match

  Even seemingly harmless metadata can become useful to an attacker or accidentally disclose identity.

  ### Stage 2: bounded secret read

  read_secret() performs the privileged action:

  - Refuses symbolic-link traversal
  - Opens the artifact directly
  - Rechecks permissions through the opened file descriptor
  - Reads only a bounded amount
  - Closes the descriptor reliably
  - Returns bytes rather than converting automatically to text

  The important idea is that metadata inspection is not authorization to read.

  ## Embedded principle: least authority

  Each part of the program should receive only the authority it needs.

  Status command
      └── may inspect metadata

  Configuration loader
      └── may store paths

  Transport adapter
      └── may read required credential briefly

  Logger
      └── must never receive credential values

  This is stronger than relying on programmer discipline.

  A world-class architecture tries to make invalid information flow structurally difficult.

  ## Design pattern: secure facade

  credentials.py acts as a secure facade around operating-system file operations.

  Callers do not decide independently:

  - Whether symlinks are acceptable
  - Which permissions are safe
  - How much data can be read
  - Whether secrets should be strings
  - What errors may be reported

  The facade centralizes that policy.

  This is similar to a hardware abstraction layer, except the protected resource is authority rather than a peripheral.

  ## Design pattern: handle by reference

  CredentialPaths represents secrets using references.

  This resembles resource handles:

  credential path → protected resource
  file descriptor → opened instance
  secret bytes    → short-lived sensitive material

  The path is not the secret, just as a file descriptor is not the file contents and a peripheral handle is not the peripheral state.

  This pattern permits most layers to route credential identity without possessing credential data.

  ## Symlink defense and TOCTOU

  A common attack is to validate one file and then read another:

  check path
     ↓
  attacker replaces path with symlink
     ↓
  open different target

  This is a time-of-check/time-of-use race.

  read_secret() improves the design by opening with symbolic-link traversal disabled and then inspecting the opened descriptor. Descriptor-based validation matters because it
  examines the object actually being used—not merely what the pathname referred to earlier.

  This principle appears throughout low-level systems:

  > Validate the acquired resource, not just the name used to acquire it.

  ## Bounded reads

  The maximum credential size prevents a configured “credential” from consuming unbounded memory.

  This defends against:

  - Accidentally selecting a large file
  - Malicious replacement
  - Device-like or unexpected inputs
  - Corrupted provisioning
  - Resource-exhaustion attacks

  A firmware architect treats all external sizes as hostile until bounded.

  ## Structured redaction

  redact_mapping() protects the logging boundary.

  It uses field names to identify sensitive values:

  - Password
  - Secret
  - Token
  - Private key
  - Credential

  Nested mappings are traversed, and byte arrays are represented only by their length.

  This follows an important observability principle:

  > Logs should describe system state without recreating protected system state.

  Redaction belongs close to the output sink because every upstream caller can make mistakes.

  But redaction is a last line of defense, not authorization to pass secrets into the logger.

  ## Where these patterns appear

  Automotive:

  - Immobilizer keys, diagnostic-security seeds, certificates, and provisioning material are isolated from ordinary application software.
  - Hardware security modules expose operations such as “sign” or “verify” without releasing private keys.

  Aerospace:

  - Mission keys and authenticated command material are compartmentalized.
  - Components receive narrowly scoped cryptographic services rather than broad access to key storage.

  Industrial PLCs:

  - Safety-program authorization, protected recipes, remote-access credentials, and signing keys are separated from ordinary control logic.

  Robotics:

  - Fleet certificates, cloud tokens, actuator authorization, and update-signing keys are mounted into specific processes rather than embedded in robot configuration files.

  Linux kernel drivers:

  - File descriptors and kernel objects are validated after acquisition.
  - Credentials and keyrings are represented through handles and access-controlled kernel objects.

  RTOS firmware:

  - Secrets may reside in secure flash, OTP memory, a secure element, TrustZone, or a dedicated security task.
  - Ordinary tasks request an operation through IPC instead of reading the key.

  ## Architectural weaknesses in the current implementation

  ### Ownership is not explicitly verified

  The documentation says credential files must be owned by the service account, but inspect_credentials() does not compare the file UID against an expected service UID.

  A root process could read a mode-0600 file owned by someone else and incorrectly consider it acceptable.

  Permissions and ownership answer different questions:

  - Mode: who may access the file?
  - Ownership: whose security domain controls it?

  Both should be validated.

  ### read_secret() does not explicitly require a regular file

  It prevents symlinks and checks permissions, but it does not use descriptor metadata to prove the opened object is a regular file.

  A FIFO or device node could behave very differently:

  - Block indefinitely
  - Produce changing data
  - Trigger device behavior
  - Violate the bounded-file model

  The object actually opened should be verified as a regular file before reading.

  ### Inspection and reading use different strength

  inspect_credentials() validates a pathname and then uses separate path operations. The object could theoretically change between those operations.

  read_secret() is stronger because it operates through one opened descriptor.

  The strongest architecture would use the same descriptor-based verification logic for both inspection and reading.

  ### Newline removal changes the secret

  read_secret() removes trailing CR and LF bytes.

  That is convenient for manually created files, but it means the adapter does not receive the exact stored bytes.

  A stronger contract must choose one policy explicitly:

  - Credential files contain exact raw bytes and must not include terminators.
  - Credential files contain one textual line and exactly one line ending may be removed.
  - Credential type determines normalization.

  Silent normalization is dangerous because the filesystem value and transmitted value are no longer identical.

  ### Empty secrets can emerge after normalization

  A file containing only line endings is initially nonempty but becomes empty after trimming. The function does not reject the resulting empty value.

  Validation should apply to the final representation actually sent to the broker.

  ### Returned secrets are difficult to erase

  Python bytes objects are immutable. Once returned, they cannot reliably be overwritten.

  Copies may exist in:

  - Temporary objects
  - Protocol libraries
  - TLS libraries
  - Interpreter-managed memory

  This does not make Python inherently unusable, but it limits claims about secret erasure. A rigorous design should say “minimize lifetime and copies,” not “guarantee erasure.”

  ### Redaction depends on field names

  This is safe:

  password → redacted

  This may leak:

  message → "connection failed using secret-value"

  The logger cannot know that an arbitrary string contains a credential.

  That is why the primary rule remains: do not send raw exception messages or secrets to logging at all.

  ### Separate implementations can drift

  The Java OpenWire receiver has its own credential checks and redaction logic. The Python boundary has another implementation.

  That creates policy duplication. A mature design needs a shared specification and tests proving equivalent behavior across languages.

  ## Stronger architecture: use without disclosure

  The best secret API often does not return a secret at all.

  Conceptually:

  CredentialBroker.authenticate(connection_spec)

  or:

  with_credential(identity, authorized_operation)

  The credential service:

  1. Validates authority.
  2. Opens the protected artifact.
  3. Supplies it only to the approved operation.
  4. Prevents unrelated callers from retaining it.
  5. Records sanitized success or failure.
  6. Closes and clears what it can.

  Hardware security modules go further: the secret never leaves protected hardware.

  ## Your design questions

  1. Should credential files contain exact raw bytes or one normalized text line? Define the contract precisely, including empty values and trailing CR/LF.
  2. Redesign read_secret() so callers cannot casually retain or log the returned value. What API would you expose?
  3. What exact checks should be performed on the opened file descriptor before reading: type, owner, mode, size, link count, filesystem, or something else?
  4. If redaction encounters an object or field it cannot classify safely, should it preserve the value, replace it, or reject the entire log event? Explain your failure policy.

  Answer those, and Lesson 4 will examine immutable message envelopes and why transport callbacks should not pass arbitrary native objects into the rest of the system.
