"""Message parsing exceptions."""


class MessageDecodeError(Exception):
    """Raised when a message cannot be decoded."""


class MessageParseError(Exception):
    """Raised when a decoded message cannot be parsed."""


class UnsupportedMessageError(Exception):
    """Raised when a parsed message type is not supported."""
