"""Provedor Correios — seed.

A implementação real de scraping (CAPTCHA Securimage + parse) é da Story 1.2.
Neste story, devolve um stub vazio no contrato fixo. Não define scraping aqui.
"""

from fastapi import HTTPException

from app import RastrearRequest, RastrearResponse


def rastrear(body: RastrearRequest) -> RastrearResponse:
    # Story 1.2: scraping de rastreamento.correios.com.br com solver CAPTCHA.
    # Por ora, stub no shape fixo do contrato (AD-SCRAPER-CONTRACT).
    if body.transportadora.value != "correios":
        raise HTTPException(status_code=400, detail="Provedor inválido para este módulo")

    return RastrearResponse(codigo=body.codigo, eventos=[])
