# snc: the Scala 3 compiler, native, for Scala Native

This is a fork of [Scala 3](https://github.com/scala/scala3) (based on its `main`) that builds the compiler with [Scala Native](https://scala-native.org) into `snc`: a native executable that compiles Scala 3 to TASTy and NIR (Scala Native's intermediate representation), without a JVM. It has the Scala Native compiler plugin built in, and writes the same TASTy and NIR as the JVM compiler with that plugin. It compiles against the Java API of an installed JDK, which it reads without running it. `snc-host`, the same compiler linked as a Scala Native *live-execution host*, expands macros: it links a macro library's code against itself and loads it into its own process.

It needs a companion fork of Scala Native (based on Scala Native's `main`), which provides the sbt plugin, the libraries, nscplugin for this compiler's Scala version, `snlink`, and the live execution that `snc-host` uses. Its README describes them.

## Building

1. In the Scala Native fork, publish its artifacts locally, as its README describes ("Building and publishing locally"). The tests also need its `snlink`.
2. Here:

   ```sh
   sbt snc/nativeLink
   sbt 'set LocalProject("snc-host") / nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))' \
     snc-host/nativeLink
   ```

   With the Scala Native fork's artifacts in another Ivy home `<dir>`, run sbt with `-Dsbt.ivy.home=<dir>`.

   `snc` is `native/snc/target/scala-3.10.2-RC1/snc`. `snc-host` is in `native/snc-host/target/scala-3.10.2-RC1/`, with its manifest (`snc-host.snm`) and the class path it was linked from (`snc-host.classpath`), which it needs: next to it, or as `lib/snc.snm` and `lib/snc.classpath` for a `bin/snc`. A symbolic link to `snc-host` finds them next to the file it links to.
3. To compile with it in sbt or Mill, through the Scala Native fork's plugins, name `snc-host` in `SNC`, or link it as `snc` from a directory on the `PATH`:

   ```sh
   export SNC=$PWD/native/snc-host/target/scala-3.10.2-RC1/snc-host
   # or
   ln -s $PWD/native/snc-host/target/scala-3.10.2-RC1/snc-host ~/.local/bin/snc
   ```

   The Scala Native fork's README ("Compiling with snc in sbt and Mill") and its `examples/` show how, and what incremental compilation recompiles.

The compiler is built by the reference compiler (3.10.2-RC1) and reports this branch's Scala 3 nightly as its version (`snc --about`, `scalaVersion`): programs compiled with it use `scala-library` of that version, and link with Scala Native's `scalalib_native0.5_3` and `scala3lib_native0.5_3` of `<scalaVersion>+<scalaNativeVersion>`.

## Usage

```sh
snc -classpath <scala-library and Scala Native's libraries> -d out Main.scala
snlink --main Main --base-dir target out <libraries> <the Scala library's NIR>
```

`snc` takes the command line that build tools give `scalac`, with `scalac`'s meaning: the compiler's options, `@argfiles`, `.scala` sources and `.java` sources, parsed for their signatures. `-d` names a directory, which receives `.tasty` and `.nir` files. Its own options:

| Option | Meaning |
|---|---|
| `-scalanative` | emit NIR (the default and only backend) |
| `--java-home <dir>` | the JDK whose Java API to compile against ([The JDK](#the-jdk)) |
| `--about` | print one line of JSON for build tools: versions, plugins, protocols |
| `--events`, `--events-text` | report as an event stream on standard output ([Events](#events)) |
| `--analysis` | with `--events` or `--events-text`: also send Zinc's analysis, for incremental compilation ([Incremental compilation](#incremental-compilation)) |

- Diagnostics are printed exactly as `scalac` prints them. The exit code is 0 for success (also for `-help` and `-version`), 1 for compilation errors, 2 for a bad command line or setup, and 3 for a crash.
- The Scala Native compiler plugin is built in and enabled; `-P:scalanative:<option>` passes it options. Scala Native's junit-plugin is built in too, and is enabled by an `-Xplugin` path to its jar, as build tools give it for JUnit tests. Other plugins cannot be loaded.
- Options for the JVM or a JVM backend (`-J`, `-opt`, ...) are accepted with a configuration warning.
- Compile against Scala Native's libraries (`nativelib`, `clib`, `posixlib`, `windowslib`, `javalib`, `auxlib`) and `scala-library` of `snc`'s Scala version. An output directory of `snc` is a valid class path entry for the next compilation.

### The JDK

`snc` compiles against the Java API of a JDK, as `scalac` with the Scala Native plugin does on the JVM it runs on. It takes the first of `--java-home <dir>`, `$JAVA_HOME`, `java` on the `PATH`, the JDK that macOS's `/usr/libexec/java_home` names, and the newest JDK that coursier has installed. A JDK is a directory with a `release` file and `jmods/` or `lib/ct.sym`; `-verbose` says which JDK was taken.

- For `-release N` below the JDK's version it reads release N's API from `lib/ct.sym`; otherwise the JDK's own classes, from `jmods/` (or from `ct.sym` for JDKs that have no `jmods/`). `-javabootclasspath` adds jars after them.
- The JDK's API is wider than Scala Native's javalib: code that calls what javalib lacks compiles, as with `scalac`, and fails when it is linked.

### Events

With `--events`, standard output carries only a binary event stream (header `SNCE`, then frames of a length, a kind and a UTF-8 JSON payload); `--events-text` writes the same JSON objects one per line. Everything else goes to standard error. The events are `start` (the protocol and compiler versions), one `diagnostic` per diagnostic as it is reported (severity, message id, kind, message, positions with offsets, explanation, related positions, quick fixes, and the text as `scalac` prints it), and `done` (exit code and counts), always last. With `--analysis`, the analysis events come between them.

### Incremental compilation

With `--analysis`, `snc` runs the sbt phases (`sbt-deps` and `sbt-api`, as `scalac` does under Zinc) and sends each call they make to Zinc's incremental callback as one event, in the order of the calls. A JVM-side `xsbti.compile.ScalaCompiler`, such as the one in the companion Scala Native fork's sbt and Mill plugins, replays them on Zinc's `AnalysisCallback`; Zinc then hashes the API and decides what to recompile as it does for `scalac`. `--about` says `"analysis":true` and `"eventsProtocol":"1.1"`. The events of a compilation with errors are incomplete: use them only when `done` has exit code 0.

| Event | Fields | `AnalysisCallback` method |
|---|---|---|
| `startSource` | `source` | `startSource` |
| `api` | `source`, `classLike` | `api` |
| `mainClass` | `source`, `name` | `mainClass` |
| `usedName` | `className`, `name`, `scopes` (`UseScope` names) | `usedName` |
| `binaryDependency` | `entry`, `jdk` (only when true), `binaryName`, `fromClass`, `source`, `context` (a `DependencyContext` name) | `binaryDependency` |
| `classDependency` | `on`, `from`, `context` | `classDependency` |
| `generatedNonLocalClass` | `source`, `file`, `binaryName`, `srcName` | `generatedNonLocalClass` |
| `generatedLocalClass` | `source`, `file` | `generatedLocalClass` |
| `apiPhaseCompleted`, `dependencyPhaseCompleted` | | the same |

- `source` is the ordinal of a source among the command line's source files (`.scala` and `.java`), counting from 0. Paths are absolute.
- Products are the NIR file of each class, `X.nir` (and `X$.nir` for a module), in the `-d` directory. A binary dependency on a class read from TASTy in a directory names its sibling `.nir` file; on a class in a jar, the jar.
- A binary dependency on a class of the JDK names the archive `snc` read it from (`lib/ct.sym` or a jmod) and has `"jdk": true`. Zinc does not track dependencies on the JDK, except on classes that the project's own sources define; it recognizes them by the paths that `scalac` reports (in the `jrt:` file system or in `ct.sym`), so a client passes such a path as the class file.
- Not reported: the classes that only the Native backend generates (lambdas, `$SN$ReflectivelyInstantiate$`), local and anonymous classes (which the JVM backend reports to `scalac`'s Zinc), and TASTy files. A client makes them products of the source of their top-level class.
- `classLike` is Zinc's `xsbti.api.ClassLike` tree as JSON arrays tagged by their first element, one tag per `xsbti.api` class, with the fields in the order of the class's factory method (`native/snc/src/dotty/tools/dotc/SncAnalysis.scala` writes it). The sbt phases build these trees with Scala versions of Zinc's `xsbti.api` classes (`native/sbt-interfaces`, generated from Zinc's compiler interface by `generate.py`).

## Macros: snc-host

The plain `snc` cannot load code, so it fails on a macro call. `snc-host` expands macros as the JVM compiler does, from the class path and from the same compilation run:

- The first expansion that needs a macro implementation from a class path entry reads the entry's NIR (libraries published for Scala Native have it), generates an entry table for its macro code, links it with the code it reaches against `snc-host`'s manifest, loads the bundle and runs the macro. Code that does not link on Scala Native (a JDK API that javalib lacks, another platform's API) becomes a stub that throws `NoSuchMethodException` naming what it needs, so a macro fails only if it runs that code.
- Bundles are cached in `$XDG_CACHE_HOME/snc/macros` (by default `~/.cache/snc/macros`), keyed by the entry's content, `snc-host`'s manifest, clang and the link mode. The first use of a library's macros takes about one to a few seconds; later compiles load the bundle in milliseconds.
- A unit that uses a macro defined in the same run waits for the run that compiles it, as with the JVM compiler.
- It needs clang and a linker, as Scala Native does.

| Variable | Effect |
|---|---|
| `SNC_MACRO_MODE=release` | link macro code with the optimizer, instead of in debug mode |
| `SNC_MACRO_LOG=<file>` | append a line per class path entry whose macro code is loaded: `linked`, `cached` or `failed`, and the time |
| `SNC_LINK_VERBOSE=1` | print the extension linker's log |
| `SNC_MACRO_LOADER=off` | load no macro code |

### snc-host

`native/snc/host/boundary.txt` says what code that `snc-host` loads may subclass (open types: `Object`, the function and SAM types, `Product`, exceptions, `Ordering`, the quotes API's `TreeMap` and accumulators, ...) and call (callable types: the standard library with `QuotesImpl`, and the core of javalib). Both builds read it: `snc-host/nativeLink` and `native/snc/host/link-host.sh`, which links with `snlink`. `live.ExtensionLinker` is the internal API, and `snc-host --link-extension <work dir> <NIR>`, `--load-extension <bundle> <module>` and `--host-info` exercise it.

## Testing

The tests use the environment that `native/snc/test/env.sh` sets up, with `JAVA_HOME` naming a JDK 17, `SNC_IVY_HOME` the Ivy home of the Scala Native fork's artifacts, and `SNLINK` its `snlink`:

```sh
. native/snc/test/env.sh <work dir>
WORK=<dir> native/snc/test/compat/run.sh
SNC=$SNC_HOST WORK=<dir> native/snc/host/test/run.sh
cd native/snc/test/macro-rig && SNC=$SNC_HOST WORK=<dir> python3 -I run-macros.py prepare
cd native/snc/test/macro-rig && SNC=$SNC_HOST WORK=<dir> python3 -I run-macros.py exec
```

### Compatibility suite

`native/snc/test/compat/run.sh`: each case runs with `snc` and with the JVM compiler built from the same sources, both against the same JDK, and compares exit codes, standard output and error, and the files written. Cases marked `snc` check `snc`'s own behavior against expected outputs (`UPDATE=1` rewrites them). `sbt-phases` compares the analysis that `-Ydump-sbt-inc` writes with `scalac`'s, and checks the `--analysis` events of the same sources.

### snc-host tests

`native/snc/host/test/run.sh`: macros compiled from source are linked and loaded with `--link-extension` and `--load-extension`, then through the macro executor from the class path, from its cache, and within one run; a case checks that a loaded extension's static state survives garbage collections.

### Macro tests

`native/snc/test/macro-rig/run-macros.py` compiles the separately compiled tests of `tests/run-macros` (their macro groups with the JVM compiler), compiles the last group with `snc-host` (`exec`) or with a build of `snc` that has the macro implementations linked in (`rig`, built with `RigGen.scala` and `link-rig.sh`), links each program with `snlink` and compares its output with the check file; `joint-exec` compiles the tests that define and use macros in one run. `rig-exclude.txt` lists the tests whose macro code does not link on Scala Native.

### Unit tests

`NativeCompilerTest` checks that the native compiler's phases differ from the JVM compiler's only by those it leaves out and adds.

## Limitations

- Only directories as outputs, not jars. No plugins other than the two built in, no JVM or Scala.js backend, no REPL.
- `-from-tasty` with the Scala Native plugin fails, as with `scalac`: the plugin's interop phase needs `posttyper`.
- `-usejavacp` finds nothing, and `-verbose`'s class path differs from the JVM's.

---

The rest of this file is upstream's README.

Scala 3
=====
[![Scala 3 CI](https://github.com/scala/scala3/actions/workflows/ci.yaml/badge.svg?branch=main)](https://github.com/scala/scala3/actions/workflows/ci.yaml?query=branch%3Amain)
[![Join the chat at https://discord.com/invite/scala](https://img.shields.io/discord/632150470000902164)](https://discord.com/invite/scala)

This is the home of the [Scala 3](https://www.scala-lang.org) standard library, compiler, and language spec.
More documentation [here](https://docs.scala-lang.org/scala3/).

Building a Local Distribution
=============================
1. `sbt dist/Universal/packageBin`
2. Find the newly-built distributions in `dist/target/`

Code of Conduct
===============
Scala 3 uses the [Scala Code of Conduct](https://www.scala-lang.org/conduct.html)
for all communication and discussion. This includes both GitHub, Discord and
other more direct lines of communication such as email.

How to Contribute
=================
* [Contributing guide](./CONTRIBUTING.md)
* [Issues where help is wanted](https://github.com/scala/scala3/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
* [Policy regarding the use of LLM-based tools](LLM_POLICY.md)

License
=======
Scala 3 is licensed under the [Apache License Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
