.PHONY: build
build:
	gradle shadowJar
	mkdir -p bin
	cp build/libs/DefCoreLib*.jar bin/

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
		'server-port=25575' 'level-name=world' 'motd=DefCoreLib docs export' \
		> test-server/server.properties
	cp build/libs/DefCoreLib*.jar test-server/plugins/
	rm -f .temp/display-spec.json
ifeq ($(KEEP_ALIVE),1)
	@echo ">>> test-server keep-alive on localhost:25575 (offline, MC 1.21.11). You spawn at the grid."
	cd test-server && java -Ddefcorelib.export="$(CURDIR)/.temp/display-spec.json" \
		-Ddefcorelib.exportKeep=true -Xmx2G -jar $(PAPER_JAR) --nogui --port 25575
else
	cd test-server && timeout 300 java -Ddefcorelib.export="$(CURDIR)/.temp/display-spec.json" \
		-Xmx2G -jar $(PAPER_JAR) --nogui --port 25575 || true
	@test -s .temp/display-spec.json || { echo "ERROR: headless export did not produce .temp/display-spec.json"; exit 1; }
	cp .temp/display-spec.json docs/data/display-spec.json
	uv run scripts/generate_catalog.py
endif

# Fast iteration: regenerate the catalog from the existing display-spec.json (no server boot).
.PHONY: docs-fast
docs-fast:
	uv run scripts/generate_catalog.py

.PHONY: clean
clean:
	gradle clean
	rm -rf bin/

.PHONY: server-plugin-copy
server-plugin-copy:
	cp bin/DefCoreLib*.jar server/plugins/

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
