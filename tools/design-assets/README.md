# Design assets

Generates the fonts and icons bundled with the client ([ADR-0016](../../docs/adr/0016-visual-design-system.md),
[visual style](../../docs/design/visual-style.md)). Outputs are committed; the app build does not run this script.

| Output | Source | License |
| --- | --- | --- |
| `client/shared/src/commonMain/composeResources/font/manrope_{400,500,600,700,800}.ttf` | Manrope variable font, [google/fonts](https://github.com/google/fonts/tree/e7db6ca603fd4862010383ff17ffd6cc4ffac1c0/ofl/manrope) `ofl/manrope/Manrope[wght].ttf` at commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0` | SIL OFL 1.1 |
| `client/shared/src/commonMain/composeResources/drawable/ic_<name>.xml`, `ic_<name>_filled.xml` | Material Symbols Outlined variable font, [google/material-design-icons](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/variablefont) `variablefont/MaterialSymbolsOutlined[FILL,GRAD,opsz,wght].ttf` at commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7` | Apache 2.0 |

Icons are instantiated at `wght=250, opsz=24, GRAD=0` with `FILL 0` (outlined) and `FILL 1` (`_filled`, selected
navigation item), converted to 24 dp vector drawables (viewport 960, black fill so `Icon` tints them).

## Run

```sh
python3 -m venv .venv && .venv/bin/pip install fonttools
.venv/bin/python build.py
```

Downloads are cached in `.cache/` (ignored by git). The script overwrites all outputs.

## Add an icon

1. Find the name on [Material Symbols](https://fonts.google.com/icons) (e.g. `photo_camera`).
2. Add it to `icons.txt` (and to the list in visual-style.md § Icons).
3. Run the script, commit the two new XML files. In Kotlin: `painterResource(Res.drawable.ic_photo_camera)`.
4. `IconResourcesTest` in `client/shared` checks that every name in `icons.txt` has both drawables.
