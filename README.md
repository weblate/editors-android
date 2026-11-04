# editors-android

Native document, spreadsheet, presentation and PDF editors for the Euro-Office Android app.

Build and architecture: see [AGENTS.md](AGENTS.md) and [editors-engine](https://github.com/Euro-Office/editors-engine), the engine it shares with the iOS app. The app is built from [MobileEditors](https://github.com/Euro-Office/MobileEditors).

## Translations

The editors' texts are translated on [Weblate](https://hosted.weblate.org/projects/euro-office/). The English source is `libresources/src/main/res/values/strings.xml`; Weblate writes the translations to `values-<language>/strings.xml` in the same folder. These texts include:
- the editors' own texts
- the texts sdkjs puts into documents (`editors_sdk_*`)
- the names of the built-in styles (`editors_style_*`)
- the names of chart types and shapes in the insert pickers (generated, see below)

Settings of the Weblate component:

| Setting | Value |
|---|---|
| File format | Android String Resource |
| File mask | `libresources/src/main/res/values-*/strings.xml` |
| Monolingual base language file | `libresources/src/main/res/values/strings.xml` |
| Template for new translations | `libresources/src/main/res/values/strings.xml` |
| Language code style | Android style (`pt-rBR`) |

Change only the English file by hand; Weblate owns the others.

## Pictures of chart types and shapes

The insert pickers show web-apps' pictures of chart types and shapes. `tools/web-apps-icons.py` generates them, together with their English names and the pickers' list (`InsertCatalog.kt`), from a web-apps and an sdkjs checkout:

```sh
tools/web-apps-icons.py <web-apps> <sdkjs>
```

The pictures, the names and the menu and format icons it takes are Ascensio System SIA's, under the [Creative Commons Attribution-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-sa/4.0/) license; the editors' licence page says so. `InsertCatalog.kt` notes the web-apps and sdkjs commits they come from.

## Tests

Unit tests live in each module's `src/test` and run on the JVM against the engine stub, so they need neither core nor the NDK. From a MobileEditors checkout (or with `documents-app-android` next to this repository):

```sh
cd documents-app-android/app_manager
./gradlew :libeditors:createDebugUnitTestCoverageReport :libgeditors:createDebugUnitTestCoverageReport
```

The JaCoCo reports go to `<module>/build/reports/coverage/test/debug/`. On every pull request, `.github/workflows/unit-tests.yml` runs them and fails when less than 80% of the changed lines are covered; the editor screens (`ui/activities`) are left out, they are tested on an emulator in MobileEditors (`build/tests/ui`). It builds with the documents-app-android branch of the same name when there is one, else `main`, so a change across both repositories uses one branch name in each. Feat and fix pull requests must also change a test (`.github/workflows/require-tests.yml`), or carry the `no-tests-needed` label with a reason.

## Licences

The code is under the AGPL-3.0 ([LICENSE](LICENSE)), but not all icons and pictures are:

- Icons, pictures and names from web-apps (including the SmartArt pictures that `tools/smartart-previews.py` renders) are under CC BY-SA 4.0, and so are our changes to them.
- Material Design icons are under the Apache License 2.0.

[REUSE.toml](REUSE.toml) gives the licence of each file, with the texts in [LICENSES](LICENSES). Check it with `reuse lint` (e.g. `docker run --rm -v $PWD:/data fsfe/reuse lint`); CI runs it too.
