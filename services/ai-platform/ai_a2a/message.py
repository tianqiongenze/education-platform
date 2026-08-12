"""Message, Part and Artifact models for A2A protocol."""

from __future__ import annotations

import base64
from dataclasses import dataclass, field
from typing import Any, Literal


@dataclass
class Part:
    """Base part."""

    kind: str = "text"

    def to_dict(self) -> dict[str, Any]:
        raise NotImplementedError

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Part":
        kind = data.get("kind", "text")
        if kind == "text":
            return TextPart.from_dict(data)
        if kind == "file":
            return FilePart.from_dict(data)
        if kind == "data":
            return DataPart.from_dict(data)
        raise ValueError(f"Unknown part kind: {kind}")


@dataclass
class TextPart(Part):
    text: str = ""

    def __post_init__(self) -> None:
        self.kind = "text"

    def to_dict(self) -> dict[str, Any]:
        return {"kind": "text", "text": self.text}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TextPart":
        return cls(text=data.get("text", ""))


@dataclass
class FilePart(Part):
    uri: str | None = None
    mime_type: str | None = None
    bytes_b64: str | None = None  # base64-encoded content

    def __post_init__(self) -> None:
        self.kind = "file"

    @classmethod
    def from_bytes(cls, data: bytes, mime_type: str | None = None) -> "FilePart":
        return cls(bytes_b64=base64.b64encode(data).decode("ascii"), mime_type=mime_type)

    def to_bytes(self) -> bytes | None:
        if self.bytes_b64 is None:
            return None
        return base64.b64decode(self.bytes_b64)

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {"kind": "file"}
        if self.uri is not None:
            d["uri"] = self.uri
        if self.mime_type is not None:
            d["mime_type"] = self.mime_type
        if self.bytes_b64 is not None:
            d["bytes"] = self.bytes_b64
        return d

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "FilePart":
        return cls(
            uri=data.get("uri"),
            mime_type=data.get("mime_type"),
            bytes_b64=data.get("bytes"),
        )


@dataclass
class DataPart(Part):
    json_data: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.kind = "data"

    def to_dict(self) -> dict[str, Any]:
        return {"kind": "data", "data": dict(self.json_data)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "DataPart":
        return cls(json_data=dict(data.get("data", {})))


Role = Literal["user", "agent"]


@dataclass
class Message:
    role: Role
    parts: list[Part] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {"role": self.role, "parts": [p.to_dict() for p in self.parts]}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Message":
        return cls(
            role=data["role"],
            parts=[Part.from_dict(p) for p in data.get("parts", [])],
        )

    @classmethod
    def text(cls, role: Role, text: str) -> "Message":
        return cls(role=role, parts=[TextPart(text=text)])


@dataclass
class Artifact:
    id: str
    name: str = ""
    parts: list[Part] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "parts": [p.to_dict() for p in self.parts],
            "metadata": dict(self.metadata),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Artifact":
        return cls(
            id=data["id"],
            name=data.get("name", ""),
            parts=[Part.from_dict(p) for p in data.get("parts", [])],
            metadata=dict(data.get("metadata", {})),
        )
