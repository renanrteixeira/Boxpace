# Boxpace

Aplicativo de rastreamento de encomendas estruturado por camadas (clean architecture),
com um backend de scraping separado (FastAPI).

Este repositório é o esqueleto do **Epic 1, Story 1.1 — Fundação da arquitetura**:
módulos Kotlin/Android `app/{domain,data,presentation}` e o serviço Python `scraper/`.

## Estrutura

```
app/                     -- app Android (Gradle multi-módulo)
  domain/                -- entidades, portas de repositório e use cases (apenas Kotlin puro/JVM, sem Android/HTTP/Drive)
  data/                  -- implementações local/ (Room), remote/ (scraper HTTP), cloud/ (Drive) e di/
  presentation/          -- telas Compose (Configurações) + MainActivity
scraper/                 -- backend de scraping (Python/FastAPI)
  app.py                 -- FastAPI: POST /rastrear (roteia por transportadora)
  correios.py            -- seed do provedor Correios
  jt.py                  -- seed do provedor J&T
  test_app.py            -- testes do contrato HTTP
  requirements.txt       -- dependências Python
```

## Pré-requisitos

- JDK 17+
- Android SDK (compileSdk 37)
- Python 3.13+

## Compilar e testar o app

Do diretório `app/`:

```bash
# Testes de unidade do domínio (JVM pura, sem dispositivo Android)
./gradlew :domain:test

# Compilar o projeto Android completo
./gradlew build
```

### Invariante de camadas (AD-1)

O módulo `domain` importa apenas Kotlin puro/JVM — sem Android, HTTP ou Google
Drive — e seus testes rodam sem dispositivo Android. `data` e `presentation`
dependem de `domain`, nunca o contrário (regra de dependência).

## Rodar e testar o scraper

Do diretório `scraper/`:

```bash
mkdir -p .venv && python -m venv .venv
.venv/bin/pip install -r requirements.txt

# Subir o servidor
.venv/bin/uvicorn app:app --app-dir . --port 8000

# Testes do contrato HTTP
.venv/bin/python -m pytest -v
```

### Contrato fixo `POST /rastrear`

Requisição:

```json
{ "transportadora": "correios|jt", "codigo": "AA123", "cpf": "opcional" }
```

Resposta (shape fixo):

```json
{ "codigo": "AA123", "eventos": [ { "data": "ISO-8601", "descricao": "...", "cidade": "...", "uf": "SP", "unidade": "..." } ] }
```

Comportamento:
- `transportadora` válida → stub no shape fixo (ou erro claro).
- `transportadora` ausente ou fora de `correios|jt` → `422`.
- Provedor reconhecido, porém não implementado (ex.: J&T) → `501` com mensagem clara.

## Escopo do esqueleto

Este story entrega apenas a fundação. **Não** faz parte dele (adiado para outras
stories/epics): scraping real com CAPTCHA/parse (Story 1.2), persistência Room,
OAuth/sincronização com Drive, worker de notificação, tema aplicado, busca e
'tema escuro' funcional. A tela **Configurações** é mera superfície de UI, sem
lógica de negócio, persistência ou estado.

O **CPF** do destinatário é dado pessoal sensível (LGPD): nunca é logado e deve
permanecer mascarado na UI.
