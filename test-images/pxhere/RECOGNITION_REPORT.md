# DropZone PxHere recognition smoke test

Tested on the Android 35 emulator on 2026-11-07 with the 50 photos listed in
`manifest.csv`.

## Result summary

- 50/50 photos completed the WorkManager recognition task.
- 43/50 photos produced at least one image label at >= 60% confidence.
- 7/50 photos produced non-empty OCR text. Most of this sample contains no
  visible text, so an empty OCR result is expected for many images.
- OCR text and image labels were both verified through DropZone search.

## Spot checks

- `05_OCR-Vision_1697078.jpg`: detected `GITEX`, `REGISTRATION`,
  `PLATINUM SPONSOR`, `GOLD SPONSORS`, and other event signage.
- `48_Vision_1697408.jpg`: `Sky 82%`, `Church 79%`, `Building 77%`,
  `Tower 68%`, `Cathedral 64%`.
- `50_Vision_1697350.jpg`: `Sky 96%`, `Skyline 81%`, `Lightning 71%`,
  `Skyscraper 67%`.
- `46_Vision_1697300.jpg`: `Bird 98%`, `Wing 66%`.
- `38_Vision_1697624.jpg`: `Tableware 90%`, `Cup 89%`, `Saucer 67%`.

## Interpretation

This is a functional smoke test, not a formal accuracy benchmark: the PxHere
sample has source descriptions but no OCR transcription or fixed class
ground-truth. The generic image model returns English labels and can miss
domain-specific concepts. OCR can also produce short false positives on images
without deliberate text. The confidence threshold is currently 60% so the UI
does not get flooded with weak guesses.
