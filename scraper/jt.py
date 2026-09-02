"""Provedor J&T — seed.

A implementação real (scraping do site público `jtexpress.com.br/trajectoryQuery`
ou lib de API própria, exigindo CPF/CNPJ do destinatário) é da Story 1.2.
Neste story, devolve um stub vazio no contrato fixo e sinaliza com 501 que o
provedor ainda não está implementado (semântica honesta do esqueleto).

O `cpf` do request é dado sensível (LGPD): nunca deve ser logado.
"""

from fastapi import HTTPException

from app import RastrearRequest, RastrearResponse


def rastrear(body: RastrearRequest) -> RastrearResponse:
    # O esqueleto ainda não implementa o rastreio real da J&T.
    # Sinalizar 501 evita silenciar o consumidor sobre a falta do provedor.
    raise HTTPException(status_code=501, detail="Provedor J&T ainda não implementado")
