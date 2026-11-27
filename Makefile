EMBED_MODEL ?= ggml-org/embeddinggemma-300M-GGUF

.PHONY: embeddings help up down nuke infra build test smoke ps logs pipeline-once

INFRA = postgres mongo redis rabbitmq rustfs lgtm postgres-exporter redis-exporter

help:
	@echo "make up          build and start everything (http://localhost:3000)"
	@echo "make infra       start only databases, broker, object store and Grafana"
	@echo "make down        stop everything (keeps data)"
	@echo "make nuke        stop everything and delete all data volumes"
	@echo "make test        run service and web unit tests"
	@echo "make smoke       two test accounts trade against each other through the gateway"
	@echo "make embeddings  run the llama.cpp embedding server on the host (port 8081, Phase 2)"
	@echo "make ps | logs   status / follow logs"

.env:
	cp .env.example .env

up: .env
	docker compose up -d --build

infra: .env
	docker compose up -d $(INFRA)

down:
	docker compose down

nuke:
	docker compose down -v

build:
	cd services && ./gradlew build -x test
	cd web && pnpm install --frozen-lockfile && pnpm build

test:
	cd services && ./gradlew test
	cd web && pnpm install --frozen-lockfile && pnpm test

smoke: .env
	python3 scripts/smoke.py

pipeline-once:
	docker compose exec pipeline python -m pipeline.run_once

ps:
	docker compose ps

logs:
	docker compose logs -f --tail=100

# Phase 2: EmbeddingGemma on the Mac's GPU (brew install llama.cpp). news-service reaches it
# through host.docker.internal:8081; Grafana scrapes its /metrics.
embeddings:
	llama-server -hf $(EMBED_MODEL) --embeddings --host 0.0.0.0 --port 8081 --metrics
