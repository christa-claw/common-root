# Contributing

Thank you for looking under the hood. A few things about how this repository
works will save you a surprise.

## This is a mirror

`common-root` is the public export of a private working repository. Every
commit here is a release ("Release 0.8.3"); day-to-day development, branches,
CI and the deployment pipeline live in the private repository, and each release
is copied here by a script after it is tagged. Nothing is built from this tree.

That means a pull request against this repository is **not merged here**.
Instead:

1. Open the PR as you normally would, against `main`.
2. The maintainer applies the change to the working repository (crediting you
   in the commit message and CHANGELOG).
3. It appears here in the next "Release x.y.z" commit, and the PR is closed
   with a pointer to that release.

Small, focused PRs land fastest. For anything larger than a fix, open an issue
first so the design can be agreed before the work.

## Issues

Issues are the project's backlog and are very welcome — bugs, corpus errors
(a wrong verse, a leaked footnote, a mislabelled edition), translation
corrections, ideas. Use the labels to say which area you mean. Corrections
to a language's texts belong on that language's "Verify … texts" issue.

## Building it yourself

See `README.md`. A clean clone compiles (`mvn -o compile` on both modules is
run against every export before it is published), but the corpus, the argument
ledger and the channel registry are not part of this repository; the
placeholders let the Docker image build and the app start with an empty
corpus, and `docs/` explains how to ingest texts of your own.

## Licence

Contributions are accepted under the repository's licence, GNU AGPL-3.0-or-later
(the edition information pages additionally under CC BY-SA 4.0 — see `NOTICE`).
By opening a PR you agree to license your contribution the same way.
