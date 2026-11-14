# ParkFlow 0.9 visual alignment

ParkFlow uses native Android Views styled from the public Pleos Connect design guidance. These are application-owned adaptations, not SDK-provided Pleos UI widgets, a Figma import or a claim of complete specification compliance. The ParkFlow name and original monochrome parking-P launcher remain distinct from Pleos branding. The functional notification icon is unchanged.

## Sources and interpretation

Primary guidance: [Introduction](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro), [Colors](https://document.pleos.ai/en/docs/connect/guide/docs-design/foundations/colors), [Typography](https://document.pleos.ai/en/docs/connect/guide/docs-design/foundations/typography), [Basic button](https://document.pleos.ai/en/docs/connect/guide/docs-design/components/actions/basic-button), [Text field](https://document.pleos.ai/en/docs/connect/guide/docs-design/components/fields/text-field), [Segmented menu](https://document.pleos.ai/en/docs/connect/guide/docs-design/components/segmented-menu). [Official Figma asset](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig) is linked for reference only; no figure images are shipped in the app.

The public grayscale chart was used to select the hex primitives. Some semantic illustrations have inconsistent token labels, so the table below states the actual app mapping rather than inferring a formal semantic token contract.

| App role | Day | Night |
| --- | --- | --- |
| Canvas | #F7F8FA | #1D1E21 |
| Card/basic button surface | #FFFFFF | #313236 |
| Primary text | #131417 | #FFFFFF |
| Supporting text | #44464E | #E0E1E6 |
| Metadata | #686A72 | #C7C9CE |
| Border | #C7C9CE | #686A72 |
| Filled action | #44464E / white | #44464E / white |

The guide's button hierarchy image calls the pale Basic variant “Primary” and the gray Filled variant “Secondary.” ParkFlow's existing business-primary boolean maps to Filled for emphasis. It does not mean the guide names a dark filled button “Primary.” Other actions use the neutral Basic-style surface with an app-specific outline. Selected duration uses the same Filled treatment within one surrounding segmented container; its accessibility label also states selection. Unselected durations retain their labels and shared outline. Informational stages are plain neutral labels rather than colored pills.

Page titles are 40sp; lot/card titles 32sp; fee and location-record highlights 48sp; body 26sp (30/28sp emphasis in existing explanatory areas); supporting text 24sp; metadata 20sp. Button labels use 24sp, compact navigation labels 20sp. Bold text uses weight 700. Density-independent sizes and scalable text respect Android configuration; no forced DPI or font-scale changes. Buttons grow with wrapped labels instead of clipping to a fixed height. Main actions target 80dp minimum height and header actions 64dp; 24dp button and 16dp card/field radii are ParkFlow choices, not verified official mandates. Narrow duration labels use 20sp for legibility within four equal columns.

State-list drawables, native ripple feedback, enabled/disabled label colors, keyboard-focus outlines and focused field borders provide control feedback without shadows or gradients. Theme resources switch platform dialogs, editors, background and system-bar appearance with night mode. QR remains black/white with its original white quiet zone and integer-pixel enlargement. Field defaults use the canvas, and focus uses the surface plus a thicker neutral border; this is a native adaptation of the field guidance.

At >=850dp floor/zone/pillar share one row; memo remains below. Narrow layouts stack the same fields. IDs 2000–2003, label associations, ordering, text/cursor restoration, saved drafts, discard guard and persistence behavior remain intact. The root ScrollView has a stable ID for native scroll-state restoration across configuration changes. Timers still update labels without rebuilding the page each second.

## Font provenance and license

Unmodified variable font from [google/fonts ofl/astasans](https://github.com/google/fonts/tree/main/ofl/astasans), downloaded before implementation from the official repository:

- Font source: `https://raw.githubusercontent.com/google/fonts/main/ofl/astasans/AstaSans%5Bwght%5D.ttf`
- SHA-256: `f68410bf7d04deb992097758640145c72ddcf234070794237fc4994e18991c33`
- Bundled at `app/src/main/res/font/asta_sans.ttf`; the loaded Typeface is cached and shared by native text, buttons and fields.
- Copyright 2024 The Asta Sans Project Authors (https://github.com/42dot/Asta-Sans).
- SIL Open Font License, Version 1.1. Full original copyright and license text is bundled at `app/src/main/assets/licenses/AstaSans-OFL.txt`.
- License source: `https://raw.githubusercontent.com/google/fonts/main/ofl/astasans/OFL.txt`.
- Visible full license: **설정 → 자료 안내 → Asta Sans 라이선스 보기**. The file is copied unchanged; no renamed or modified font derivative was made. Android handles fallback for glyphs outside its coverage.

## Submission scope

Version 0.11 preserves the 0.9 visual design and uses the signed release build for contest submission. Lot display names use a parking-lot suffix where necessary, with the road address below the title. Runtime/build results for the submission are in [validation.md](validation.md).
