package dotty.tools.dotc

import java.nio.file.{Path, Paths}
import java.util.EnumSet

import dotty.tools.dotc.sbt.interfaces.IncrementalCallback
import dotty.tools.dotc.util.SourceFile
import dotty.tools.io.AbstractFile

/** Zinc's analysis as events (`snc --events --analysis`, events protocol 1.1;
 *  see README.md, "Incremental compilation"). With `--analysis`,
 *  the compiler runs its sbt phases, `ExtractDependencies` and `ExtractAPI`,
 *  and `Callback` sends each of their calls of `IncrementalCallback` as one
 *  event, in order. A build tool replays them on Zinc's `AnalysisCallback`,
 *  so that Zinc decides what to recompile as it does for `scalac`.
 */
object SncAnalysis:

  /** Sends the calls of the sbt phases to `sink`. Sources are ordinals into
   *  `sources`, the compiled sources in command-line order; paths are
   *  absolute. The products of classes are their `.nir` files (see
   *  `PlatformHooks.classFileExtension`).
   *
   *  A binary dependency whose entry is an archive of the JDK (`jdkArchive`)
   *  is marked `"jdk": true`. On a JVM, the sbt phases report the JDK's
   *  classes by paths in the `jrt:` file system or in `ct.sym`, which Zinc
   *  recognizes: it does not track dependencies on them, except on classes
   *  that the project's own sources define. `snc` reads the JDK's archives as
   *  files, whose paths Zinc would take for libraries on the class path, so
   *  the build tool needs to be told which they are.
   */
  final class Callback(sink: SncEvents.Sink, sources: List[AbstractFile], jdkArchive: Path => Boolean)
      extends IncrementalCallback:
    import Json.*

    private val ordinals: Map[String, Int] =
      sources.zipWithIndex.reverse.flatMap((file, i) => List(file.path -> i, normalized(file.path) -> i)).toMap

    private def normalized(path: String): String = Paths.get(path).toAbsolutePath.normalize.toString

    private def source(file: SourceFile | Null): String =
      val path = file.path
      ordinals.get(path).orElse(ordinals.get(normalized(path))) match
        case Some(i) => i.toString
        case None => throw new IllegalStateException(s"snc --analysis: $path is not a source of this compilation")

    private def absolute(path: Path | Null): String = str(path.toAbsolutePath.toString)

    private def emit(fields: (String, String)*): Unit = sink.emit(obj(fields*), flush = false)

    override def enabled(): Boolean = true

    override def startSource(sourceFile: SourceFile | Null): Unit =
      emit("event" -> str("startSource"), "source" -> source(sourceFile))

    override def api(sourceFile: SourceFile | Null, classApi: xsbti.api.ClassLike | Null): Unit =
      emit("event" -> str("api"), "source" -> source(sourceFile), "classLike" -> ApiJson.classLike(classApi))

    override def mainClass(sourceFile: SourceFile | Null, className: String | Null): Unit =
      emit("event" -> str("mainClass"), "source" -> source(sourceFile), "name" -> str(className))

    override def usedName(className: String | Null, name: String | Null,
        useScopes: EnumSet[xsbti.UseScope] | Null): Unit =
      // In ordinal order, as a JVM EnumSet iterates
      val scopes = xsbti.UseScope.values.filter(useScopes.contains).map(s => str(s.name))
      emit("event" -> str("usedName"), "className" -> str(className), "name" -> str(name),
        "scopes" -> arr(scopes.toSeq))

    override def binaryDependency(onBinaryEntry: Path | Null, onBinaryClassName: String | Null,
        fromClassName: String | Null, fromSourceFile: SourceFile | Null,
        context: xsbti.api.DependencyContext | Null): Unit =
      val jdk = if onBinaryEntry != null && jdkArchive(onBinaryEntry) then List("jdk" -> "true") else Nil
      val fields =
        List("event" -> str("binaryDependency"), "entry" -> absolute(onBinaryEntry)) ++ jdk ++
          List(
            "binaryName" -> str(onBinaryClassName),
            "fromClass" -> str(fromClassName),
            "source" -> source(fromSourceFile),
            "context" -> str(context.name))
      emit(fields*)

    override def classDependency(onClassName: String | Null, sourceClassName: String | Null,
        context: xsbti.api.DependencyContext | Null): Unit =
      emit("event" -> str("classDependency"), "on" -> str(onClassName), "from" -> str(sourceClassName),
        "context" -> str(context.name))

    override def generatedLocalClass(sourceFile: SourceFile | Null, classFile: Path | Null): Unit =
      emit("event" -> str("generatedLocalClass"), "source" -> source(sourceFile), "file" -> absolute(classFile))

    override def generatedNonLocalClass(sourceFile: SourceFile | Null, classFile: Path | Null,
        binaryClassName: String | Null, srcClassName: String | Null): Unit =
      emit(
        "event" -> str("generatedNonLocalClass"),
        "source" -> source(sourceFile),
        "file" -> absolute(classFile),
        "binaryName" -> str(binaryClassName),
        "srcName" -> str(srcClassName))

    override def apiPhaseCompleted(): Unit = emit("event" -> str("apiPhaseCompleted"))

    override def dependencyPhaseCompleted(): Unit = emit("event" -> str("dependencyPhaseCompleted"))
  end Callback

  /** Zinc's API trees (`xsbti.api`) as JSON: arrays tagged by their first
   *  element, one tag per class, which the sbt plugin's `SncApiJson` decodes.
   *
   *  {{{
   *  ["CL", name, access, modifiers, annotations, definitionType, selfType,
   *         structure, savedAnnotations, childrenOfSealedClass, topLevel,
   *         typeParameters]
   *  }}}
   *
   *  Access is `"pub"`, `["pro", qualifier]` or `["pri", qualifier]`, a
   *  qualifier `"u"`, `"t"` or `["i", id]`; modifiers are a bit set in the
   *  order of `Modifiers`' constructor (bit 0 abstract, ..., bit 7 super
   *  accessor); enums are short names. Lazy members are forced.
   */
  object ApiJson:
    import xsbti.api.*

    def classLike(c: ClassLike): String =
      val w = new Writer
      w.classLike(c)
      w.result

    private final class Writer:
      private val sb = new java.lang.StringBuilder(4096)

      def result: String = sb.toString

      private def str(s: String): Unit = Json.appendStr(sb, s)
      private def bool(b: Boolean): Unit = sb.append(b)
      private def sep(): Unit = sb.append(',')
      private def tag(t: String): Unit =
        sb.append('[')
        str(t)
      private def end(): Unit = sb.append(']')

      private def array[T](xs: Array[T])(write: T => Unit): Unit =
        sb.append('[')
        var i = 0
        while i < xs.length do
          if i > 0 then sep()
          write(xs(i))
          i += 1
        sb.append(']')

      def classLike(c: ClassLike): Unit =
        tag("CL"); sep()
        str(c.name); sep()
        access(c.access); sep()
        modifiers(c.modifiers); sep()
        annotations(c.annotations); sep()
        definitionType(c.definitionType); sep()
        tpe(c.selfType); sep()
        structure(c.structure); sep()
        array(c.savedAnnotations)(str); sep()
        array(c.childrenOfSealedClass)(tpe); sep()
        bool(c.topLevel); sep()
        typeParameters(c.typeParameters)
        end()

      private def access(x: Access): Unit = x match
        case _: Public => str("pub")
        case p: Protected =>
          tag("pro"); sep(); qualifier(p.qualifier); end()
        case p: Private =>
          tag("pri"); sep(); qualifier(p.qualifier); end()

      private def qualifier(q: Qualifier): Unit = q match
        case _: Unqualified => str("u")
        case _: ThisQualifier => str("t")
        case i: IdQualifier =>
          tag("i"); sep(); str(i.value); end()

      private def modifiers(m: Modifiers): Unit =
        def bit(b: Boolean, i: Int) = if b then 1 << i else 0
        sb.append(
          bit(m.isAbstract, 0) | bit(m.isOverride, 1) | bit(m.isFinal, 2) | bit(m.isSealed, 3) |
          bit(m.isImplicit, 4) | bit(m.isLazy, 5) | bit(m.isMacro, 6) | bit(m.isSuperAccessor, 7))

      private def annotations(as: Array[Annotation]): Unit =
        array(as): a =>
          sb.append('[')
          tpe(a.base); sep()
          array(a.arguments): arg =>
            sb.append('[')
            str(arg.name); sep(); str(arg.value)
            end()
          end()

      private def definitionType(d: DefinitionType): Unit = str(d match
        case DefinitionType.Trait => "trait"
        case DefinitionType.ClassDef => "class"
        case DefinitionType.Module => "module"
        case DefinitionType.PackageModule => "package")

      private def variance(v: Variance): Unit = str(v match
        case Variance.Covariant => "co"
        case Variance.Contravariant => "contra"
        case Variance.Invariant => "in")

      private def typeParameters(ts: Array[TypeParameter]): Unit =
        array(ts): t =>
          sb.append('[')
          str(t.id); sep()
          annotations(t.annotations); sep()
          typeParameters(t.typeParameters); sep()
          variance(t.variance); sep()
          tpe(t.lowerBound); sep()
          tpe(t.upperBound)
          end()

      private def structure(s: Structure): Unit =
        tag("S"); sep()
        array(s.parents)(tpe); sep()
        array(s.declared)(definition); sep()
        array(s.inherited)(definition)
        end()

      private def path(p: xsbti.api.Path): Unit =
        array(p.components):
          case i: Id =>
            tag("i"); sep(); str(i.id); end()
          case s: Super =>
            tag("s"); sep(); path(s.qualifier); end()
          case _: This =>
            tag("t"); end()

      private def tpe(t: Type): Unit = t match
        case p: Projection =>
          tag("P"); sep(); tpe(p.prefix); sep(); str(p.id); end()
        case p: Parameterized =>
          tag("Z"); sep(); tpe(p.baseType); sep(); array(p.typeArguments)(tpe); end()
        case p: ParameterRef =>
          tag("R"); sep(); str(p.id); end()
        case s: Structure => structure(s)
        case p: Polymorphic =>
          tag("Poly"); sep(); tpe(p.baseType); sep(); typeParameters(p.parameters); end()
        case c: Constant =>
          tag("C"); sep(); tpe(c.baseType); sep(); str(c.value); end()
        case e: Existential =>
          tag("X"); sep(); tpe(e.baseType); sep(); typeParameters(e.clause); end()
        case s: Singleton =>
          tag("Y"); sep(); path(s.path); end()
        case n: Annotated =>
          tag("A"); sep(); tpe(n.baseType); sep(); annotations(n.annotations); end()
        case _: EmptyType =>
          tag("E"); end()

      private def parameterModifier(m: ParameterModifier): Unit = str(m match
        case ParameterModifier.Repeated => "rep"
        case ParameterModifier.ByName => "byname"
        case ParameterModifier.Plain => "plain")

      /** The fields that all definitions start with, after the tag. */
      private def header(tagName: String, d: Definition): Unit =
        tag(tagName); sep()
        str(d.name); sep()
        access(d.access); sep()
        modifiers(d.modifiers); sep()
        annotations(d.annotations)

      private def definition(d: ClassDefinition): Unit = d match
        case d: Def =>
          header("Def", d); sep()
          typeParameters(d.typeParameters); sep()
          array(d.valueParameters): l =>
            sb.append('[')
            array(l.parameters): p =>
              sb.append('[')
              str(p.name); sep()
              tpe(p.tpe); sep()
              bool(p.hasDefault); sep()
              parameterModifier(p.modifier)
              end()
            sep()
            bool(l.isImplicit)
            end()
          sep()
          tpe(d.returnType)
          end()
        case v: Val =>
          header("Val", v); sep(); tpe(v.tpe); end()
        case v: Var =>
          header("Var", v); sep(); tpe(v.tpe); end()
        case t: TypeAlias =>
          header("TA", t); sep(); typeParameters(t.typeParameters); sep(); tpe(t.tpe); end()
        case t: TypeDeclaration =>
          header("TD", t); sep()
          typeParameters(t.typeParameters); sep()
          tpe(t.lowerBound); sep()
          tpe(t.upperBound)
          end()
        case c: ClassLikeDef =>
          header("CLD", c); sep()
          typeParameters(c.typeParameters); sep()
          definitionType(c.definitionType)
          end()
    end Writer
  end ApiJson
end SncAnalysis
