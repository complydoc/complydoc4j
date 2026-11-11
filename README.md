# complydoc4j

[complydoc](https://github.com/complydoc/complydoc) for Java. It finds the personal and
financial identifiers in your documents, the text a reader doesn't see but a model does,
and the passages written as instructions to a model. It also shows what each step of an
ingestion pipeline did to all of them.

It writes the same report as complydoc, so `complydoc ui` opens it and `complydoc check`
runs your policies on it. The rules come from the same files (`sensitive.yaml`,
`hidden.yaml`), and CI checks the answers against the Python package's own.

## Modules

| Module | What it is |
| --- | --- |
| `complydoc4j-core` | Identifier scanning with checksum validators, masking, fingerprints, hidden-text checks, chunk inspection and the report writer. No framework attached. |
| `complydoc4j-langchain4j` | Observes a LangChain4j ingestion. Each splitter, transformer, embedding call and embedding store becomes a stage of the trace. |
| `complydoc4j-conformance` | The suite that checks the Java answers against the Python package's, on the same input. |

Java 17 or later.

complydoc4j isn't on Maven Central yet. Until it is, install it from a clone with
`mvn install`, then depend on `io.github.complydoc:complydoc4j-langchain4j` (or
`complydoc4j-core`) at `0.1.0`.

## With no framework

```java
Complydoc4j.scanText("IBAN: GB29 NWBK 6016 1331 9268 19");  // the identifiers, masked
Complydoc4j.maskText(prompt);                               // the text, identifiers masked
Complydoc4j.findHidden(retrievedChunk);                     // invisible text, planted instructions

Report report = Complydoc4j.audit(Path.of("contracts"));  // a folder of PDF, Word, Excel, text files,
                                                          // checked against its .complydoc-evals.yaml
Report read = Complydoc4j.audit(items, "my-reader");      // documents you have already read
Complydoc4j.write(report, Path.of(".complydoc/contracts.json"));
```

## LangChain4j

Change `EmbeddingStoreIngestor.builder()` to `observation.ingestor()`:

```java
try (Observation observation = Observation.start("contracts-ingest")) {
    List<Document> documents = observation.load("FileSystemDocumentLoader",
            () -> FileSystemDocumentLoader.loadDocuments(folder, parser));
    observation.ingestor()
            .documentSplitter(DocumentSplitters.recursive(800, 80))
            .embeddingModel(model)
            .embeddingStore(store)
            .build()
            .ingest(documents);
}
```

When the block closes, the report is written to `.complydoc/contracts-ingest-<time>.json`.
Then run `complydoc ui` to open it. Components used outside an ingestor can be wrapped one
at a time: `observation.splitter(...)`, `observation.embeddingModel(...)`,
`observation.embeddingStore(...)`.

Nothing is scanned while the pipeline runs, so the stage timings are the pipeline's own.
Observing never breaks the pipeline. If complydoc4j itself fails, the failure is reported
as a warning. If the pipeline throws, the exception is recorded and thrown on.

`samples/langchain4j-ingest` is a runnable ingestion of three documents, with an in-process
embedding model so nothing leaves the machine.

## What the Java version leaves out

The report has the same shape, and these sections are null or empty:

- **Names and organisations.** Python reads these with a statistical model, and there is
  none here. The report lists them as not looked for, as Python does when its name model
  isn't installed.
- **Cost, readiness and routing.**
- **HTML, email, PowerPoint and images.** A folder audit lists these files as skipped. It
  reads PDF, Word, Excel, text and Markdown files, and checks how each PDF, Word and Excel
  file hides text: white, tiny, off-page, invisible or covered text, hidden runs, hidden
  sheets, rows and columns. It doesn't run OCR.
- **Where an embedding call went.** Python sees this from inside its network guard. Here it
  is read from the endpoint in the model's settings, and the report says so.

`run.tool_version` starts with `complydoc4j`, so a reader can tell which version wrote a
report.

## Conformance

`complydoc4j-conformance` holds what the Python package answered on the shared fixtures:

- every validator's verdict on about 7,800 values
- a folder of PDF, Word and Excel files, audited by each side's own readers: the same
  identifiers, the same hidden passages with the same reasons, and the same evals, near
  misses scored to the same four decimals
- the scans, masking and hidden-text findings on each fixture and each of its paragraphs
- the full report `cd.observe` wrote for the same pipeline

The Java answers have to be the same, field by field. The only exceptions are fields that
differ on purpose, such as timings, IDs and versions.

To refresh the golden data from a complydoc checkout:

```bash
scripts/update-golden.sh ../complydoc
```

## Contributing

Branches, pull requests and releases work as in complydoc. See [CONTRIBUTING.md](CONTRIBUTING.md),
and [CHANGELOG.md](CHANGELOG.md) for what each release changed.

## Licence

MIT
