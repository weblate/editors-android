// Engine bridge: JNI binding to the C++ engine (V8 + sdkjs + core rendering)
plugins {
    id("com.android.library")
    kotlin("android")
}

// The native core (libx2t and the engine) is only included when the app is built
// with -PwithNativeCore=true; otherwise stubs report the engine as unavailable
val hasNativeCore = findProject(":libx2t") != null

// MobileEditors checkout: the engine (editors-engine), core sources, the core-android libraries
// and the sdkjs bundles
val mobileEditorsDir = rootDir.resolve("../..")
val coreLibsDir = providers.gradleProperty("PATH_LIB_BUILD_TOOLS")
    .getOrElse(mobileEditorsDir.resolve("build/out/core-android").path)
val sdkjsDir = providers.gradleProperty("SDKJS_DIR")
    .getOrElse(mobileEditorsDir.resolve("build/out/sdkjs").path)
val coreFontsDir = providers.gradleProperty("CORE_FONTS_DIR")
    .getOrElse(mobileEditorsDir.resolve("core-fonts").path)
val dictionariesDir = providers.gradleProperty("DICTIONARIES_DIR")
    .getOrElse(mobileEditorsDir.resolve("build/out/dictionaries").path)
val documentTemplatesDir = providers.gradleProperty("DOCUMENT_TEMPLATES_DIR")
    .getOrElse(mobileEditorsDir.resolve("document-templates").path)
val engineAssetsDir = layout.buildDirectory.dir("generated/engineAssets")

android {
    namespace = "lib.editors.base"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        if (hasNativeCore) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DANDROID_STL=c++_shared",
                        "-DEDITORS_ENGINE_DIR=${mobileEditorsDir.resolve("editors-engine").path}",
                        "-DCORE_SOURCE_DIR=${mobileEditorsDir.resolve("core").path}",
                        "-DCORE_LIBS_DIR=$coreLibsDir"
                    )
                }
            }
            ndk {
                // The core libraries are only built for 64-bit ABIs
                abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
            }
        }
    }

    if (hasNativeCore) {
        ndkVersion = rootProject.extra.get("NDK_VERSION").toString()
        externalNativeBuild {
            cmake {
                path("src/native/cpp/CMakeLists.txt")
            }
        }
    }

    buildTypes {
        // JaCoCo report of the unit tests (createDebugUnitTestCoverageReport) for the CI's
        // coverage check
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }

    testOptions {
        // android.util.Log and the like do nothing in unit tests
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir(if (hasNativeCore) "src/native/java" else "src/nativeStub/java")
    if (hasNativeCore) {
        sourceSets["main"].assets.srcDirs("src/native/assets", engineAssetsDir)
    }
}

// The parts of sdkjs the engine loads (see DoctRenderer.config and core's editors.cpp), and
// open fonts with the metrics of the common Microsoft ones, so documents keep their layout
val copyEngineAssets by tasks.registering(Copy::class) {
    from(sdkjsDir) {
        include(
            "word/sdk-all-min.js",
            "word/sdk-all.js",
            "slide/sdk-all-min.js",
            "slide/sdk-all.js",
            "slide/themes/**",
            "cell/sdk-all-min.js",
            "cell/sdk-all.js",
            "common/Native/*.js",
            "common/SmartArts/**",
            "common/Charts/ChartStyles.js",
            "common/libfont/engine/fonts_native.js",
            // sdkjs' PDF editor reads PDFs through core's reader with it (pdf.js)
            "pdf/src/engine/drawingfile_native.js",
            "vendor/xregexp-all-min.js"
        )
        into("sdkjs")
    }
    // The blank document the PDF editor's context starts from (Document::OpenPdf)
    from(documentTemplatesDir) {
        include("new/en-US/new.docx")
        eachFile { path = "empty/new.docx" }
        includeEmptyDirs = false
    }
    from(coreFontsDir) {
        include(
            "ASC.ttf",
            "liberation/LiberationSans-*.ttf", // Arial
            "liberation/LiberationSerif-*.ttf", // Times New Roman
            "liberation/LiberationMono-*.ttf", // Courier New
            "crosextra/Carlito-*.ttf", // Calibri
            "caladea/*.ttf", // Cambria
            "opensans/*.ttf",
            // Symbol's and Wingdings' characters (bullets): sdkjs and core draw them with its glyphs
            "openoffice/opens___.ttf"
        )
        into("bundled-fonts")
    }
    // English, and where to download the other spell checking dictionaries from
    from(dictionariesDir) {
        into("dictionaries")
    }
    into(engineAssetsDir.map { it.dir("editors/runtime") })
    doFirst {
        if (!file("$sdkjsDir/word/sdk-all.js").exists()) {
            throw GradleException("sdkjs bundles not found in $sdkjsDir (see MobileEditors/build/README.md)")
        }
        if (!file("$dictionariesDir/index.json").exists()) {
            throw GradleException("dictionaries not found in $dictionariesDir (see MobileEditors/build/README.md)")
        }
    }
}

// The licences of what the editors contain (assets/editors-licenses.html, linked from the app's
// licences page): the third-party lists of core and sdkjs, Hunspell, the fonts and dictionaries
val editorsLicenses by tasks.registering {
    val sources = listOf(mobileEditorsDir.resolve("core/3DPARTY.md"), file("$sdkjsDir/3DPARTY.md"))
    inputs.files(sources)
    val output = engineAssetsDir.map { it.file("editors-licenses.html") }
    outputs.file(output)
    doLast {
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // "- name ([licence](url))" lines of the 3DPARTY.md files
        val item = Regex("""^- (.+?) \(\[(.+?)]\((.+?)\)\)""")
        val components = sources.filter { it.exists() }.flatMap { it.readLines() }.mapNotNull { item.find(it.trim()) }
            .map { Triple(it.groupValues[1].trim(), it.groupValues[2], it.groupValues[3]) } +
            Triple("hunspell (spell checking)", "MPL-1.1 / GPL-2.0 / LGPL-2.1", "https://github.com/hunspell/hunspell/blob/master/COPYING.MPL")
        val fonts = listOf(
            Triple("Liberation fonts", "SIL Open Font License 1.1", "https://github.com/liberationfonts/liberation-fonts/blob/main/LICENSE"),
            Triple("Carlito", "SIL Open Font License 1.1", "https://github.com/googlefonts/carlito/blob/main/OFL.txt"),
            Triple("Caladea", "SIL Open Font License 1.1", "https://github.com/huertatipografica/Caladea/blob/master/OFL.txt"),
            Triple("Open Sans", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
            Triple("OpenSymbol", "SIL Open Font License 1.0", "https://scripts.sil.org/OFL")
        )
        fun list(items: List<Triple<String, String, String>>) = items.distinctBy { it.first }.joinToString("\n") { (name, licence, url) ->
            "<li><b>${escape(name)}</b>: <a href=\"${escape(url)}\">${escape(licence)}</a></li>"
        }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText("""<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body { font-family: sans-serif; margin: 16px; } @media (prefers-color-scheme: dark) { body { background: #121212; color: #e0e0e0; } a { color: #8ab4f8; } }</style>
</head><body>
<h3>Document editors</h3>
<p>The document, spreadsheet, presentation and PDF editors are free software under the
<a href="https://www.gnu.org/licenses/agpl-3.0.html">GNU Affero General Public License 3.0</a>:
<a href="https://github.com/Euro-Office/editors-android">editors-android</a>,
<a href="https://github.com/Euro-Office/core">core</a>,
<a href="https://github.com/Euro-Office/sdkjs">sdkjs</a> and
<a href="https://github.com/Euro-Office/sdkjs-forms">sdkjs-forms</a>, by the Euro-Office contributors, based on ONLYOFFICE by Ascensio System SIA.</p>
<h3>Third-party components</h3>
<ul>
${list(components)}
</ul>
<h3>Fonts</h3>
<ul>
${list(fonts)}
</ul>
<h3>Icons</h3>
<p>The pictures of chart types and shapes, the icons of the menus, formats, list styles and slide
transitions and the names of the chart types, shapes and SmartArt layouts are from
<a href="https://github.com/Euro-Office/web-apps">web-apps</a>, © 2010-2024 Ascensio System SIA, under the
<a href="https://creativecommons.org/licenses/by-sa/4.0/legalcode">Creative Commons Attribution-ShareAlike 4.0
International</a> license. The icons were converted to Android drawables. The pictures of the SmartArt layouts
are drawn by sdkjs from Ascensio System SIA's layouts, under the same license. Our changes are under that
license, too. They come without warranty, as the license says.</p>
<p>The other icons are <a href="https://fonts.google.com/icons">Material Design icons</a>, © Google LLC, under the
<a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>; its text is on the app's
<a href="licenses.html">licences page</a>.</p>
<h3>Dictionaries</h3>
<p>Spell checking uses the Hunspell dictionaries of <a href="https://github.com/Euro-Office/dictionaries">Euro-Office/dictionaries</a>,
each under the licence given in its folder there.</p>
</body></html>
""")
    }
}

if (hasNativeCore) {
    tasks.named("preBuild") { dependsOn(copyEngineAssets, editorsLicenses) }
}

dependencies {
    implementation(project(":libtoolkit"))

    implementation(libs.ktx)
    implementation(libs.kotlin.coroutines.android)

    if (hasNativeCore) {
        implementation(project(":libx2t"))
    }

    // Co-editing: the Document Server's socket.io (Android has org.json)
    implementation("io.socket:socket.io-client:2.1.2") {
        exclude(group = "org.json", module = "json")
    }

    testImplementation(libs.junit)
    // Android's org.json is only a stub on the JVM
    testImplementation("org.json:json:20260814")
}
