"""Backend de scraping do Boxpace — serviço FastAPI separado do app Android.

Porta de saída efêmera e não-persistente (AD-REMOTE-ROLES). Apenas o esqueleto
deste story: expõe o contrato fixo `POST /rastrear` (AD-SCRAPER-CONTRACT),
roteando por `transportadora` (AD-TRANSPORTADORA). Provedores reais (scraping
com CAPTCHA/parse) são implementados na Story 1.2.
"""

from enum import Enum

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Boxpace Scraper", version="0.1.0")


class Transportadora(str, Enum):
    CORREIOS = "correios"
    JT = "jt"


class RastrearRequest(BaseModel):
    transportadora: Transportadora
    codigo: str = Field(min_length=1)
    cpf: str | None = Field(default=None, max_length=14)


class EventoDTO(BaseModel):
    data: str
    descricao: str
    cidade: str | None = None
    uf: str | None = None
    unidade: str | None = None


class RastrearResponse(BaseModel):
    codigo: str
    eventos: list[EventoDTO] = Field(default_factory=list)


@app.post("/rastrear", response_model=RastrearResponse, responses={501: {"description": "Provedor não implementado"}})
def rastrear(body: RastrearRequest) -> RastrearResponse:
    """Roteia o rastreio para o provedor da transportadora escolhida.

    O contrato fixo preserva a fronteira HTTP: `data/remote` no app traduz esse
    shape para o `Evento` de domínio (AD-SCRAPER-CONTRACT).
    """
    provedor = PROVEDORES.get(body.transportadora)

    if provedor is None or not hasattr(provedor, "rastrear"):
        raise HTTPException(status_code=501, detail="Provedor não implementado")

    return provedor.rastrear(body)


PROVEDORES: dict[Transportadora, object] = {}


def _import_providers() -> None:
    # Importado no final para evitar importação circular (provedores importam
    # os modelos deste módulo via `from app import ...`).
    import correios
    import jt

    PROVEDORES[Transportadora.CORREIOS] = correios
    PROVEDORES[Transportadora.JT] = jt


_import_providers()
