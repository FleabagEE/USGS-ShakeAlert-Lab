"""Protected credential-file validation without secret disclosure."""

from __future__ import annotations

from dataclasses import dataclass, fields
import os
from pathlib import Path

from shakealert_lab.config import CredentialPaths


class CredentialError(RuntimeError):
    """Raised when a configured credential file is unsafe."""


@dataclass(frozen=True, slots=True)
class CredentialStatus:
    username: bool
    password: bool
    client_certificate: bool
    private_key: bool
    ca_certificate: bool

    def display_lines(self) -> tuple[str, ...]:
        return tuple(
            f"{item.name.replace('_', ' ')}: {'present' if getattr(self, item.name) else 'not configured'}"
            for item in fields(self)
        )


def inspect_credentials(paths: CredentialPaths) -> CredentialStatus:
    """Check existence, regular-file type, owner access, and mode only."""
    results: dict[str, bool] = {}
    for item in fields(paths):
        path = getattr(paths, item.name)
        if path is None:
            results[item.name] = False
            continue
        if not isinstance(path, Path):
            raise CredentialError(f"{item.name} path is invalid")
        try:
            info = path.stat(follow_symlinks=False)
        except OSError as error:
            raise CredentialError(f"{item.name} file is unavailable") from error
        if not path.is_file() or path.is_symlink():
            raise CredentialError(f"{item.name} must be a regular non-symlink file")
        if info.st_mode & 0o077:
            raise CredentialError(f"{item.name} must have mode 0600 or stricter")
        if not os.access(path, os.R_OK):
            raise CredentialError(f"{item.name} is not readable by the service")
        results[item.name] = True
    return CredentialStatus(**results)


def read_secret(path: Path, *, maximum_bytes: int = 65536) -> bytes:
    """Read a bounded secret only for an approved adapter; never stringify it."""
    if maximum_bytes <= 0:
        raise ValueError("maximum_bytes must be positive")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if info.st_mode & 0o077:
            raise CredentialError("credential file mode is too permissive")
        value = os.read(descriptor, maximum_bytes + 1)
        if len(value) > maximum_bytes:
            raise CredentialError("credential file exceeds maximum size")
        return value.rstrip(b"\r\n")
    finally:
        os.close(descriptor)
