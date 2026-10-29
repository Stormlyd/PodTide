# Resource provenance

- PodBridge bridge/headphone mark and replacement earbud/case vectors: created for this project on 2026-10-26; distributed with the project under GPL-3.0.
- Upstream CAPod branded raster/device art and translated resource directories are removed from the independent distribution. Framework/Material-style functional XML resources remain subject to their upstream applicable terms.
- No LibrePods logo, Apple proprietary font, video or promotional image is imported.
- CAPod and LibrePods source copyrights and license attribution are retained; see LICENSE and THIRD_PARTY_NOTICES.md.

- Simplified Chinese resources (`values-zh-rCN`, main/FOSS): independently written for PodBridge on 2026-10-26 from the English string catalog and feature behavior, under GPL-3.0. They were not copied from excluded upstream translations.

## Popup 3D hero, 2026-10-26

Project asset: `app/src/main/res/drawable-nodpi/popup_pro_hero.png`. Native Chinese production-Compose previews are delivered separately as `PodBridge-popup-zh-light.png`, `PodBridge-popup-zh-dark.png` and `PodBridge-popup-zh-large-font.png`; these are renderer outputs, not phone screenshots.

New project-specific illustration generated with the built-in `image_gen.imagegen` tool, following the user's approved light, refined 3D popup style. No input/reference image was supplied. The transparent output is RGBA,1536x1024,1,348,758bytes; alpha range0..254; SHA256 `7fbc30bc7b4f053de74937d26ad1c37c83322088f12bda8267fb3092e6301ac1`. It is included as a new popup drawable without replacing the existing per-model vectors. The project distributes this illustration with its GPL-3.0 assets; no Apple marketing artwork, Apple logo or proprietary font was imported.

Task21 (2026-10-27) reuses this same unchanged asset in the native top card for supported Pro models at56dp; other model resources remain model-specific. Separate actual Compose renders are `PodBridge-top-popup-zh-light.png`, `PodBridge-top-popup-zh-dark.png` and `PodBridge-top-popup-zh-large-font.png`. No additional raster generation or upstream video/marketing asset import was used. These are source-rendered previews, not phone screenshots.

Generation prompt:

> Use case: stylized-concept. Asset type: production transparent PNG hero artwork for a compact Android AirPods companion connection popup, called PodBridge. Generate a brand new original polished 3D illustration. Subject: exactly two white in-ear wireless earbuds with silicone tips and short stems floating just above a matching OPEN white horizontal rounded charging case. Recognizable functional AirPods Pro 3-like consumer earbuds proportions, but original illustration, no Apple logo, no typography or brand. Case in centered three-quarter front view, lid opens backward, two empty correct-size earbud wells visible; a tiny soft green front status LED. Earbuds float asymmetrically but balanced left and right above the case, each separated with clear silhouette. Fine light-gray vent detail and restrained polished silver accents. Refined light, airy premium physical product render, soft studio lighting, porcelain-white plastic, subtle realistic ambient occlusion and rounded bevels, gently cool neutral highlights. Composition centered and wide landscape, object group occupies ~85 percent width and 85 percent height, generous alpha margin so no item is cut off. Truly transparent background, no opaque backdrop, no checkerboard, no text, no labels, no rings, no UI/card. No environment or additional objects. Lighting must remain legible on both pale gray and dark charcoal UI. Faint tight contact shadow only, no broad dark shadow. Render at high detail suitable for a 360dp app card hero displayed 200 by 160dp.

## PodTide layered popup assets, 2026-10-29

`popup_pro_case.png` and `popup_pro_earbud.png` are newly generated original transparent illustrations made for this project using the built-in image generation tool, based on the project's own earlier popup hero. They are distributed under the project GPL-3.0 asset terms. They are layered and animated in the popup; no Apple marketing images or branding were imported.

The adaptive launcher foreground and monochrome vector are independently drawn abstract line art for PodTide. No Apple logo or official artwork is included.

## PodTide crossed-earbud tide mark, 2026-10-29

The updated adaptive launcher foreground and monochrome icon are project-original editable vector line art, combining two angled earbuds with a pair of tide-like sound waves. They are distributed with the project under GPL-3.0.
