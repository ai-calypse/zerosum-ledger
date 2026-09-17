// decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
// The M13 (c) provenance block and the .env reader, shared by tools/simulator and tools/verifier.
//
// Extracted for the same reason CR-S05-01 extracted libs/testsupport: two copies of a provenance block are one
// change away from drifting, and M13 (c) is precisely the thing that must not drift between the two tools that
// write evidence. JDK-only, like libs/money: nothing here needs a framework, and a tool that records what ran
// should not itself pull in a dependency tree that changes what ran.
plugins {
    id("zs.library-conventions")
}
