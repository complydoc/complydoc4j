# Changelog

Newest first. Releases increment the patch number; the minor number changes for a new
way of using complydoc4j, or for a breaking change to its API. The report it writes
follows complydoc's report schema, which is versioned separately, as `run.schema_version`.

## [Unreleased]

The first release: complydoc for Java, writing the same report as complydoc 0.7.11
(schema 27).

### Added

- `Complydoc4j.audit(folder)` reads PDF, Word, Excel, text and Markdown files and checks
  each for identifiers and hidden text: white, tiny, off-page, invisible or covered text in
  a PDF, hidden runs and styles in Word, hidden sheets, rows, columns and cells in Excel,
  and passages written as instructions to a model. It checks the folder's
  `.complydoc-evals.yaml`. HTML, email, PowerPoint and image files are listed as skipped.
- `Complydoc4j.scanText`, `maskText` and `findHidden`, on any string.
- `Observation.start(name)` observes a LangChain4j ingestion. `observation.ingestor()`
  stands in for `EmbeddingStoreIngestor.builder()`, and each loader, transformer,
  splitter, embedding call and store becomes a stage of the trace, followed identifier by
  identifier. Where an embedding call went is read from the model's settings.
- A conformance suite that checks every answer against complydoc's on the same input:
  validators, scans, masking, hidden text, traces, file audits and evals.
