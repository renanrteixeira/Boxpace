"""Testes do contrato de `POST /rastrear` (AD-SCRAPER-CONTRACT).

Cobre as linhas da matriz de I/O do story:
- HAPPY_PATH         → `{transportadora:"correios", codigo:"AA123"}` → 200 com eventos mapeados
                       (scraping real mockado via `correios._rastrear_sync`)
- TRANSPORTADORA_ROTA→ `{transportadora:"jt", ...}`              → roteia para o provedor jt → 501 claro
- INVALIDA           → transportadora ausente/fora de correios|jt → 422
"""

from fastapi.testclient import TestClient

import app
import correios

client = TestClient(app.app)

_EVENTOS = [
    {
        "dtHrCriado": "2026-09-01 10:30:00.000000",
        "descricaoWeb": "Objeto entregue ao destinatário",
        "unidade": {"nome": "CTE CUIABA", "endereco": {"cidade": "Cuiabá", "uf": "MT"}},
    }
]


def test_happy_path_correios_scraping_mapeado(monkeypatch) -> None:
    monkeypatch.setattr(correios, "_rastrear_sync", lambda codigo: {"eventos": _EVENTOS})

    resp = client.post("/rastrear", json={"transportadora": "correios", "codigo": "AA123"})

    assert resp.status_code == 200
    body = resp.json()
    assert body["codigo"] == "AA123"
    assert len(body["eventos"]) == 1
    assert body["eventos"][0]["data"] == "2026-09-01T10:30:00"
    assert body["eventos"][0]["descricao"] == "Objeto entregue ao destinatário"
    assert body["eventos"][0]["cidade"] == "Cuiabá"
    assert body["eventos"][0]["uf"] == "MT"
    assert body["eventos"][0]["unidade"] == "CTE CUIABA"


def test_rota_jt_roteia_e_devolve_501_claro() -> None:
    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 501
    assert "não implementado" in resp.json()["detail"]


def test_transportadora_ausente_rejeita_422() -> None:
    resp = client.post("/rastrear", json={"codigo": "AA123"})

    assert resp.status_code == 422


def test_transportadora_invalida_rejeita_422() -> None:
    resp = client.post("/rastrear", json={"transportadora": "sedex", "codigo": "AA123"})

    assert resp.status_code == 422


def test_codigo_vazio_rejeita_422() -> None:
    resp = client.post("/rastrear", json={"transportadora": "correios", "codigo": ""})

    assert resp.status_code == 422


def test_cpf_acima_de_14_digitos_rejeita_422() -> None:
    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "123456789012345"},
    )

    assert resp.status_code == 422