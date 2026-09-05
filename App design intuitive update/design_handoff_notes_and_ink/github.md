repo: batunii/YANTRA
branch: main

## Last sync

date: 2026-09-01T00:20:01Z

### Updated in this project

- Rebuilt the task page as a document of blocks (prose, heading, bullet, task, ink, image) to match PageDoc.
- Added the ink-block screen and the block insert menu.
- Documented the in-progress treatment as five consistent signals across screens.
- Glyph corrected to three states: bhupura, drawn ring, plain circle.

## Screen map

| Project screen | Repo files |
|---|---|
| 01 First run | ui/onboarding, data/seed/WorkspaceSeeder.kt |
| 02 The day (Today, Loose, capture) | ui/home/HomeScreen.kt, ui/smart/SmartListScreen.kt, data/capture/CaptureParse.kt |
| 03 One task (3 run states) | data/format/PageDoc.kt, data/repo/FocusRepository.kt |
| 04 Focus + ledger | ui/focus/YantraFocus.kt, ui/components/YantraGlyph.kt |
| 05 Widgets | ui/components/YantraGlyph.kt |
| 06 Tablet two-pane ink | data/ink/StrokeCodec.kt, data/ink/ShapeRecognizer.kt, data/repo/InkRepository.kt |
| 07 Task page blocks | data/format/PageDoc.kt, data/format/PageCodec.kt, data/workspace/PageMapper.kt |
| 08 In progress end to end | data/format/PageDoc.kt (TaskStatus), data/repo/FocusRepository.kt |
| Shared tokens (all) | ui/theme/YantraColors.kt, ui/theme/Theme.kt, data/label/LabelPalette.kt |

## Sync history

- 2026-08-26 — first read: colour law, glyph path, type ramp; three phone directions.
