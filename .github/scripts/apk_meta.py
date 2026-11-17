#!/usr/bin/env python3
"""Parse an APK's trailing bytes (ZIP end-of-central-directory, central directory and APK Signing
Block) from a ranged tail read, without the whole file.

Emits JSON: central-directory entries (name, local-header offset, stored size, method), the signing
block's absolute [start,size), and the SHA-256 digests of the v3/v2 signer certificate and the Play
source-stamp certificate. stdlib only (struct, hashlib, json).

Usage: apk_meta.py <tailfile> <tailStart> <total>
  tailfile  bytes [tailStart, total) of the APK, as fetched by a Range request
  tailStart absolute offset in the APK where tailfile begins
  total     full APK size
"""
import hashlib
import json
import struct
import sys

APK_SIG_MAGIC = b"APK Sig Block 42"
ID_V2 = 0x7109871A
ID_V3 = 0xF05368C0
ID_V31 = 0x1B93AD61
ID_STAMP_V1 = 0x2B09189E
ID_STAMP_V2 = 0x6DFF800D


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def lp_u32(b, o):
    """length-prefixed (uint32) blob at o -> (payload bytes, offset past it)."""
    n = u32(b, o)
    return b[o + 4 : o + 4 + n], o + 4 + n


def lp_seq(b):
    """iterate a length-prefixed sequence of length-prefixed elements."""
    o = 0
    while o < len(b):
        el, o = lp_u32(b, o)
        yield el


def first_cert_sha256(signed_data_or_block, scheme):
    # Walk a v2/v3 signer's signed-data to its certificate sequence; return the first cert's DER sha256.
    # signed_data layout: digests(seq), certificates(seq), [v3 minSdk,maxSdk], attributes(seq).
    # We only need the certificates sequence: digests first, then certs.
    o = 0
    _digests, o = lp_u32(signed_data_or_block, o)
    certs_blob, o = lp_u32(signed_data_or_block, o)
    for cert in lp_seq(certs_blob):
        return hashlib.sha256(cert).hexdigest()
    return None


def signer_cert_from_block(value, scheme):
    # block value = length-prefixed sequence of signers; strip that outer length, then each signer =
    # signed_data(lp) + ... ; take the first signer's first certificate.
    signers_blob, _ = lp_u32(value, 0)
    for signer in lp_seq(signers_blob):
        signed_data, _ = lp_u32(signer, 0)
        d = first_cert_sha256(signed_data, scheme)
        if d:
            return d
    return None


def stamp_cert_from_block(value, scheme):
    # v1 source-stamp (0x2b09189e): value = length-prefixed stamp certificate.
    # v2 source-stamp (0x6dff800d): value = length-prefixed signed-data whose first element is the
    # length-prefixed stamp certificate.
    cert, _ = lp_u32(value, 0)
    if scheme == 2:
        cert, _ = lp_u32(cert, 0)
    return hashlib.sha256(cert).hexdigest()


def main():
    tailfile, tail_start, total = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    with open(tailfile, "rb") as f:
        tail = f.read()

    def at(abs_off):
        return abs_off - tail_start

    # Locate EOCD by scanning back for its signature (0x06054b50); no ZIP64 expected for these APKs.
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("no EOCD found in tail (fetch a larger tail)")
    cd_size = u32(tail, eocd + 12)
    cd_offset = u32(tail, eocd + 16)
    if at(cd_offset) < 0:
        raise SystemExit("central directory precedes the fetched tail (fetch a larger tail)")

    # Parse central-directory entries.
    o = at(cd_offset)
    entries = []
    while o + 4 <= len(tail) and tail[o : o + 4] == b"PK\x01\x02":
        method = u16(tail, o + 10)
        csize = u32(tail, o + 20)
        usize = u32(tail, o + 24)
        nlen = u16(tail, o + 28)
        elen = u16(tail, o + 30)
        clen = u16(tail, o + 32)
        lho = u32(tail, o + 42)
        name = tail[o + 46 : o + 46 + nlen].decode("utf-8", "replace")
        entries.append({"name": name, "lho": lho, "csize": csize, "usize": usize, "method": method})
        o += 46 + nlen + elen + clen

    # APK Signing Block sits immediately before the central directory.
    sig = {"present": False}
    certs = {"signer": None, "stamp": None}
    magic_pos = at(cd_offset) - 16
    if magic_pos >= 0 and tail[magic_pos : magic_pos + 16] == APK_SIG_MAGIC:
        block_size = u64(tail, at(cd_offset) - 24)
        block_start = cd_offset - 8 - block_size  # absolute
        if at(block_start) >= 0:
            sig = {"present": True, "start": block_start, "size": block_size + 8}
            # pairs live between (block_start + 8) and (cd_offset - 24)
            p = at(block_start) + 8
            end = at(cd_offset) - 24
            while p + 12 <= end:
                pair_len = u64(tail, p)
                pid = u32(tail, p + 8)
                value = tail[p + 12 : p + 8 + pair_len]
                if pid in (ID_V3, ID_V31) and certs["signer"] is None:
                    certs["signer"] = signer_cert_from_block(value, 3)
                elif pid == ID_V2 and certs["signer"] is None:
                    certs["signer"] = signer_cert_from_block(value, 2)
                elif pid in (ID_STAMP_V2, ID_STAMP_V1) and certs["stamp"] is None:
                    certs["stamp"] = stamp_cert_from_block(value, 2 if pid == ID_STAMP_V2 else 1)
                p += 8 + pair_len

    print(json.dumps({
        "total": total,
        "cd_offset": cd_offset,
        "cd_size": cd_size,
        "entries": entries,
        "sig": sig,
        "certs": certs,
    }))


if __name__ == "__main__":
    main()
