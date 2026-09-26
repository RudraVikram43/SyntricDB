import base64

import requests

from . import exceptions


class Transport:
    """Shared HTTP calling code for the DB-API layer, mapping HTTP/JSON responses
    onto the PEP 249 exception hierarchy."""

    def __init__(self, host, port, user, password, timeout):
        self.host = host or "localhost"
        self.port = port or 8080
        self.user = user or "admin"
        self.password = password or "syntricdb_secret_pass"
        self.timeout = timeout
        raw = f"{self.user}:{self.password}".encode("utf-8")
        self._headers = {
            "Authorization": "Basic " + base64.b64encode(raw).decode("ascii"),
            "Accept": "application/json",
        }
        self.base_url = f"http://{self.host}:{self.port}"

    def request(self, method, path, body=None):
        url = f"{self.base_url}{path}"
        try:
            response = requests.request(
                method, url, json=body, headers=self._headers, timeout=self.timeout
            )
        except requests.exceptions.RequestException as e:
            raise exceptions.OperationalError(
                f"Could not reach SyntricDB server at {url}: {e}"
            ) from e

        try:
            payload = response.json() if response.content else {}
        except ValueError:
            payload = {}

        if response.status_code in (401, 403):
            message = payload.get("error", f"HTTP {response.status_code}")
            raise exceptions.InterfaceError(message)
        if response.status_code >= 400:
            message = payload.get("error", f"HTTP {response.status_code}")
            raise exceptions.ProgrammingError(message)
        return payload
