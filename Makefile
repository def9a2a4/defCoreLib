# `gradle shadowJar` runs the shadowJar task in the root (core) AND every companion module that
# has it (vslab / bbanners / mech), so one invocation builds all four jars.
.PHONY: build
build:
	gradle shadowJar
	mkdir -p bin
	cp build/libs/DefCoreLib*.jar bin/
	cp vslab/build/libs/vslab*.jar bin/
	cp bbanners/build/libs/bbanners*.jar bin/
	cp mech/build/libs/mech*.jar bin/

# Docs build: compile the plugin, boot a SEPARATE server (test-server/, not the playtest server/)
# that exports the ground-truth placed-display data (-Ddefcorelib.export), then generate the catalog
# + vendor assets. Single command. test-server/ is created (gitignored) with a joinable, peaceful,
# creative baseline on first run.
#   make docs               headless export on port 25575, shuts down, then generates the catalog.
#   make docs KEEP_ALIVE=1  lays every block+variant out in a grid and STAYS running so you can join
#                           localhost:25575 (offline, MC 1.21.11); you spawn at the grid. Ctrl-C,
#                           then `make docs-fast` to regenerate the catalog.
PAPER_JAR := $(notdir $(firstword $(wildcard server/paper-*.jar)))
KEEP_ALIVE ?= 0
# Extra args passed to the catalog generator. CI sets `--no-assets` to skip
# vendoring images from third-party CDNs (only the headless boot is under test).
CATALOG_ARGS ?=

.PHONY: docs
docs:
	@[ -n "$(PAPER_JAR)" ] || { echo "ERROR: no server/paper-*.jar found to copy into test-server/"; exit 1; }
	gradle shadowJar
	mkdir -p test-server/plugins .temp
	[ -f test-server/$(PAPER_JAR) ] || cp server/$(PAPER_JAR) test-server/
	[ -f test-server/eula.txt ] || echo 'eula=true' > test-server/eula.txt
	[ -f test-server/server.properties ] || printf '%s\n' \
		'online-mode=false' 'enforce-secure-profile=false' 'gamemode=creative' 'allow-flight=true' \
		'difficulty=peaceful' 'spawn-monsters=false' 'spawn-protection=0' 'level-type=flat' \
		'generate-structures=false' 'server-port=25575' 'level-name=world' \
		'motd=DefCoreLib docs export' \
		> test-server/server.properties
	cp build/libs/DefCoreLib*.jar test-server/plugins/
	rm -f .temp/display-spec.json
ifeq ($(KEEP_ALIVE),1)
	@echo ">>> test-server keep-alive on localhost:25575 (offline, MC 1.21.11). You spawn at the grid."
	cd test-server && java -Ddefcorelib.export="$(CURDIR)/.temp/display-spec.json" \
		-Ddefcorelib.exportKeep=true -Xmx2G -jar $(PAPER_JAR) --nogui --port 25575
else
	# --foreground: keep java in the foreground process group so Paper's console reader can access the
	# TTY. Without it, `timeout` (run from a Makefile, i.e. not an interactive shell) backgrounds java;
	# Paper's stdin read then triggers SIGTTIN and the whole JVM is STOPPED mid-boot (looks like a hang
	# that Ctrl+C can't kill). timeout still enforces the 300s cap on a genuinely stuck boot.
	# -k 30: if SIGTERM at 300s doesn't kill it, escalate to SIGKILL 30s later (insurance against a
	# hang before the export's own halt(0) runs — e.g. during world gen). A normal run halts in ~12s.
	cd test-server && timeout --foreground -k 30 300 java -Ddefcorelib.export="$(CURDIR)/.temp/display-spec.json" \
		-Xmx2G -jar $(PAPER_JAR) --nogui --port 25575 < /dev/null || true
	@test -s .temp/display-spec.json || { echo "ERROR: headless export did not produce .temp/display-spec.json"; exit 1; }
	cp .temp/display-spec.json docs/data/display-spec.json
	uv run scripts/generate_catalog.py $(CATALOG_ARGS)
endif

# Fast iteration: regenerate the catalog from the existing display-spec.json (no server boot).
.PHONY: docs-fast
docs-fast:
	uv run scripts/generate_catalog.py $(CATALOG_ARGS)

# Copy the deployable demo website (static site) from docs/ into the sibling
# defCoreLib-docs/ deploy directory. "Site only": the HTML pages plus util/, data/,
# assets/, and skin-editor/ — everything the browser loads. README.md, readmes/, and
# todo/ are source/planning docs and are NOT copied. Mirror semantics: the managed
# paths are removed first so files deleted from docs/ also disappear from the deploy
# dir; anything else there (e.g. a .git repo) is left untouched.
.PHONY: docs-site
docs-site:
	mkdir -p ../defCoreLib-docs
	rm -rf ../defCoreLib-docs/util ../defCoreLib-docs/data \
		../defCoreLib-docs/assets ../defCoreLib-docs/skin-editor ../defCoreLib-docs/*.html
	cp docs/*.html ../defCoreLib-docs/
	cp -r docs/util docs/data docs/assets docs/skin-editor ../defCoreLib-docs/
	rm -f ../defCoreLib-docs/data/.gitignore

# Showcase integration tests: build the demo machines, run them, assert the YAML `expect` conditions.
# The plugin halt()s with exit 0 (all pass) or 1 (any fail); NO `|| true`, so a failure fails `make`.
# Runs on a separate port (25576) so it won't clash with a keep-alive docs server on 25575.
.PHONY: showcase-test
showcase-test:
	@[ -n "$(PAPER_JAR)" ] || { echo "ERROR: no server/paper-*.jar found to copy into test-server/"; exit 1; }
	gradle shadowJar
	mkdir -p test-server/plugins
	[ -f test-server/$(PAPER_JAR) ] || cp server/$(PAPER_JAR) test-server/
	[ -f test-server/eula.txt ] || echo 'eula=true' > test-server/eula.txt
	[ -f test-server/server.properties ] || printf '%s\n' \
		'online-mode=false' 'enforce-secure-profile=false' 'gamemode=creative' 'allow-flight=true' \
		'difficulty=peaceful' 'spawn-monsters=false' 'spawn-protection=0' 'level-type=flat' \
		'generate-structures=false' 'server-port=25575' 'level-name=world' \
		'motd=DefCoreLib docs export' \
		> test-server/server.properties
	cp build/libs/DefCoreLib*.jar test-server/plugins/
	cd test-server && timeout --foreground -k 30 180 java -Ddefcorelib.showcaseTest=true \
		-Xmx2G -jar $(PAPER_JAR) --nogui --port 25576 < /dev/null

# Showcase capture: build the demo machines, run them, read back their live display + animation data
# into showcase-spec.json (then generate_catalog folds it into showcases.json for the docs page).
.PHONY: showcase-capture
showcase-capture:
	@[ -n "$(PAPER_JAR)" ] || { echo "ERROR: no server/paper-*.jar found to copy into test-server/"; exit 1; }
	gradle shadowJar
	mkdir -p test-server/plugins .temp
	[ -f test-server/$(PAPER_JAR) ] || cp server/$(PAPER_JAR) test-server/
	[ -f test-server/eula.txt ] || echo 'eula=true' > test-server/eula.txt
	[ -f test-server/server.properties ] || printf '%s\n' \
		'online-mode=false' 'enforce-secure-profile=false' 'gamemode=creative' 'allow-flight=true' \
		'difficulty=peaceful' 'spawn-monsters=false' 'spawn-protection=0' 'level-type=flat' \
		'generate-structures=false' 'server-port=25575' 'level-name=world' \
		'motd=DefCoreLib docs export' \
		> test-server/server.properties
	cp build/libs/DefCoreLib*.jar test-server/plugins/
	rm -f .temp/showcase-spec.json
	cd test-server && timeout --foreground -k 30 180 java -Ddefcorelib.showcaseCapture="$(CURDIR)/.temp/showcase-spec.json" \
		-Xmx2G -jar $(PAPER_JAR) --nogui --port 25576 < /dev/null || true
	@test -s .temp/showcase-spec.json || { echo "ERROR: capture did not produce .temp/showcase-spec.json"; exit 1; }
	cp .temp/showcase-spec.json docs/data/showcase-spec.json
	uv run scripts/generate_catalog.py $(CATALOG_ARGS)

.PHONY: clean
clean:
	gradle clean
	rm -rf bin/

.PHONY: server-plugin-copy
server-plugin-copy:
	cp bin/*.jar server/plugins/

.PHONY: server-clear-plugin-data
server-clear-plugin-data:
	rm -rf server/plugins/DefCoreLib/

.PHONY: server-start
server-start:
	cd server && java -Xmx2G -jar paper-1.21.11-55.jar --nogui

.PHONY: server
server: build server-plugin-copy server-start

.PHONY: all
all: clean build server
