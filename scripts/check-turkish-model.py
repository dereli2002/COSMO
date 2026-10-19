#!/usr/bin/env python3
"""Check the real official Turkish ZIP, optionally load it with host libvosk.

Usage:
  python3 scripts/check-turkish-model.py /tmp/vosk-model-small-tr-0.3.zip
  python3 scripts/check-turkish-model.py /tmp/vosk-model-small-tr-0.3.zip \
      --library /tmp/vosk/libvosk.so

The model digest pins bytes obtained over verified HTTPS from:
https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip

For the Linux smoke check we used the official PyPI vosk 0.3.45 manylinux
x86_64 wheel, verified against PyPI's SHA-256 metadata before extraction:
25e025093c4399d7278f543568ed8cc5460ac3a4bf48c23673ace1e25d26619f
This is host validation, not an Android 0.3.75 or physical microphone test.
No downloads, global installs, audio recordings, or repository caches are made.
"""

import argparse
import ctypes
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
import zipfile


MODEL_NAME = "vosk-model-small-tr-0.3"
MODEL_SHA256 = "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5"
REQUIRED_FILES = (
    "final.mdl", "mfcc.conf", "HCLr.fst", "Gr.fst", "disambig_tid.int",
    "word_boundary.int", "ivector/final.ie", "ivector/final.mat",
    "ivector/final.dubm", "ivector/global_cmvn.stats", "ivector/splice.conf",
    "ivector/online_cmvn.conf",
)


def validate_archive(path):
    """Verify the exact supported artifact and its legacy Vosk layout."""
    if path.stat().st_size > 90 * 1024 * 1024:
        raise ValueError("Model archive exceeds the 90 MiB size limit")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != MODEL_SHA256:
        raise ValueError("Official model SHA-256 mismatch")

    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if not entries or len(entries) > 1000:
            raise ValueError("Unexpected ZIP entry count")
        names = set()
        total = 0
        for entry in entries:
            name = entry.filename
            parts = PurePosixPath(name).parts
            mode = entry.external_attr >> 16
            if (not parts or parts[0] != MODEL_NAME or ".." in parts
                    or "\\" in name or ":" in name or name in names
                    or stat.S_ISLNK(mode)):
                raise ValueError(f"Unsafe or duplicate ZIP path: {name}")
            names.add(name)
            total += entry.file_size
            if total > 250 * 1024 * 1024:
                raise ValueError("Expanded model exceeds the 250 MiB limit")
        for relative in REQUIRED_FILES:
            entry = archive.getinfo(f"{MODEL_NAME}/{relative}")
            if entry.is_dir() or entry.file_size <= 0:
                raise ValueError(f"Missing or empty model file: {relative}")
        if archive.testzip() is not None:
            raise ValueError("ZIP CRC verification failed")
    print(f"Official model SHA-256 and all ZIP CRCs: OK ({digest})")
    print("Legacy Vosk model layout and all i-vector files: OK")


def validate_native(archive_path, library_path):
    """Actually load and exercise the official model using a host native library."""
    library = ctypes.CDLL(str(library_path.resolve()))
    signatures = (
        ("vosk_model_new", [ctypes.c_char_p], ctypes.c_void_p),
        ("vosk_model_free", [ctypes.c_void_p], None),
        ("vosk_model_find_word", [ctypes.c_void_p, ctypes.c_char_p], ctypes.c_int),
        ("vosk_recognizer_new", [ctypes.c_void_p, ctypes.c_float], ctypes.c_void_p),
        ("vosk_recognizer_free", [ctypes.c_void_p], None),
        ("vosk_recognizer_accept_waveform",
         [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int], ctypes.c_int),
        ("vosk_recognizer_final_result", [ctypes.c_void_p], ctypes.c_char_p),
    )
    for name, args, result in signatures:
        function = getattr(library, name)
        function.argtypes = args
        function.restype = result

    with tempfile.TemporaryDirectory(prefix="cosmo-model-check-") as directory:
        # Extraction follows complete SHA-256, path, size, and ZIP CRC checks.
        with zipfile.ZipFile(archive_path) as archive:
            archive.extractall(directory)
        model_path = Path(directory) / MODEL_NAME
        model = library.vosk_model_new(str(model_path).encode())
        if not model:
            raise RuntimeError("Native vosk_model_new failed")
        try:
            print("Native model load: OK")
            vocabulary = {
                word: library.vosk_model_find_word(model, word.encode())
                for word in ("COSMO", "cosmo", "kosmo", "kozmo", "kozmos", "asistan")
            }
            print("Vocabulary IDs (-1 means absent): " + json.dumps(vocabulary))
            recognizer = library.vosk_recognizer_new(model, 16000.0)
            if not recognizer:
                raise RuntimeError("Native 16 kHz recognizer creation failed")
            try:
                silence = bytes(32000)  # One second, mono 16 kHz PCM16; no recording.
                status = library.vosk_recognizer_accept_waveform(
                    recognizer, silence, len(silence))
                if status < 0:
                    raise RuntimeError("Native recognizer rejected PCM16 silence")
                result = json.loads(library.vosk_recognizer_final_result(recognizer))
                if result.get("text") != "":
                    raise RuntimeError("Unexpected recognition result for silence")
                print("16 kHz recognizer and one second PCM16 silence: OK")
            finally:
                library.vosk_recognizer_free(recognizer)
        finally:
            library.vosk_model_free(model)
    print("Host native smoke test only; Android and physical audio remain separate checks.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="Official Turkish Vosk ZIP")
    parser.add_argument("--library", type=Path, help="Trusted host libvosk.so for native smoke test")
    args = parser.parse_args()
    try:
        validate_archive(args.archive)
        if args.library:
            validate_native(args.archive, args.library)
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Model check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
