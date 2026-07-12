# `gradle shadowJar` runs the shadowJar task in the root (core) AND every companion module that
# has it (vslab / bbanners / mech / rsd), so one invocation builds all five jars.
.PHONY: build
build:
	gradle shadowJar
	
	mkdir -p bin
	cp build/libs/defCoreLib*.jar bin/
	cp vslab/build/libs/vslab*.jar bin/
	cp bbanners/build/libs/BetterBanners*.jar bin/
	cp mech/build/libs/Mechanism*.jar bin/
	cp rsd/build/libs/RedstoneDisplays*.jar bin/
	cp pipes/build/libs/Pipes*.jar bin/

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
	cp build/libs/defCoreLib*.jar vslab/build/libs/vslab*.jar \
		bbanners/build/libs/BetterBanners*.jar mech/build/libs/Mechanism*.jar \
		rsd/build/libs/RedstoneDisplays*.jar \
		pipes/build/libs/Pipes*.jar test-server/plugins/
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

# Boot the docs test-server in keep-alive mode (grid layout, stays up on
# localhost:25575). Alias for `make docs KEEP_ALIVE=1`.
.PHONY: docs-server
docs-server:
	$(MAKE) docs KEEP_ALIVE=1

# Fast iteration: regenerate the catalog from the existing display-spec.json (no server boot).
.PHONY: docs-fast
docs-fast:
	uv run scripts/generate_catalog.py $(CATALOG_ARGS)

# Copy the deployable demo website (static site) from docs/ into the sibling
# defCoreLib-docs/ deploy directory. "Site only": the HTML pages plus util/, data/,
# assets/, skin-editor/, and readmes/ (the plugin READMEs + their images, which the
# READMEs reference by their deployed remote URL) — everything the browser loads.
# README.md and todo/ are source/planning docs and are NOT copied. The raw
# readmes/assets/mech/old/ screencasts are gitignored and dropped from the deploy.
# Mirror semantics: the managed paths are removed first so files deleted from docs/
# also disappear from the deploy dir; anything else there (e.g. a .git repo) is left untouched.
.PHONY: docs-site
docs-site:
	mkdir -p ../defCoreLib-docs
	rm -rf ../defCoreLib-docs/util ../defCoreLib-docs/data \
		../defCoreLib-docs/assets ../defCoreLib-docs/skin-editor ../defCoreLib-docs/readmes ../defCoreLib-docs/*.html
	cp docs/*.html ../defCoreLib-docs/
	cp -r docs/util docs/data docs/assets docs/skin-editor docs/readmes ../defCoreLib-docs/
	rm -f ../defCoreLib-docs/data/.gitignore
	rm -f ../defCoreLib-docs/readmes/.gitignore

# Strip embedded metadata (tIME timestamps, tEXt/EXIF comments) from the README
# asset PNGs. In place, via ImageMagick; pixel data is untouched. Makes the images
# smaller and their re-exports diff-deterministically. `-strip` on all PNGs under
# docs/readmes/assets/; `find ... +` no-ops cleanly when the dir is empty.
.PHONY: strip-assets
strip-assets:
	find docs/readmes/assets -name '*.png' -exec magick mogrify -strip {} +

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
	cp build/libs/defCoreLib*.jar vslab/build/libs/vslab*.jar \
		bbanners/build/libs/BetterBanners*.jar mech/build/libs/Mechanism*.jar \
		rsd/build/libs/RedstoneDisplays*.jar \
		pipes/build/libs/Pipes*.jar test-server/plugins/
	cd test-server && timeout --foreground -k 30 180 java -Ddefcorelib.showcaseTest=true \
		-Xmx2G -jar $(PAPER_JAR) --nogui --port 25576 < /dev/null

# ---------------------------------------------------------------------------
# Multi-version CI matrix (see .github/workflows/server-test.yml).
# Boots the plugins against a chosen server flavour + Minecraft version. Unlike
# the local `showcase-test` above, this does NOT run gradle: it uses the shaded
# jars already in bin/ (a downloaded build artifact), so 26.x cells can run on
# Java 25 without hitting the Java-21 gradle toolchain.
# ---------------------------------------------------------------------------
SERVER_VARIANT ?= paper
MINECRAFT_VERSION ?= 1.21.11
DOWNLOAD_CACHE := .temp/download-cache
SERVER_JAR := $(DOWNLOAD_CACHE)/$(SERVER_VARIANT)-$(MINECRAFT_VERSION).jar

# Paper: Fill API (v2 was retired). builds/latest returns the download URL directly.
$(DOWNLOAD_CACHE)/paper-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	url=$$(curl -fsSL -H "User-Agent: defCoreLib-ci (github actions)" \
		"https://fill.papermc.io/v3/projects/paper/versions/$*/builds/latest" \
		| jq -r '.downloads."server:default".url'); \
	curl -fsSL -o $@ "$$url"

# Purpur: resolve the latest build for the version, then download it.
$(DOWNLOAD_CACHE)/purpur-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	build=$$(curl -fsSL "https://api.purpurmc.org/v2/purpur/$*" | jq -r '.builds.latest'); \
	curl -fsSL -o $@ "https://api.purpurmc.org/v2/purpur/$*/$$build/download"

.PHONY: test-server-download
test-server-download: $(SERVER_JAR)

# Self-asserting showcase test on the matrixed server (halts 0=pass / 1=fail, no `|| true`).
.PHONY: showcase-test-ci
showcase-test-ci: test-server-download
	mkdir -p test-server/plugins
	rm -f test-server/plugins/*.jar
	cp bin/*.jar test-server/plugins/
	cp $(SERVER_JAR) test-server/server.jar
	[ -f test-server/eula.txt ] || echo 'eula=true' > test-server/eula.txt
	[ -f test-server/server.properties ] || printf '%s\n' \
		'online-mode=false' 'enforce-secure-profile=false' 'gamemode=creative' 'allow-flight=true' \
		'difficulty=peaceful' 'spawn-monsters=false' 'spawn-protection=0' 'level-type=flat' \
		'generate-structures=false' 'server-port=25576' 'level-name=world' \
		'motd=DefCoreLib showcase test' \
		> test-server/server.properties
	cd test-server && timeout --foreground -k 30 180 java -Ddefcorelib.showcaseTest=true \
		-Xmx2G -jar server.jar --nogui --port 25576 < /dev/null

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
	cp build/libs/defCoreLib*.jar vslab/build/libs/vslab*.jar \
		bbanners/build/libs/BetterBanners*.jar mech/build/libs/Mechanism*.jar \
		rsd/build/libs/RedstoneDisplays*.jar \
		pipes/build/libs/Pipes*.jar test-server/plugins/
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
