"""Testes unitários do provedor Correios (sem rede, sem torch).

Cobre a matriz de I/O do story:

- mapeamento do JSON oficial → EventoDTO (dtHrCriado str OU dict{.date},
  descricaoWeb→fallback descricao, cidade/uf do endereço, unidade com fallback
  `logradouro, numero`) e ordenação cronológica;
- erros traduzidos (404 não-encontrado / 400 captcha esgotado / 502 resposta
  inválida / 502 solver indisponível);
- retry de CAPTCHA com solver mockada (contagem = MAX_RETRIES);
- lazy-import do solver: sem torch, `_solve_captcha` → SolverIndisponivelError.
"""

import pytest
from fastapi import HTTPException

import correios
from app import EventoDTO, RastrearRequest

CODIGO = "AA000000000BR"


class FakeResponse:
    def __init__(self, payload=None, invalid_json=False, content=b"imagem"):
        self._payload = payload
        self._invalid_json = invalid_json
        self.content = content

    def raise_for_status(self) -> None:
        return None

    def json(self):
        if self._invalid_json:
            raise ValueError("resposta não é JSON")
        return self._payload() if callable(self._payload) else self._payload


class FakeSession:
    def __init__(self, resultado=None, invalid_json=False):
        self.resultado = resultado
        self.invalid_json = invalid_json
        self.calls = []

    def get(self, url, params=None, headers=None, timeout=None):
        self.calls.append((url, params))
        if "resultado.php" in url:
            return FakeResponse(payload=self.resultado, invalid_json=self.invalid_json)
        return FakeResponse(content=b"imagem")

    def close(self):
        pass


class NetFailSession:
    """Simula falha transitória de rede: `get` levanta por `max_raises` chamadas,
    depois passa a responder — recuperando no N-ésimo retry e exigindo cópia de
    `get` contada para assertar o número de tentativas.
    """

    def __init__(self, max_raises=0, resultado=None):
        self.max_raises = max_raises
        self.resultado = resultado or {"eventos": []}
        self.raises = 0
        self.calls = []

    def get(self, url, params=None, headers=None, timeout=None):
        self.calls.append((url, params))
        if self.raises < self.max_raises:
            self.raises += 1
            raise OSError("tempo de conexão esgotado")
        return FakeResponse(payload=self.resultado)

    def close(self):
        pass


def _request(**kw) -> RastrearRequest:
    base = {"transportadora": "correios", "codigo": CODIGO}
    base.update(kw)
    return RastrearRequest(**base)


def test_mapeia_shape_oficial_ordenado_cronologicamente(monkeypatch) -> None:
    evento_dict_date = {
        "descricaoWeb": "Entregue",
        "dtHrCriado": {"date": "2026-09-01 08:00:00.000000", "timezone_type": 3, "timezone": "America/Sao_Paulo"},
        "unidade": {"tipo": "Unidade de Distribuição", "endereco": {"cidade": "SAO PAULO", "uf": "SP"}},
    }
    evento_sem_unidade = {
        "descricao": "Objeto postado",
        "dtHrCriado": "2026-08-31 10:00:00.000000",
    }
    evento_fallback_unidade = {
        "descricaoWeb": "Saiu para entrega",
        "dtHrCriado": "2026-08-31 18:00:00.000000",
        "unidade": {"endereco": {"cidade": "SAO PAULO", "uf": "SP", "logradouro": "AV REBOUCAS", "numero": "1000"}},
    }
    dados = {"codObjeto": CODIGO, "eventos": [evento_dict_date, evento_sem_unidade, evento_fallback_unidade]}
    monkeypatch.setattr(correios, "_rastrear_sync", lambda codigo: dados)

    resp = correios.rastrear(_request())

    assert resp.codigo == CODIGO
    assert resp.eventos == [
        EventoDTO(data="2026-08-31T10:00:00", descricao="Objeto postado", unidade=None),
        EventoDTO(
            data="2026-08-31T18:00:00",
            descricao="Saiu para entrega",
            cidade="SAO PAULO",
            uf="SP",
            unidade="AV REBOUCAS, 1000",
        ),
        EventoDTO(data="2026-09-01T08:00:00", descricao="Entregue", cidade="SAO PAULO", uf="SP", unidade=None),
    ]


def test_mapeia_unidade_pelo_nome_quando_presente(monkeypatch) -> None:
    dados = {
        "eventos": [
            {
                "descricaoWeb": "Entrega",
                "dtHrCriado": "2026-09-01 10:00:00.000000",
                "unidade": {"nome": "CDD PINHEIROS", "endereco": {"cidade": "SAO PAULO", "uf": "SP"}},
            }
        ]
    }
    monkeypatch.setattr(correios, "_rastrear_sync", lambda codigo: dados)

    resp = correios.rastrear(_request())

    assert resp.eventos[0].unidade == "CDD PINHEIROS"


def test_objeto_nao_encontrado_404(monkeypatch) -> None:
    def rais(*_a, **_kw):
        raise correios.ObjetoNaoEncontradoError()

    monkeypatch.setattr(correios, "_rastrear_sync", rais)

    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request())

    assert exc.value.status_code == 404
    assert exc.value.detail == "Objeto não encontrado na base de dados dos Correios."


def test_captcha_esgotado_400(monkeypatch) -> None:
    def rais(*_a, **_kw):
        raise correios.CaptchaEsgotadoError()

    monkeypatch.setattr(correios, "_rastrear_sync", rais)

    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request())

    assert exc.value.status_code == 400
    assert exc.value.detail == "CAPTCHA inválido após 4 tentativas."


def test_resposta_invalida_502(monkeypatch) -> None:
    def rais(*_a, **_kw):
        raise correios.RespostaInvalidaError()

    monkeypatch.setattr(correios, "_rastrear_sync", rais)

    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request())

    assert exc.value.status_code == 502
    assert exc.value.detail == "Resposta inválida do Correios."


def test_solver_indisponivel_502(monkeypatch) -> None:
    def rais(*_a, **_kw):
        raise correios.SolverIndisponivelError()

    monkeypatch.setattr(correios, "_rastrear_sync", rais)

    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request())

    assert exc.value.status_code == 502
    assert exc.value.detail == "Solver de CAPTCHA indisponível."


def test_solve_captcha_sem_torch_levanta_solver_indisponivel() -> None:
    with pytest.raises(correios.SolverIndisponivelError):
        correios._solve_captcha(b"imagem")


def test_retry_soluciona_apos_captcha_errado_na_primeira(monkeypatch) -> None:
    payload_captcha = {"erro": True, "mensagem": "CAPTCHA inválido"}
    payload_ok = {"eventos": [{"descricaoWeb": "Postado", "dtHrCriado": "2026-09-01 10:00:00.000000"}]}
    respostas = iter([payload_captcha, payload_ok])

    def fake_session():
        fake_session.sess = FakeSession(resultado=lambda: next(respostas))
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_fetch_captcha", lambda session: b"imagem")
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "wxyz")

    dados = correios._rastrear_sync(CODIGO)

    resultado_calls = [c for c in fake_session.sess.calls if "resultado.php" in c[0]]
    assert len(resultado_calls) == 2
    assert [params["captcha"] for _, params in resultado_calls] == ["wxyz", "wxyz"]
    assert dados["eventos"][0]["dtHrCriado"] == "2026-09-01 10:00:00.000000"


def test_captcha_errado_4x_exaure_retry(monkeypatch) -> None:
    payload_captcha = {"erro": True, "mensagem": "CAPTCHA inválido"}

    def fake_session():
        fake_session.sess = FakeSession(resultado=payload_captcha)
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_fetch_captcha", lambda session: b"imagem")
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "wxyz")

    with pytest.raises(correios.CaptchaEsgotadoError):
        correios._rastrear_sync(CODIGO)

    resultado_calls = [c for c in fake_session.sess.calls if "resultado.php" in c[0]]
    assert len(resultado_calls) == correios.MAX_RETRIES == 4
    assert all(params["captcha"] == "wxyz" for _, params in resultado_calls)
    assert all(params["objeto"] == CODIGO and params["mqs"] == "S" for _, params in resultado_calls)


def test_resposta_nao_parseavel_502_imediata(monkeypatch) -> None:
    def fake_session():
        fake_session.sess = FakeSession(invalid_json=True)
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_fetch_captcha", lambda session: b"imagem")
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "abcd")

    with pytest.raises(correios.RespostaInvalidaError):
        correios._rastrear_sync(CODIGO)

    resultado_calls = [c for c in fake_session.sess.calls if "resultado.php" in c[0]]
    assert len(resultado_calls) == 1


def test_resposta_sem_eventos_e_sem_erro_502_imediata(monkeypatch) -> None:
    def fake_session():
        fake_session.sess = FakeSession(resultado={"objeto": {}, "nao_e_eventos": []})
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_fetch_captcha", lambda session: b"imagem")
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "abcd")

    with pytest.raises(correios.RespostaInvalidaError):
        correios._rastrear_sync(CODIGO)

    resultado_calls = [c for c in fake_session.sess.calls if "resultado.php" in c[0]]
    assert len(resultado_calls) == 1


def test_erro_nao_captcha_404_imediato_sem_retry(monkeypatch) -> None:
    payload_erro = {
        "erro": "Objeto não encontrado na base de dados dos Correios.",
        "mensagem": "Objeto não encontrado na base de dados dos Correios.",
    }

    def fake_session():
        fake_session.sess = FakeSession(resultado=payload_erro)
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_fetch_captcha", lambda session: b"imagem")
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "abcd")

    with pytest.raises(correios.ObjetoNaoEncontradoError):
        correios._rastrear_sync(CODIGO)

    resultado_calls = [c for c in fake_session.sess.calls if "resultado.php" in c[0]]
    assert len(resultado_calls) == 1


@pytest.mark.parametrize(
    ("data", "esperado"),
    [
        ({"erro": True, "mensagem": "CAPTCHA inválido."}, True),
        ({"erro": "true", "mensagem": "Captcha inválido."}, True),
        ({"erro": True, "mensagem": "Objeto não encontrado."}, False),
        ({"erro": "Objeto não encontrado."}, False),
        ([], False),
    ],
)
def test_detecta_captcha_error(data, esperado) -> None:
    assert correios._is_captcha_error(data) is esperado


def test_transportadora_invalida_no_modulo_400() -> None:
    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request(transportadora="jt"))

    assert exc.value.status_code == 400


def test_falha_de_rede_exaure_retry_e_502(monkeypatch) -> None:
    def fake_session():
        fake_session.sess = NetFailSession(max_raises=10 ** 3)
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "wxyz")

    with pytest.raises(HTTPException) as exc:
        correios.rastrear(_request())

    assert exc.value.status_code == 502
    assert exc.value.detail == "Não foi possível consultar os Correios após 4 tentativas."
    assert len(fake_session.sess.calls) == correios.MAX_RETRIES  # uma tentativa por vez, falha no index.php


def test_falha_de_rede_recupera_no_retry_seguinte(monkeypatch) -> None:
    dados = {"eventos": [{"descricaoWeb": "Postado", "dtHrCriado": "2026-09-01 10:00:00.000000"}]}

    def fake_session():
        fake_session.sess = NetFailSession(max_raises=3, resultado=dados)
        return fake_session.sess

    monkeypatch.setattr(correios, "_nova_sessao", fake_session)
    monkeypatch.setattr(correios, "_solve_captcha", lambda image_bytes: "wxyz")

    resp = correios.rastrear(_request())

    assert resp.codigo == CODIGO
    assert len(resp.eventos) == 1