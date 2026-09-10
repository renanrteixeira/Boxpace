"""Testes do contrato de `POST /rastrear` (AD-SCRAPER-CONTRACT).

Cobre as linhas da matriz de I/O do story:
- HAPPY_PATH         → `{transportadora:"correios", codigo:"AA123"}` → 200 com eventos mapeados
                        (scraping real mockado via `correios._rastrear_sync`)
- JT_HAPPY_PATH      → `{transportadora:"jt", codigo, cpf}`       → 200 com eventos mapeados
                        (consulta real mockada via `jt._rastrear_sync`)
- JT_SEM_CPF         → J&T sem `cpf`                              → 400 claro
- JT_NAO_ENCONTRADO  → J&T recusou o par código/CPF               → 404
- INVALIDA           → transportadora ausente/fora de correios|jt → 422
"""

from fastapi.testclient import TestClient

import app
import correios
import jt

client = TestClient(app.app)

_EVENTOS = [
    {
        "dtHrCriado": "2026-09-01 10:30:00.000000",
        "descricaoWeb": "Objeto entregue ao destinatário",
        "unidade": {"nome": "CTE CUIABA", "endereco": {"cidade": "Cuiabá", "uf": "MT"}},
    }
]


_EVENTO_JT = {
    "scanTime": "2026-07-07 15:03:15",
    "desc": "O pacote foi assinado. O signatário é [porteiro].",
    "customerTracking": "[Teresópolis] O pacote foi assinado! O signatário é [porteiro].",
    "scanNetworkCity": "Teresópolis",
    "scanNetworkProvince": "RJ",
    "scanNetworkName": "TRS -RJ",
}

_EVENTO_JT_REAL = {
    "scanTime": "2026-09-09 17:34:56",
    "scanTypeName": "快件签收",
    "customerTracking": "[Feira de Santana] O pacote foi assinado! O signatário é [Assinar pelo próprio], se você tiver alguma dúvida, entre em contato com: 08000550050",
    "status": "Pedido Entregue",
    "code": 100,
    "remark1": "Assinar pelo próprio",
}


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


def test_rota_jt_happy_path_shape_real(monkeypatch) -> None:
    monkeypatch.setattr(jt, "_rastrear_sync", lambda codigo, cpf: {"details": [_EVENTO_JT_REAL]})

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["codigo"] == "888123"
    assert len(body["eventos"]) == 1
    evento = body["eventos"][0]
    assert evento["data"] == "2026-09-09T17:34:56"
    assert evento["descricao"] == _EVENTO_JT_REAL["customerTracking"]
    assert evento["cidade"] is None
    assert evento["uf"] is None
    assert evento["unidade"] is None


def test_rota_jt_assinatura_sem_customer_tracking_composta(monkeypatch) -> None:
    sem_ct = {k: None if k == "customerTracking" else v for k, v in _EVENTO_JT_REAL.items()}
    monkeypatch.setattr(jt, "_rastrear_sync", lambda codigo, cpf: {"details": [sem_ct]})

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 200
    assert resp.json()["eventos"][0]["descricao"] == "O pacote foi assinado! O signatário é [Assinar pelo próprio]."


def test_rota_jt_codigo_94_saiu_para_entrega(monkeypatch) -> None:
    monkeypatch.setattr(
        jt,
        "_rastrear_sync",
        lambda codigo, cpf: {"details": [{"scanTime": "2026-09-09 06:40:44", "code": 94}]},
    )

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 200
    assert resp.json()["eventos"][0]["descricao"] == "A encomenda saiu para entrega."


def test_rota_jt_status_chines_traduzido_para_pt(monkeypatch) -> None:
    monkeypatch.setattr(
        jt,
        "_rastrear_sync",
        lambda codigo, cpf: {"details": [{"scanTime": "2026-09-08 21:10:20", "status": "运送中"}]},
    )

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 200
    assert resp.json()["eventos"][0]["descricao"] == "Em trânsito"


def test_rota_jt_happy_path_mapeado(monkeypatch) -> None:
    monkeypatch.setattr(jt, "_rastrear_sync", lambda codigo, cpf: {"details": [_EVENTO_JT]})

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["codigo"] == "888123"
    assert len(body["eventos"]) == 1
    evento = body["eventos"][0]
    assert evento["data"] == "2026-07-07T15:03:15"
    assert evento["descricao"] == "[Teresópolis] O pacote foi assinado! O signatário é [porteiro]."
    assert evento["cidade"] == "Teresópolis"
    assert evento["uf"] == "RJ"
    assert evento["unidade"] == "TRS -RJ"


def test_header_langtype_pt_ativa_conteudo_em_portugues() -> None:
    headers = jt._header_sign({"cpf": "x", "waybillNo": "y", "langType": "PT"})

    assert headers["langType"] == "PT"


def test_rota_jt_sem_cpf_rejeita_400() -> None:
    resp = client.post("/rastrear", json={"transportadora": "jt", "codigo": "888123"})

    assert resp.status_code == 400
    assert "CPF/CNPJ" in resp.json()["detail"]


def test_rota_jt_nao_encontrado_devolve_404(monkeypatch) -> None:
    def recusa(codigo, cpf):
        raise jt.ObjetoNaoEncontradoError()

    monkeypatch.setattr(jt, "_rastrear_sync", recusa)

    resp = client.post(
        "/rastrear",
        json={"transportadora": "jt", "codigo": "888123", "cpf": "12345678909"},
    )

    assert resp.status_code == 404
    assert "não encontrado" in resp.json()["detail"]


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