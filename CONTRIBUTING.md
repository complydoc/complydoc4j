# Contributing

## Setup

```bash
make check         # build and every test, as CI runs them
make golden COMPLYDOC=../complydoc   # write the Python package's answers again
make               # list every target
```

Java 17 or later and Maven. Writing the golden data needs a complydoc checkout and `uv`.

## Layout

| Path | Contents |
| --- | --- |
| `complydoc4j-core/…/Complydoc4j.java` | The entry point with no framework: audit, scan, mask, find hidden text |
| `complydoc4j-core/…/sensitive/` | Validators, the pattern detector, masking, fingerprints, the scanner |
| `complydoc4j-core/…/hidden/` | Invisible characters, instruction patterns, PDF and Office visibility |
| `complydoc4j-core/…/read/` | Readers: PDFBox, and Word and Excel from their XML |
| `complydoc4j-core/…/evals/` | The evals file, fact matching, a port of difflib's SequenceMatcher |
| `complydoc4j-core/…/report/` | The report model, the document audit, the JSON writer |
| `complydoc4j-core/…/text/` | Python's string and regex semantics, where the report depends on them |
| `complydoc4j-core/src/main/resources/…/config/` | complydoc's `sensitive.yaml` and `hidden.yaml`, byte for byte |
| `complydoc4j-langchain4j/` | Observing a LangChain4j ingestion |
| `complydoc4j-conformance/` | The suite run against complydoc's answers, and those answers |
| `samples/` | Runnable examples, outside the build |

## Python is the reference

The answers in `complydoc4j-conformance/src/test/resources/golden/` are complydoc's. They are
written by its `src/scripts/java_conformance.py`, and the configuration in core is copied
from them. Where Java and Python disagree, Java is wrong until shown otherwise. If Python is
wrong, as it was in complydoc#62, fix it there first.

A change of behaviour here comes with the same change in complydoc, or follows one. complydoc's
CI runs this suite on every pull request's answers, so the two do not drift apart.

## Branches, pull requests and releases

These are the same as in complydoc.

- Work on a `feature/…` or `fix/…` branch, and open the pull request into `development`.
  An issue it closes is named on the first line: `Closes #12.`
- Pull requests are squash-merged, titled as the change: `Read PowerPoint files (#14)`.
- Each one adds its line to `## [Unreleased]` in `CHANGELOG.md`.
- A release increments the patch number. Its commit on `development`, `Release 0.1.1`, sets
  the version in every `pom.xml` and in `Complydoc4j.VERSION`, and turns `[Unreleased]` into
  `## [0.1.1] — <date>`. `main` then moves to it.
- `make release-check` confirms the version, the changelog and the tree agree, and prints
  the tag to push: `git tag -a v0.1.1 -m 'complydoc4j v0.1.1'`.
- The tag runs `release.yml`. It checks the tag against the version and the changelog,
  runs the suite on Java 17, 21 and 25, builds and attests the jars, and drafts the release.
- Publishing the draft runs `publish.yml`, which signs the jars and uploads them to Maven
  Central. A version there cannot be replaced, so this step is manual.
