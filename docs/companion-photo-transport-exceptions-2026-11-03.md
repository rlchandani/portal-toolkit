# Photo upload transport outcomes

An unexpected exception after sending a photo previously escaped the upload
client and could mark a group item Failed while its detail said completion was
unconfirmed. Failure-only retry could then select that item again.

The client now classifies unexpected transport exceptions as Unconfirmed.
Cancellation and explicit server errors retain their original handling.
Request construction and local input validation remain outside the transport
catch, so invalid input is rejected before any send.

Five added JVM tests cover after-body exceptions, cancellation/server errors,
local rejection, single-room outcomes and mixed-room failure-only retry.
The focused run passed 79 tests; the final full run passed **656 tests in 81
suites**, with zero failures, errors or skips. Debug assembly passed in the
same terminal-successful 12-second Gradle run.

The local candidate is `com.portalinstaller`, **1.7.9-music-preview/code21**.
Its APK SHA-256 is
`dcee8a0879a3367462ac1e52482085133927fa2a6461ebe922165cc72d0adbb5`
(14,738,973 bytes). Its verified certificate SHA-256 matches the preceding
1.7.8 candidate:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

These are synthetic JVM transport fixtures, not evidence of a real-device
incident or native acceptance. No phone commands or installation were
performed. The Google phone remains outside the authorized test scope.
