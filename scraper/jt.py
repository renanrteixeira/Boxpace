"""Provedor J&T — scraping real via API pública do site oficial.

O site `jtexpress.com.br` consulta `POST https://official.jtjms-br.com/...`
com assinatura MD5 nos headers (a `key`/`appId` são públicas do próprio
bundle web). O mesmo endpoint é usado aqui, sem CAPTCHA:

  `/official/logisticsTracking/v2/getDetailByWaybillNo`

O site oficial exige o CPF/CNPJ do destinatário junto do código (contrato do
app já carrega `cpfDestinatario`; a UI pede o documento para J&T). CPF é dado
sensível (LGPD) — nunca deve ser logado nem persistido aqui.

- Sem CPF/CNPJ            → 400 (o rastreio J&T exige o documento)
- Código não encontrado/não pertence ao CPF → 404
- Falha de rede/API       → 502
"""

from datetime import datetime
import hashlib
import json
import time

from fastapi import HTTPException

from app import EventoDTO, RastrearRequest, RastrearResponse

_BASE_URL = "https://official.jtjms-br.com"
_DETAIL_PATH = "/official/logisticsTracking/v2/getDetailByWaybillNo"

_APP_ID = "3B29A9C5728BF3E1DB0C4D66B79748B7"
_API_KEY = "94bbcac67ab47c736d530efe3e1dc358"

_TIMEOUT = 15

_TIMEZONE = "-0300"

_LANG_TYPE = "PT"  # maiúsculo, no HEADER: é o que ativa o conteúdo em português.

# `remark1` vem em português no delivery; nos demais eventos o app devolve só o
# `status`/`scanTypeName` em chinês — mapeamos os estados comuns para PT.
_STATUS_PT = {
    "已揽件": "Coletado",
    "已取件": "Coletado",
    "运送中": "Em trânsito",
    "运输中": "Em trânsito",
    "派件中": "Em rota de entrega",
    "已签收": "Entregue",
    "已到达": "Chegou à unidade",
    "到达站点": "Chegou à unidade",
    "快件揽收": "Coleta da encomenda",
    "快件签收": "Assinatura da encomenda",
    "出仓扫描": "Saiu para entrega",
}

# O endpoint real não devolve o texto montado do site (só código + `remark1`).
# Compomos o português pelos códigos de varredura da J&T (fonte canônica).
_CÓDIGO_PT = {
    10: "Coletado",
    50: "Em trânsito",
    90: "Chegou ao centro de distribuição",
    92: "Chegou à unidade",
    94: "A encomenda saiu para entrega.",
    100: None,  # composto com o signatário, ver `_assinar_entrega`
}


def _assinar_entrega(item: dict) -> str:
    signatario = item.get("remark1") or item.get("signer") or ""
    if signatario:
        return f"O pacote foi assinado! O signatário é [{signatario}]."
    return "O pacote foi assinado!"


class ObjetoNaoEncontradoError(Exception):
    """Código inexistente ou que não pertence ao CPF/CNPJ informado."""


class ParametroInvalidoError(Exception):
    """O parâmetro informado foi recusado pela API da J&T (CPF/CNPJ, formato)."""


class RespostaInvalidaError(Exception):
    """Resposta da J&T inesperada (rede, JSON inválido ou shape ausente)."""


def _nova_sessao():
    # Só cria sessão real sob demanda; os testes injetam uma sessão fake ao
    # monkeypatch `_nova_sessao`, sem depender de curl_cffi instalado.
    from curl_cffi import requests as cffi_requests

    return cffi_requests.Session(impersonate="chrome124")



def _md5(texto: str) -> str:
    return hashlib.md5(texto.encode()).hexdigest().upper()


def _ordenar_fingerprint(valor):
    """Recria o `jr`+`Cr` do bundle web: remove nulos e ordena chaves."""
    if isinstance(valor, dict):
        return {chave: _ordenar_fingerprint(valor[chave]) for chave in sorted(valor) if valor[chave] is not None}
    if isinstance(valor, list):
        return [_ordenar_fingerprint(item) for item in valor]
    return valor


def _header_sign(dados: dict) -> dict:
    """Reproduz a assinatura `sign` do site: MD5(appId+timestamp+nonce+JSON+key)."""
    timestamp = str(int(time.time() * 1000))
    nonce = "{:f}".format(time.time()).replace(".", "")
    corpo = json.dumps(_ordenar_fingerprint(dados), separators=(",", ":"), ensure_ascii=False)
    sign = _md5(_APP_ID + timestamp + nonce + corpo + _API_KEY)
    return {
        "langType": _LANG_TYPE,
        "clientSource": "web",
        "timezone": _TIMEZONE,
        "timeZone": _TIMEZONE,
        "countryId": "1",
        "token": "",
        "appId": _APP_ID,
        "key": _API_KEY,
        "nonce": nonce,
        "timestamp": timestamp,
        "sign": sign,
    }


def _solicitar(sessao, dados: dict) -> dict:
    try:
        resposta = sessao.post(
            f"{_BASE_URL}{_DETAIL_PATH}",
            headers=_header_sign(dados),
            json=dados,
            timeout=_TIMEOUT,
        )
        resposta.raise_for_status()
        return resposta.json()
    except Exception as exc:
        # curl_cffi ausente, rede fora, JSON inválido ou status HTTP != 2xx.
        raise RespostaInvalidaError(str(exc)) from None


def _rastrear_sync(codigo: str, cpf: str) -> dict:
    """Consulta a API pública da J&T e devolve o `data` de detalhes."""
    sessao = _nova_sessao()
    try:
        dados = _solicitar(sessao, {"cpf": cpf, "waybillNo": codigo, "langType": _LANG_TYPE})
        if not isinstance(dados, dict):
            raise RespostaInvalidaError("Resposta não é objeto JSON.")
        if not dados.get("succ"):
            codigo_erro = dados.get("code")
            erro = dados.get("msg") or "Consulta recusada pela J&T."
            if codigo_erro == 999001030:
                # 999001030 = "parâmetro inválido" (ex.: cpf não pode ser vazio).
                raise ParametroInvalidoError(str(erro))
            raise ObjetoNaoEncontradoError(str(erro))
        detalhes = dados.get("data") or {}
        if not isinstance(detalhes, dict) or not isinstance(detalhes.get("details"), list):
            raise RespostaInvalidaError("Shape inesperado na resposta da J&T.")
        return detalhes
    finally:
        sessao.close()


def _normalizar_data(item: dict) -> str:
    """`scanTime` é `"2026-07-07 15:03:15"` (ou com fração) → ISO-8601."""
    bruto = str(item.get("scanTime") or "").strip()
    if " " in bruto:
        data, hora = bruto.split(None, 1)
        return f"{data}T{hora.split('.')[0]}"
    return bruto


def _mapear_evento(item: dict) -> EventoDTO:
    if not isinstance(item, dict):
        return EventoDTO(data="", descricao="")
    # Com `langType: PT` no header, a API devolve `customerTracking`/`status`
    # prontos em português — é o texto preferido. Com código 100 sem texto,
    # `remark1` é só o signatário: compomos a frase de entrega.
    descricao = item.get("customerTracking") or item.get("desc")
    codigo = item.get("code")
    if not descricao and codigo == 100:
        descricao = _assinar_entrega(item)
    if not descricao:
        descricao = item.get("remark1") or ""
    if not descricao and codigo in _CÓDIGO_PT:
        descricao = _CÓDIGO_PT[codigo]
    if not descricao:
        descricao = (
            _STATUS_PT.get(str(item.get("status") or item.get("scanTypeName") or ""))
            or item.get("status")
            or item.get("scanTypeName")
            or ""
        )
    return EventoDTO(
        data=_normalizar_data(item),
        descricao=str(descricao),
        cidade=item.get("scanNetworkCity") or None,
        uf=item.get("scanNetworkProvince") or None,
        unidade=item.get("scanNetworkName") or None,
    )


def _chave_ordenacao(evento: EventoDTO) -> datetime:
    try:
        return datetime.strptime(evento.data, "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return datetime.min


def rastrear(body: RastrearRequest) -> RastrearResponse:
    if body.transportadora.value != "jt":
        raise HTTPException(status_code=400, detail="Provedor inválido para este módulo")

    cpf = (body.cpf or "").strip()
    if not cpf:
        raise HTTPException(
            status_code=400,
            detail="O rastreio J&T exige o CPF/CNPJ do destinatário informado na adição.",
        )

    try:
        detalhes = _rastrear_sync(body.codigo, cpf)
    except ParametroInvalidoError as exc:
        raise HTTPException(status_code=400, detail=str(exc) or "Parâmetro inválido para a J&T.") from None
    except ObjetoNaoEncontradoError:
        raise HTTPException(
            status_code=404,
            detail="Código não encontrado na base da J&T para o CPF/CNPJ informado.",
        ) from None
    except RespostaInvalidaError as exc:
        detail = str(exc) or "Resposta inválida da J&T."
        raise HTTPException(status_code=502, detail=detail) from None

    eventos = [_mapear_evento(item) for item in detalhes.get("details") or []]
    eventos.sort(key=_chave_ordenacao)
    return RastrearResponse(codigo=body.codigo, eventos=eventos)