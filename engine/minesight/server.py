"""WebSocket server: the Forge mod connects here, streams player state in,
and receives detection messages out."""
from __future__ import annotations

import asyncio
import json
import logging

import websockets

log = logging.getLogger("minesight.server")


class DetectionServer:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.clients: set = set()
        # Clients that asked for preview/stats frames (the management GUI).
        # The mod never subscribes, keeping images off its connection per spec.
        self.preview_clients: set = set()
        self.player_state: dict | None = None  # latest, consumed by later phases
        # Active learning: latest frame + predictions, grabbed on demand when a
        # tester flags a mistake. Set by the pipeline each iteration.
        self.latest_frame = None
        self.latest_objects: list = []
        self.review_callback = None  # set by main: (frame, objects, reason) -> str|None
        self._server = None

    @property
    def mod_clients(self) -> int:
        return len(self.clients - self.preview_clients)

    async def start(self):
        self._server = await websockets.serve(self._handler, self.host, self.port)
        log.info("WebSocket server listening on ws://%s:%d", self.host, self.port)

    async def _handler(self, ws):
        self.clients.add(ws)
        log.info("Client connected: %s", ws.remote_address)
        try:
            async for message in ws:
                self._on_message(ws, message)
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            self.preview_clients.discard(ws)
            log.info("Client disconnected")

    def _on_message(self, ws, message) -> None:
        try:
            data = json.loads(message)
        except (json.JSONDecodeError, TypeError):
            return
        if not isinstance(data, dict):
            return
        msg_type = data.get("type")
        if msg_type == "player":
            # Phase 1 only stores it; screen-to-world mapping (Phase 4) consumes it.
            self.player_state = data
        elif msg_type == "subscribe_preview":
            self.preview_clients.add(ws)
        elif msg_type == "unsubscribe_preview":
            self.preview_clients.discard(ws)
        elif msg_type == "review_capture":
            # A tester flagged the current frame as a mistake (hotkey or GUI).
            self.capture_review("manual")

    def capture_review(self, reason: str) -> str | None:
        """Snapshot the current frame + predictions for human correction."""
        if self.review_callback is None or self.latest_frame is None:
            return None
        return self.review_callback(self.latest_frame, list(self.latest_objects), reason)

    async def broadcast(self, message: dict) -> None:
        if not self.clients:
            return
        payload = json.dumps(message)
        await asyncio.gather(*(self._send(ws, payload) for ws in list(self.clients)))

    async def broadcast_preview(self, message: dict) -> None:
        """Send to preview subscribers only (GUI), never the mod."""
        if not self.preview_clients:
            return
        payload = json.dumps(message)
        await asyncio.gather(*(self._send(ws, payload) for ws in list(self.preview_clients)))

    async def _send(self, ws, payload: str) -> None:
        try:
            await ws.send(payload)
        except websockets.ConnectionClosed:
            self.clients.discard(ws)
