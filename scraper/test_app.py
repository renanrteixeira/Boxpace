"""Testes do contrato de `POST /rastrear` (AD-SCRAPER-CONTRACT).

Cobre as linhas da matriz de I/O do story:
- HAPPY_PATH         → `{transportadora:"correios", codigo:"AA123"}` → 200 `{codigo, eventos: []}`
- TRANSPORTADORA_ROTA→ `{transportadora:"jt", ...}`              → roteia para o provedor jt → 501 claro
- INVALIDA           → transportadora ausente/fora de correios|jt → 422
"""

from fastapi.testclient import TestClient

import app

client = TestClient(app.app)


def test_happy_path_correios_devolve_stub() -> None:
    resp = client.post("/rastrear", json={"transportadora": "correios", "codigo": "AA123"})

    assert resp.status_code == 200
    body = resp.json()
    assert body == {"codigo": "AA123", "eventos": []}


def test_rota_jt_roteia_e_devolve_501_claro() -> None:
    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    # O provedor jt (seed) não implementa scraping real → 501 com mensagem clara.
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
