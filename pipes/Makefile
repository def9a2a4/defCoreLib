.PHONY: build
build:
	gradle shadowJar
	mkdir -p bin
	cp build/libs/Pipes*.jar bin/

.PHONY: clean
clean:
	gradle clean
	rm -rf bin/

.PHONY: server-plugin-copy
server-plugin-copy:
	cp bin/Pipes*.jar server/plugins/

.PHONY: server-clear-plugin-data
server-clear-plugin-data:
	rm -rf server/plugins/Pipes/

.PHONY: server-start
server-start:
	cd server && java -Xmx2G -jar paper-1.21.11-55.jar --nogui

.PHONY: server
server: build server-plugin-copy server-start

.PHONY: all
all: clean build server

# =============================================================================
# Test Server
# =============================================================================

TEST_SERVER_DIR := test-server
DOWNLOAD_CACHE := .download-cache
MINECRAFT_VERSION ?= 1.21.11

$(DOWNLOAD_CACHE)/paper-%.jar:
	@mkdir -p $(DOWNLOAD_CACHE)
	curl -o $@ $$(curl -s -X POST "https://fill.papermc.io/graphql" \
		-H "Content-Type: application/json" \
		-d '{"query":"{ project(key: \"paper\") { version(key: \"$*\") { builds(orderBy: {direction: DESC}, first: 1) { edges { node { download(key: \"server:default\") { url } } } } } } }"}' \
		| jq -r '.data.project.version.builds.edges[0].node.download.url')

.PHONY: test-server-download
test-server-download: $(DOWNLOAD_CACHE)/paper-$(MINECRAFT_VERSION).jar
	mkdir -p $(TEST_SERVER_DIR)
	cp $< $(TEST_SERVER_DIR)/server.jar

.PHONY: test-server-setup
test-server-setup:
	mkdir -p $(TEST_SERVER_DIR)/plugins
	cp bin/*.jar $(TEST_SERVER_DIR)/plugins/
	echo "eula=true" > $(TEST_SERVER_DIR)/eula.txt
	printf "online-mode=false\nserver-port=25565\nmax-tick-time=-1\n" > $(TEST_SERVER_DIR)/server.properties
	@mkdir -p $(TEST_SERVER_DIR)/plugins/bStats
	@printf 'enabled: false\nserverUuid: "00000000-0000-0000-0000-000000000000"\nlogFailedRequests: false\nlogSentData: false\nlogResponseStatusText: false\n' > $(TEST_SERVER_DIR)/plugins/bStats/config.yml

.PHONY: test-server-startup-only
test-server-startup-only:
	@cd $(TEST_SERVER_DIR) && \
	mkfifo server_input 2>/dev/null || true; \
	tail -f server_input | java -Xmx1G -Xms1G -jar server.jar nogui > server.log 2>&1 & \
	SERVER_PID=$$!; \
	sleep 0.5; \
	tail -f server.log & \
	TAIL_PID=$$!; \
	echo "Waiting for server to start..."; \
	for i in $$(seq 1 600); do \
		if grep -q "Done.*For help" server.log 2>/dev/null; then \
			echo ""; \
			echo "========== Server started successfully =========="; \
			break; \
		fi; \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			echo ""; \
			echo "========== Server process died unexpectedly =========="; \
			cat server.log; \
			exit 1; \
		fi; \
		sleep 1; \
	done; \
	if ! grep -q "Done.*For help" server.log 2>/dev/null; then \
		echo ""; \
		echo "========== Server startup timed out =========="; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	if ! grep -q "Pipes.*enabled" server.log; then \
		echo "✗ Pipes plugin failed to load"; \
		cat server.log; \
		kill $$TAIL_PID 2>/dev/null || true; \
		kill $$SERVER_PID 2>/dev/null || true; \
		rm -f server_input; \
		exit 1; \
	fi; \
	echo "✓ Pipes plugin loaded"; \
	echo ""; \
	echo "========== Shutting down server =========="; \
	echo "stop" > server_input; \
	for i in $$(seq 1 30); do \
		if ! kill -0 $$SERVER_PID 2>/dev/null; then \
			break; \
		fi; \
		sleep 1; \
	done; \
	kill $$TAIL_PID 2>/dev/null || true; \
	kill $$SERVER_PID 2>/dev/null || true; \
	rm -f server_input; \
	rm -f errors.log; \
	FAILED=0; \
	if grep -qE "ERROR.*Pipes|Pipes.*Exception" server.log 2>/dev/null; then \
		echo "=== SERVER ERRORS ===" | tee -a errors.log; \
		grep -E "ERROR.*Pipes|Pipes.*Exception" server.log | tee -a errors.log; \
		FAILED=1; \
	fi; \
	if [ $$FAILED -eq 1 ]; then \
		echo "✗ Tests failed"; \
		exit 1; \
	else \
		echo "✓ Server startup test passed"; \
	fi

.PHONY: clean-test-server
clean-test-server:
	rm -rf $(TEST_SERVER_DIR)

.PHONY: clean-download-cache
clean-download-cache:
	rm -rf $(DOWNLOAD_CACHE)
