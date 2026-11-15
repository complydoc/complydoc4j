# Changelog

Newest first. Releases increment the patch number; the minor number changes for a new
way of using complydoc4j, or for a breaking change to its API. The report it writes
follows complydoc's report schema, which is versioned separately, as `run.schema_version`.

## [0.1.1] — 2026-11-15

PowerPoint, HTML and email files are read, and the text they and Markdown hide is checked,
as complydoc 0.7.12 checks it. Only images are still skipped. The report stays at schema 27.

### Added

- PowerPoint, HTML and email files are read, as complydoc reads them, and no longer listed
  as skipped. PowerPoint gives a page per slide, with speaker notes after the slide's text.
  An HTML page is one page, its table cells separated by tabs. An email gives its headers,
  then the plain body, or the HTML one as text.
- Their hidden text is checked:
  - PowerPoint: hidden slides, shapes placed outside the slide, and text under 1pt.
  - HTML, and the HTML body of an email: the hidden attribute, `display: none`,
    `visibility: hidden`, no opacity, transparent or white text, text under 1pt, zero
    height, comments, and text placed off screen.
  - Markdown: HTML comments, link references used as comments, and hidden inline HTML.
    Code blocks are left alone.

  As in complydoc, a passage hidden in HTML or Markdown is reported only when it reads as an
  instruction, since pages routinely hide menus and comments.

## [0.1.0] — 2026-11-11

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
