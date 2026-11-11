# A LangChain4j ingestion, observed

Three documents go through a LangChain4j ingestion: loaded, split, embedded by a model that
runs in the process, and kept in an in-memory store. complydoc4j records each step.

- `invoice_en.txt`: an IBAN, a sort code and a card number, and a planted instruction.
- `supplier_review.pdf`: a passage hidden five ways (white, under 1pt, off the page,
  invisible, under an image).
- `supplier_contract_pt.pdf`: Portuguese identifiers.

```bash
mvn -q install -DskipTests -f ../../pom.xml   # complydoc4j itself
mvn -q compile exec:java
complydoc ui
```

The run prints what each step did and writes `.complydoc/sample-ingest-<time>.json`.
`complydoc ui` opens it. It shows which identifiers reached the embedding model and the
store, and where in each chunk they are.
