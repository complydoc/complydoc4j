.DEFAULT_GOAL := help
MVN ?= mvn --batch-mode
COMPLYDOC ?= ../complydoc

.PHONY: help
help: ## List every target
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

.PHONY: check
check: ## Build and run every test, as CI does
	$(MVN) verify

.PHONY: install
install: ## Install the modules in the local Maven repository, for the samples
	$(MVN) -q install -DskipTests

.PHONY: golden
golden: ## Write complydoc's answers again, from the checkout at COMPLYDOC
	scripts/update-golden.sh $(COMPLYDOC)
	cp complydoc4j-conformance/src/test/resources/golden/config/*.yaml \
		complydoc4j-core/src/main/resources/io/github/complydoc4j/config/

.PHONY: version
version: ## Print the version the build publishes
	@$(MVN) -q help:evaluate -Dexpression=project.version -DforceStdout; echo

.PHONY: release-check
release-check: ## Confirm the version, the changelog and the tree agree before tagging
	@version=$$($(MVN) -q help:evaluate -Dexpression=project.version -DforceStdout); \
	grep -q "VERSION = \"$$version\"" complydoc4j-core/src/main/java/io/github/complydoc4j/Complydoc4j.java \
		|| { echo "Complydoc4j.VERSION is not $$version"; exit 1; }; \
	grep -q "^## \[$$version\]" CHANGELOG.md \
		|| { echo "CHANGELOG.md has no entry for $$version"; exit 1; }; \
	test -z "$$(git status --porcelain)" || { echo "the working tree is dirty"; exit 1; }; \
	echo "ready to tag: git tag -a v$$version -m 'complydoc4j v$$version' && git push origin v$$version"

.PHONY: clean
clean: ## Remove build output and the reports tests write
	$(MVN) -q clean
	rm -rf .complydoc samples/*/.complydoc samples/*/target
