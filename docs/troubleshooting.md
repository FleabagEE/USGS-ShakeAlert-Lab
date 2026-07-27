# Troubleshooting

Configuration errors mean required verified facts are absent or placeholders remain. Credential errors report only the affected credential type; inspect mode/owner without printing content. A missing adapter means protocol/version has not been implemented and must not be guessed. Capture errors require disk, ownership, capacity, and audit review; never overwrite a prior record. Dashboard bind errors indicate a non-loopback address. Connection/TLS/authentication troubleshooting must follow the failure classification in `tls-validation.md` after authorization.
