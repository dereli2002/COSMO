These synthetic Turkish audio fixtures exercise the native Vosk decoder, including its
acoustic model and pronunciation lexicon. They contain no recordings of a person.

The short phrases were synthesized with eSpeak NG 1.52.0.1 from the official
`piper-tts` 1.8.0 PyPI wheel. Voices are `tr`, `tr+m2`, and `tr+f3`; speeds and
SHA-256 values are recorded in `manifest.json`. Audio is 16 kHz mono signed
16-bit PCM WAV. The engine software is not distributed in the app or these fixtures.

The English model pronounces its existing word `ferry` as F EH R IY, matching the
Turkish spoken name Feri. `perry` is a competing pronunciation to reduce accidental
wake-ups on “peri”. A detected wake only opens a separate Turkish command window.

Run `python3 scripts/check-wake-phrase.py --vosk-lib /path/to/linux/libvosk.so`.
A smoke check requires at least 8 of the 9 synthetic wake samples and rejects all
ordinary speech fixtures. Similar names are measured separately and may still wake
the assistant. These fixtures do not establish accuracy with a real speaker,
noise, a phone microphone, or screen-off/background behavior.

To reproduce the synthetic source, install `piper-tts==1.8.0` and `scipy` in an
isolated temporary Python environment. Piper's `piper/espeakbridge.so` exports the
public eSpeak synthesis API; no Piper voice model, network TTS, or API key is used.
For each manifest case, initialize `espeak_Initialize(2, 0, <piper-package-dir>, 0)`
(synchronous retrieval), select the listed voice with `espeak_SetVoiceByName`, set
`espeak_SetParameter(1, words_per_minute, 0)`, and synthesize the UTF-8 `text` with
`espeak_Synth(..., flags=1)` into its signed 16-bit callback. Concatenate the callback
samples, convert to floating point, run `scipy.signal.resample_poly(samples, 16000,
22050)`, and convert back to signed 16-bit. Add 8000 zero samples before the speech
and 18000 zero samples after it. Save mono PCM WAV at 16000 Hz. eSpeak may vary
noise excitation across synthesis sequences, so regenerated audio must get fresh
SHA-256 values and must pass the native check before replacing these fixtures.
