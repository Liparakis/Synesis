# Protocol Test Vectors

All values are hexadecimal and big-endian. The session UUID is
`00112233-4455-6677-8899-aabbccddeeff`.

## SL-007 heartbeat payloads

| Message | Payload bytes |
|---|---|
| HEARTBEAT, sequence 7, related 6, marker `0102030405060708` | `0100112233445566778899aabbccddeeff000000000000000700000000000000060102030405060708` |
| HEARTBEAT_ACK, sequence 7, related 9, marker `0102030405060708` | `0100112233445566778899aabbccddeeff000000000000000700000000000000090102030405060708` |
| minimum HEARTBEAT, sequence 0, related `-1`, marker 0 | `0100112233445566778899aabbccddeeff0000000000000000ffffffffffffffff0000000000000000` |
| unsupported payload version | replace the first byte of the minimum vector with `02` |
| wrong-session binding | replace any UUID byte in a valid vector; transport rejects it before refresh |

The maximum sequence is the non-negative `long` maximum; the next local send
is a bounded terminal sequence-exhaustion failure. Existing handshake and
SL-006 vectors are unchanged.
