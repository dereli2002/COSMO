#!/usr/bin/env python3
"""Exercise the actual offline FERİ acoustic decoder, without network access.

Requires a trusted Linux libvosk.so matching the execution architecture, for example
from the official Vosk Python wheel. Fixtures and model archives are SHA-256 checked.
"""

import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import wave
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODEL_NAME = "vosk-model-small-en-us-0.15"
MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def configure_library(path):
    library = ctypes.CDLL(str(path))
    library.vosk_set_log_level(-1)
    library.vosk_model_new.argtypes = [ctypes.c_char_p]
    library.vosk_model_new.restype = ctypes.c_void_p
    library.vosk_model_find_word.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    library.vosk_model_find_word.restype = ctypes.c_int
    library.vosk_model_free.argtypes = [ctypes.c_void_p]
    library.vosk_recognizer_new_grm.argtypes = [ctypes.c_void_p, ctypes.c_float, ctypes.c_char_p]
    library.vosk_recognizer_new_grm.restype = ctypes.c_void_p
    library.vosk_recognizer_set_words.argtypes = [ctypes.c_void_p, ctypes.c_int]
    library.vosk_recognizer_accept_waveform.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    for name in ("vosk_recognizer_result", "vosk_recognizer_final_result"):
        getattr(library, name).argtypes = [ctypes.c_void_p]
        getattr(library, name).restype = ctypes.c_char_p
    library.vosk_recognizer_free.argtypes = [ctypes.c_void_p]
    return library


def transcribe(library, model, path, grammar):
    with wave.open(str(path), "rb") as stream:
        if (stream.getnchannels(), stream.getsampwidth(), stream.getframerate()) != (1, 2, 16000):
            raise ValueError(f"Invalid PCM audio format: {path.name}")
        pcm = stream.readframes(stream.getnframes())
    recognizer = library.vosk_recognizer_new_grm(model, 16000.0, json.dumps(grammar).encode())
    if not recognizer:
        raise RuntimeError("Vosk could not create the wake recognizer")
    results = []
    try:
        library.vosk_recognizer_set_words(recognizer, 1)
        # Match the Android loop: 320 signed 16-bit samples (20 ms).
        for offset in range(0, len(pcm), 640):
            chunk = pcm[offset:offset + 640]
            if library.vosk_recognizer_accept_waveform(recognizer, chunk, len(chunk)):
                results.append(json.loads(library.vosk_recognizer_result(recognizer)))
        results.append(json.loads(library.vosk_recognizer_final_result(recognizer)))
    finally:
        library.vosk_recognizer_free(recognizer)
    return results


def is_wake(results, confidence):
    for result in results:
        words = result.get("result", [])
        if (result.get("text") == "ferry" and len(words) == 1
                and words[0].get("word") == "ferry"
                and words[0].get("conf", 0) >= confidence):
            return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vosk-lib", type=Path, required=True, help="Trusted Linux libvosk.so")
    parser.add_argument("--model-zip", type=Path,
                        default=ROOT / "app/src/main/assets/models" / (MODEL_NAME + ".zip"))
    parser.add_argument("--fixtures", type=Path, default=ROOT / "app/src/test/resources/wake")
    parser.add_argument("--json-output", type=Path, help="Optional detailed local result file")
    args = parser.parse_args()
    manifest = json.loads((args.fixtures / "manifest.json").read_text(encoding="utf-8"))
    if sha256(args.model_zip) != MODEL_SHA256:
        raise ValueError("Wake model archive SHA-256 does not match the official pinned archive")
    library = configure_library(args.vosk_lib.resolve())
    report = []
    with tempfile.TemporaryDirectory(prefix="feri-wake-validation-") as temporary:
        with zipfile.ZipFile(args.model_zip) as archive:
            size = 0
            for entry in archive.infolist():
                relative = Path(entry.filename)
                size += entry.file_size
                if (relative.is_absolute() or ".." in relative.parts
                        or not relative.parts or relative.parts[0] != MODEL_NAME
                        or "\\" in entry.filename or ":" in entry.filename
                        or size > 250 * 1024 * 1024):
                    raise ValueError("Unexpected model archive entry")
            archive.extractall(temporary)
        model = library.vosk_model_new(str(Path(temporary) / MODEL_NAME).encode())
        if not model:
            raise RuntimeError("Vosk could not open the pinned wake model")
        try:
            for word in manifest["grammar"]:
                if word != "[unk]" and library.vosk_model_find_word(model, word.encode()) < 0:
                    raise ValueError(f"Wake grammar contains a word absent from the model: {word}")
            for case in manifest["cases"]:
                path = args.fixtures / case["file"]
                if sha256(path) != case["sha256"]:
                    raise ValueError(f"Audio fixture SHA-256 mismatch: {path.name}")
                results = transcribe(library, model, path, manifest["grammar"])
                report.append({"file": case["file"], "text": case["text"], "group": case["group"],
                               "wake": is_wake(results, manifest["minimum_confidence"]), "results": results})
        finally:
            library.vosk_model_free(model)
    counts = {}
    for group in ("wake", "ordinary", "confusable"):
        cases = [case for case in report if case["group"] == group]
        counts[group] = {"detected": sum(case["wake"] for case in cases), "total": len(cases)}
    print("Native FERİ wake benchmark:")
    print(f"  Wake samples: {counts['wake']['detected']}/{counts['wake']['total']}")
    print(f"  Ordinary speech false wakes: {counts['ordinary']['detected']}/{counts['ordinary']['total']}")
    print(f"  Similar names that also wake: {counts['confusable']['detected']}/{counts['confusable']['total']}")
    print("Synthetic audio only; real-phone accuracy and background behavior remain separate checks.")
    if args.json_output:
        args.json_output.write_text(json.dumps({"counts": counts, "cases": report}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if counts["wake"]["detected"] < manifest["minimum_wake_hits"] or counts["ordinary"]["detected"]:
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Wake validation failed: {error}", file=sys.stderr)
        sys.exit(1)
