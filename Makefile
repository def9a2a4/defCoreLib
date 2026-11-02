# `gradle shadowJar` runs the shadowJar task in the root (core) AND every companion module that
# has it (vslab / bbanners / mech / rsd), so one invocation builds all five jars.

# Only the classifier-less shadow jar — never the `-plain` thin jar (which exists solely so the
# subprojects can compile against corelib via `compileOnly(project(":"))`). Shipping both into a
# plugins folder makes Paper report "Ambiguous plugin name 'DefCoreLib'", so deploys use this.
#
# Pinned to the current version: build/libs keeps every past version's jar, and copying a glob put
# 0.4.0 AND 0.5.0 of each plugin side by side (Paper then loads whichever it likes). Deploys also
# delete our older jars from the target folder first; other plugins there (BlockShips, …) are kept.
VERSION := $(shell sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
PLUGIN_JARS = build/libs/defCoreLib-$(VERSION).jar vslab/build/libs/vslab-$(VERSION).jar \
	bbanners/build/libs/BetterBanners-$(VERSION).jar mech/build/libs/Mechanism-$(VERSION).jar \
	rsd/build/libs/RedstoneDisplays-$(VERSION).jar pipes/build/libs/Pipes-$(VERSION).jar \
	railbound/build/libs/Railbound-$(VERSION).jar headsmith/build/libs/HeadSmith-$(VERSION).jar
STALE_JARS = defCoreLib-*.jar vslab-*.jar BetterBanners-*.jar Mechanism-*.jar RedstoneDisplays-*.jar \
	Pipes-*.jar Railbound-*.jar HeadSmith-*.jar

# $(call deploy_jars,DIR): replace our plugin jars in DIR with this version's.
define deploy_jars
	mkdir -p $(1)
	cd $(1) && rm -f $(STALE_JARS)
	cp $(PLUGIN_JARS) $(1)/
endef

.PHONY: build
build:
	gradle shadowJar
	
	$(call deploy_jars,bin)

# Upload this version's jars + docs/changelogs/v$(VERSION)/ to Modrinth (scripts/publish_modrinth.py).
# publish needs MODRINTH_TOKEN and a clean tree; re-running skips plugins already published.
.PHONY: publish-dry
publish-dry: build
	uv run scripts/publish_modrinth.py --dry-run

.PHONY: publish
publish: build
	uv run scripts/publish_modrinth.py

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
	$(call deploy_jars,test-server/plugins)
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
	$(call deploy_jars,test-server/plugins)
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

# Restart tests (`restart: crash|clean` scenarios in showcases.yml): two boots of one throwaway world, once
# per mode. Boot 1 (showcasePhase=1) builds that mode's scenarios, runs them, then stops cleanly or "crashes"
# (halt with exit 3 — counted as OK, like 0); boot 2 reloads the same world and checks `after_restart`
# (exit 0/1). Each mode starts from a fresh `restart_world` (plus its mechanism state files) in test-server/;
# the regular `world` there is never touched. Port 25577, clear of the other targets.
RESTART_MODES := crash clean
# $(call restart_boots,SERVER_JAR): both boots of every mode, run inside test-server/.
# The server writes --level-name back into server.properties, so it's restored on exit (pass or fail) — else
# the docs server would come up on restart_world.
define restart_boots
	mkdir -p .temp && cd test-server && cp server.properties ../.temp/restart-server.properties.bak && \
	trap 'cp ../.temp/restart-server.properties.bak server.properties' EXIT && \
	for mode in $(RESTART_MODES); do \
		rm -rf restart_world restart_world_nether restart_world_the_end plugins/DefCoreLib/mechanisms/restart_world; \
		for phase in 1 2; do \
			rc=0; timeout --foreground -k 30 180 java -Ddefcorelib.showcaseTest=true \
				-Ddefcorelib.showcaseRestart=$$mode -Ddefcorelib.showcasePhase=$$phase \
				-Xmx2G -jar $(1) --nogui --port 25577 --level-name restart_world < /dev/null || rc=$$?; \
			if [ $$rc -ne 0 ] && ! [ $$phase = 1 -a $$rc -eq 3 ]; then \
				echo "ERROR: restart test ($$mode, boot $$phase) exited $$rc"; exit 1; \
			fi; \
		done; \
	done
endef

.PHONY: showcase-restart-test
showcase-restart-test:
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
	$(call deploy_jars,test-server/plugins)
	$(call restart_boots,$(PAPER_JAR))

# CI flavour: bin/ jars + the downloaded server jar, exactly as showcase-test-ci (run that first, or this).
.PHONY: showcase-restart-test-ci
showcase-restart-test-ci: test-server-download
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
	$(call restart_boots,server.jar)

# In-game bot tests (test-bot/, mineflayer): a real client right-clicks, places, breaks and glues
# DefCoreLib blocks. The server boots in test-server/ on its own throwaway world (bot_world, deleted
# first; `world` is never touched) and port 25578, with -Ddefcorelib.botTest=true — which is what
# enables the test-only `/defcorelib bottest` fixtures. Mineflayer speaks 1.21.11 and 26.1.x only.
#   make bot-install && make bot-test       BOT_ARGS=--only=glue runs a subset.
BOT_PORT := 25578
BOT_NAME := TestBot
BOT_ARGS ?=
BOT_FIFO := .temp/bot-server-input
BOT_LOG := .temp/bot-server.log

.PHONY: bot-install
bot-install:
	cd test-bot && npm ci

# $(call bot_run,SERVER_JAR,MC_VERSION): run from the repo root with test-server/ prepared. Boots the
# server in the background with stdin = a FIFO held open on fd 3 (so `stop` can be sent and the console
# never sees EOF early; opened read-write so it can't block if java never starts), waits for "Done", runs
# the bot, stops the server, then fails if the bot failed or the log has an error (CI's filter, incl. its
# 'No key layers in MapLike' exemption). The bot is op'd by merging its offline UUID into ops.json.
define bot_run
	mkdir -p .temp
	cd test-server && rm -rf bot_world bot_world_nether bot_world_the_end plugins/DefCoreLib/mechanisms/bot_world
	node test-bot/add-op.js test-server/ops.json $(BOT_NAME)
	rm -f $(BOT_FIFO) $(BOT_LOG) test-bot/test-results.txt && mkfifo $(BOT_FIFO)
	# The server saves --port/--level-name into server.properties; the EXIT trap puts the original back (pass,
	# fail or Ctrl-C) so the docs server doesn't come up on bot_world.
	cp test-server/server.properties .temp/bot-server.properties.bak
	(cd test-server && exec java -Ddefcorelib.botTest=true -Xmx2G -jar $(1) --nogui --port $(BOT_PORT) \
		--level-name bot_world --online-mode false < ../$(BOT_FIFO) > ../$(BOT_LOG) 2>&1) & \
	SERVER_PID=$$!; \
	trap 'kill $$SERVER_PID 2>/dev/null; cp .temp/bot-server.properties.bak test-server/server.properties' EXIT; \
	trap 'exit 130' INT TERM; \
	exec 3<> $(BOT_FIFO); \
	up=0; for i in $$(seq 1 300); do \
		if grep -q 'Done (' $(BOT_LOG); then up=1; break; fi; \
		kill -0 $$SERVER_PID 2>/dev/null || break; \
		sleep 1; \
	done; \
	BOT_EXIT=1; \
	if [ $$up = 1 ]; then \
		(cd test-bot && MC_PORT=$(BOT_PORT) MC_USERNAME=$(BOT_NAME) MC_VERSION=$(2) node defcorelib-bot.js $(BOT_ARGS)); \
		BOT_EXIT=$$?; \
	else \
		echo "ERROR: bot-test server did not start"; tail -n 50 $(BOT_LOG); \
	fi; \
	echo stop >&3; exec 3>&-; \
	for i in $$(seq 1 60); do kill -0 $$SERVER_PID 2>/dev/null || break; sleep 1; done; \
	kill $$SERVER_PID 2>/dev/null; sleep 1; \
	ERRS=$$(grep -nE ' (ERROR|FATAL)\]|SEVERE\]|^[[:space:]]+at [A-Za-z]|Caused by:' $(BOT_LOG) \
		| grep -v 'No key layers in MapLike'); \
	[ -z "$$ERRS" ] || { echo "ERROR: the server logged errors during the bot run ($(BOT_LOG)):"; echo "$$ERRS"; }; \
	[ $$BOT_EXIT -eq 0 ] && [ -z "$$ERRS" ]
endef

.PHONY: bot-test
bot-test:
	@[ -n "$(PAPER_JAR)" ] || { echo "ERROR: no server/paper-*.jar found to copy into test-server/"; exit 1; }
	@[ -d test-bot/node_modules ] || { echo "ERROR: run 'make bot-install' first"; exit 1; }
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
	$(call deploy_jars,test-server/plugins)
	$(call bot_run,$(PAPER_JAR),$(word 2,$(subst -, ,$(PAPER_JAR))))

# CI flavour: bin/ jars + the downloaded server jar, exactly as showcase-test-ci.
.PHONY: bot-test-ci
bot-test-ci: test-server-download
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
	$(call bot_run,server.jar,$(MINECRAFT_VERSION))

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
	$(call deploy_jars,test-server/plugins)
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
	cd server/plugins && rm -f $(STALE_JARS)
	cp bin/*.jar server/plugins/

# Copy the sibling BlockShips plugin jar into test-server/plugins/ so the test-server runs BlockShips
# against THIS defCoreLib build (BlockShips depend:s on DefCoreLib). Relative sibling path for now
# (JitPack pin later). Build BlockShips first: `cd ../BlockShips && make build` populates ../BlockShips/bin/.
BLOCKSHIPS_JAR := $(wildcard ../BlockShips/bin/BlockShips-*.jar)
# BlockShips' ship-smoothness position-sync is a per-tick ENTITY_TELEPORT packet sent via ProtocolLib
# (softdepend). Without ProtocolLib the packet silently no-ops and moving ships rubber-band, so stage it
# into the combined server too (it lives in the BlockShips playtest server's plugins dir).
PROTOCOLLIB_JAR := $(firstword $(wildcard ../BlockShips/server/plugins/ProtocolLib*.jar) $(wildcard ../BlockShips/.download-cache/plugins/ProtocolLib*.jar))

.PHONY: blockships
blockships:
	@[ -n "$(BLOCKSHIPS_JAR)" ] || { echo "ERROR: no ../BlockShips/bin/BlockShips-*.jar found — run 'cd ../BlockShips && make build' first"; exit 1; }
	mkdir -p test-server/plugins
	cp $(BLOCKSHIPS_JAR) test-server/plugins/
	@echo "Copied $(BLOCKSHIPS_JAR) -> test-server/plugins/"
	@[ -n "$(PROTOCOLLIB_JAR)" ] && { cp $(PROTOCOLLIB_JAR) test-server/plugins/ && echo "Copied $(PROTOCOLLIB_JAR) -> test-server/plugins/ (ship position-sync)"; } || echo "WARN: no ProtocolLib jar found under ../BlockShips — moving ships will rubber-band until one is staged"

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
