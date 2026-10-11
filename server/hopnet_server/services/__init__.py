"""Registry of services the server offers. Add a new service (e.g. food.py) here."""

from .base import ServiceHandler
from .chat import ChatService
from .findme import FindMeService
from .info import InfoService
from .system import SystemService


def default_services() -> dict[str, ServiceHandler]:
    handlers: list[ServiceHandler] = [SystemService(), ChatService(), FindMeService(), InfoService()]
    return {h.id: h for h in handlers}


__all__ = ["ServiceHandler", "default_services"]
