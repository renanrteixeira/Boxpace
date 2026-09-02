"""Provedor Correios — scraping real.

Fluxo por requisição (verificado no site oficial dos Correios):
  1. GET index.php                       → cookie de sessão (RASTREAMENTO/INGRESSCOOKIE)
  2. GET securimage_show.php             → imagem CAPTCHA (Securimage)
  3. Resolve CAPTCHA com solver CRNN local (`captcha/`, componente MIT)
  4. GET resultado.php?objeto&captcha&mqs=S → JSON com os eventos

O scraper próprio continua dono do App/do contrato (AD-7); o repo
`correios-rastreamento` (MIT) cede apenas o componente de CAPTCHA.

As falhas do fluxo são modeladas como exceções de domínio deste módulo,
traduzidas para HTTP em `rastrear()` — a fronteira HTTP não conhece detalhes
de scraping.
"""

from datetime import datetime

from fastapi import HTTPException

from app import EventoDTO, RastrearRequest, RastrearResponse

_BASE_URL = "https://rastreamento.correios.com.br/app"
_CAPTCHA_URL = "https://rastreamento.correios.com.br/core/securimage/securimage_show.php"

MAX_RETRIES = 4
_TIMEOUT = 15

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
    "Accept-Language": "pt-BR,pt;q=0.9",
    "Referer": f"{_BASE_URL}/index.php",
}


class CaptchaEsgotadoError(Exception):
    """O CAPTCHA não resolveu após MAX_RETRIES tentativas."""


class ObjetoNaoEncontradoError(Exception):
    """O código não existe na base de dados dos Correios."""


class RespostaInvalidaError(Exception):
    """Resposta do Correios inesperada (JSON inválido, shape ausente ou rede)."""


class SolverIndisponivelError(Exception):
    """Solver de CAPTCHA indisponível (torch/PIL/não baixado)."""


def _nova_sessao():
    # Só cria sessão real sob demanda; os testes injetam a sessão fake ao
    # monkeypatch `_nova_sessao`, sem depender de curl_cffi instalado.
    from curl_cffi import requests as cffi_requests

    return cffi_requests.Session(impersonate="chrome124")


def _fetch_captcha(sessao) -> bytes:
    # Import lazy (como no solver): a integração curl_cffi é uma dependência
    # opcional de runtime. Se ausente, a falha é tratada como falha de rede —
    # o retry tenta de novo, e só após esgotar MAX_RETRIES vira 502.
    from curl_cffi import requests as cffi_requests

    sessao.get(f"{_BASE_URL}/index.php", headers=_HEADERS, timeout=_TIMEOUT)
    resposta = sessao.get(_CAPTCHA_URL, headers=_HEADERS, timeout=_TIMEOUT)
    resposta.raise_for_status()
    return resposta.content


def _solve_captcha(image_bytes: bytes) -> str:
    # Import lazy: carregar o módulo app nunca exige torch/PIL instalados.
    try:
        from captcha.predictor import predict

        return predict(image_bytes).strip()
    except Exception:
        raise SolverIndisponivelError() from None


def _is_captcha_error(dados) -> bool:
    if not isinstance(dados, dict):
        return False
    erro = dados.get("erro")
    erro_flag = erro is True or str(erro).lower() == "true"
    return erro_flag and "captcha" in dados.get("mensagem", "").lower()


def _rastrear_sync(codigo: str) -> dict:
    """Executa o fluxo de scraping com retry de CAPTCHA/rede."""
    sessao = _nova_sessao()
    motivo = "rede"

    for _ in range(MAX_RETRIES):
        try:
            imagem = _fetch_captcha(sessao)
            captcha_text = _solve_captcha(imagem)
        except (CaptchaEsgotadoError, ObjetoNaoEncontradoError, RespostaInvalidaError, SolverIndisponivelError):
            raise
        except Exception:
            # Falha transitória de rede/HTTP — só a rede vale nova tentativa.
            motivo = "rede"
            continue

        try:
            resposta = sessao.get(
                f"{_BASE_URL}/resultado.php",
                params={"objeto": codigo, "captcha": captcha_text, "mqs": "S"},
                headers=_HEADERS,
                timeout=_TIMEOUT,
            )
            resposta.raise_for_status()
        except Exception:
            motivo = "rede"
            continue

        try:
            dados = resposta.json()
        except ValueError:
            raise RespostaInvalidaError() from None

        if _is_captcha_error(dados):
            motivo = "captcha"
            continue
        if dados.get("erro"):
            raise ObjetoNaoEncontradoError()
        if not isinstance(dados, dict) or not isinstance(dados.get("eventos"), list):
            raise RespostaInvalidaError()
        return dados

    if motivo == "captcha":
        raise CaptchaEsgotadoError()
    raise RespostaInvalidaError(f"Não foi possível consultar os Correios após {MAX_RETRIES} tentativas.")


def _normalizar_data(item: dict) -> str:
    """`dtHrCriado` é `"2026-01-01 10:00:00.000000"` (str) — e em alguns parses
    aparece como dict `{.date}`. Normaliza para ISO-8601 sem fração de segundo.
    """
    bruto = item.get("dtHrCriado")
    if isinstance(bruto, dict):
        bruto = bruto.get("date")
    if isinstance(bruto, str):
        bruto = bruto.strip()
        if " " in bruto:
            data, hora = bruto.split(None, 1)
            return f"{data}T{hora.split('.')[0]}"
    return ""


def _cidade_uf_da_unidade(item: dict) -> tuple[str | None, str | None]:
    unidade = item.get("unidade")
    endereco = unidade.get("endereco") if isinstance(unidade, dict) else None
    if isinstance(endereco, dict):
        return endereco.get("cidade"), endereco.get("uf")
    return None, None


def _nome_da_unidade(item: dict) -> str | None:
    unidade = item.get("unidade")
    if not isinstance(unidade, dict):
        return None
    nome = unidade.get("nome")
    if nome:
        return str(nome)
    endereco = unidade.get("endereco")
    if isinstance(endereco, dict):
        partes = [p for p in (endereco.get("logradouro"), endereco.get("numero")) if p]
        if partes:
            return ", ".join(str(p) for p in partes)
    return None


def _mapear_evento(item: dict) -> EventoDTO:
    cidade, uf = _cidade_uf_da_unidade(item)
    return EventoDTO(
        data=_normalizar_data(item),
        descricao=str(item.get("descricaoWeb") or item.get("descricao") or ""),
        cidade=cidade,
        uf=uf,
        unidade=_nome_da_unidade(item),
    )


def _chave_ordenacao(evento: EventoDTO) -> datetime:
    try:
        return datetime.strptime(evento.data, "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return datetime.min


def rastrear(body: RastrearRequest) -> RastrearResponse:
    if body.transportadora.value != "correios":
        raise HTTPException(status_code=400, detail="Provedor inválido para este módulo")

    try:
        objeto = _rastrear_sync(body.codigo)
    except ObjetoNaoEncontradoError:
        raise HTTPException(status_code=404, detail="Objeto não encontrado na base de dados dos Correios.") from None
    except CaptchaEsgotadoError:
        raise HTTPException(status_code=400, detail=f"CAPTCHA inválido após {MAX_RETRIES} tentativas.") from None
    except RespostaInvalidaError as exc:
        detail = str(exc) or "Resposta inválida do Correios."
        raise HTTPException(status_code=502, detail=detail) from None
    except SolverIndisponivelError:
        raise HTTPException(status_code=502, detail="Solver de CAPTCHA indisponível.") from None

    eventos = [_mapear_evento(item) for item in objeto.get("eventos") or []]
    eventos.sort(key=_chave_ordenacao)
    return RastrearResponse(codigo=body.codigo, eventos=eventos)