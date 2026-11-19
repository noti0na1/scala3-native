package dotty.tools.dotc

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.collection.mutable

import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir
import scala.scalanative.nir.{Defn, Global, Inst, Next, Op, Sig, Type, Val}
import scala.scalanative.nir.serialization.{deserializeBinary, serializeBinary}
import scala.scalanative.util.Scope

/** The entry table of a class path entry's macro code, as NIR: a module that
 *  extends `dotty.tools.dotc.quoted.MacroInvokers`, whose
 *  `invoke(index, receiver, args)` runs entry `index`, and the keys that
 *  `MacroRegistry` looks the entries up by (see `MacroRegistry.Key`).
 *
 *  Entries, as in the macro test rig (`native/snc/test/macro-rig/RigGen.scala`):
 *  the methods whose erased signature mentions a `scala.quoted` type (macro
 *  implementations take a `Quotes` or return an `Expr`) and modules' getters
 *  of a function type (`Quotes ?=> Expr[T]` values); the modules that declare
 *  or inherit such a method, which the interpreter loads to call it; the
 *  constructors of `MacroAnnotation` subclasses, and
 *  `MacroAnnotation.transform`. Each entry runs in a method of its own, so
 *  that an entry whose code does not link becomes a stub alone.
 *
 *  Only the entry's NIR files that mention `scala.quoted` or
 *  `MacroAnnotation`, and those that mention the classes found so (modules
 *  that inherit an entry, subclasses of macro annotations), are read.
 */
object MacroGlue:

  given nir.SourcePosition = nir.SourcePosition.NoPosition
  given nir.ScopeId = nir.ScopeId.TopLevel

  private val ObjectT = Type.Ref(Global.Top("java.lang.Object"))
  private val StringT = Type.Ref(Global.Top("java.lang.String"))
  private val Invokers = Global.Top("dotty.tools.dotc.quoted.MacroInvokers")
  private val MacroAnnotation = Global.Top("scala.annotation.MacroAnnotation")
  private val InvokeSig = Sig.Method("invoke", Seq(Type.Int, ObjectT, Type.Array(ObjectT), ObjectT)).mangled
  private val UnsupportedOp = Global.Top("java.lang.UnsupportedOperationException")

  /** What `generate` wrote: the registry keys, one per entry in order, and
   *  the classes it replaced by copies with stubs.
   */
  final case class Result(keys: Seq[String], stubbedClasses: Seq[String], filesRead: Int, filesTotal: Int)

  /** Methods of macro code that are replaced: by class and method name, the
   *  reason. The stub throws `UnsupportedOperationException` with the reason,
   *  only if the macro runs it; a reason that starts with `=identity` makes it
   *  return its first argument instead, for a method whose result is only
   *  cosmetic. Macro code whose closure does not link becomes stubs without
   *  being listed (`live.HostStubs.linkExtension`); these are methods that
   *  link but fail when they run.
   */
  val builtinStubs: Map[(String, String), String] = Map(
    // utest's assertion macros shorten type names with a lookbehind regex,
    // which java.util.regex here (RE2) does not support
    ("utest.asserts.StringUtilHelpers$", "stripScalaCorePrefixes") ->
      "=identity: type names in assertion messages keep their package"
  )

  private sealed trait Entry:
    def key: String
  private final case class ModuleEntry(key: String, module: Global.Top) extends Entry
  private final case class NewEntry(key: String, cls: Global.Top, ctor: Global.Member, params: Seq[Type]) extends Entry
  private final case class CallEntry(key: String, owner: Global.Top, method: Global.Member, params: Seq[Type],
      result: Type, direct: Boolean) extends Entry

  /** Writes to `out` the NIR of module `glue`, the entry table of the macro
   *  code in `root` (a jar or directory of NIR), and copies of the classes of
   *  `root` that `stubs` replaces methods of.
   */
  def generate(root: Path, out: Path, glue: Global.Top, stubs: Map[(String, String), String]): Result =
    Scope { implicit in =>
      val dir = VirtualDirectory.real(root)
      val files = dir.files.filter(_.toString.endsWith(".nir")).sortBy(_.toString)
      val bytes = mutable.HashMap.empty[Path, Array[Byte]]
      def contentOf(file: Path): Array[Byte] =
        bytes.getOrElseUpdate(file, {
          val buffer = dir.read(file)
          val array = new Array[Byte](buffer.remaining())
          buffer.get(array)
          array
        })
      val parsed = mutable.LinkedHashMap.empty[Path, Seq[Defn]]
      def parse(file: Path): Unit =
        if !parsed.contains(file) then parsed(file) = deserializeBinary(dir, file)

      // Files that mention quotes or macro annotations
      val quoted = "scala.quoted.".getBytes("UTF-8")
      val annotation = MacroAnnotation.id.getBytes("UTF-8")
      files.foreach { file =>
        val content = contentOf(file)
        if contains(content, quoted) || contains(content, annotation) then parse(file)
      }
      // Then those that mention the classes found so: modules that inherit
      // entries, subclasses of macro annotations; until there are no more
      val searched = mutable.Set.empty[String]
      var names = interestingNames(parsed.valuesIterator.flatten.toSeq)
      while names.nonEmpty do
        searched ++= names
        val patterns = new Patterns(names.toSeq.map(_.getBytes("UTF-8")))
        files.foreach { file =>
          if !parsed.contains(file) && patterns.foundIn(contentOf(file)) then parse(file)
        }
        names = interestingNames(parsed.valuesIterator.flatten.toSeq) -- searched

      val defns = parsed.valuesIterator.flatten.toSeq
      val entries0 = findEntries(defns)
      val transform = defns.collectFirst {
        case d: Defn.Define if isAnnotationTransform(d, defns) =>
          val Sig.Method(_, types, _) = d.name.sig.unmangled: @unchecked
          val params = types.init
          CallEntry(s"C ${MacroAnnotation.id} transform ${params.size} ${descriptors(params)}",
            MacroAnnotation, MacroAnnotation.member(d.name.sig), params, types.last, direct = false)
      }
      val entries = entries0 ++ transform

      // Copies of classes with stubs, which the extension's roots put before
      // the entry's own
      val stubbedClasses = stubs.keySet.map(_._1)
      val copied = mutable.ArrayBuffer.empty[String]
      files.foreach { file =>
        val top = scala.scalanative.io.packageNameFromPath(file)
        if stubbedClasses(top) then
          val fileDefns = parsed.getOrElse(file, deserializeBinary(dir, file))
          write(out.resolve(file.toString.stripPrefix("/")), stubbed(fileDefns, stubs))
          copied += top
      }
      if entries.nonEmpty then
        write(out.resolve(glue.id.replace('.', '/') + ".nir"), glueModule(glue, entries))
      Result(entries.map(_.key), copied.toSeq, parsed.size, files.size)
    }

  /** Whether `content` contains `pattern`. */
  private def contains(content: Array[Byte], pattern: Array[Byte]): Boolean =
    val first = pattern(0)
    val last = content.length - pattern.length
    var i = 0
    var found = false
    while !found && i <= last do
      if content(i) == first then
        var j = 1
        while j < pattern.length && content(i + j) == pattern(j) do j += 1
        if j == pattern.length then found = true
      i += 1
    found

  /** Byte patterns, found by their first four bytes. */
  private final class Patterns(patterns: Seq[Array[Byte]]):
    private def word(a: Array[Byte], i: Int): Int =
      (a(i) & 0xff) << 24 | (a(i + 1) & 0xff) << 16 | (a(i + 2) & 0xff) << 8 | (a(i + 3) & 0xff)
    private val byPrefix: Map[Int, Seq[Array[Byte]]] =
      patterns.filter(_.length >= 4).groupBy(word(_, 0))
    private val short = patterns.filter(p => p.nonEmpty && p.length < 4)

    def foundIn(content: Array[Byte]): Boolean =
      var i = 0
      var found = short.exists(contains(content, _))
      val last = content.length - 4
      while !found && i <= last do
        byPrefix.get(word(content, i)) match
          case Some(candidates) =>
            found = candidates.exists { p =>
              i + p.length <= content.length && {
                var j = 4
                while j < p.length && content(i + j) == p(j) do j += 1
                j == p.length
              }
            }
          case None =>
        i += 1
      found

  /** Classes and traits whose modules and subclasses may have entries: those
   *  that declare an entry method and are not modules, and those that derive
   *  from `MacroAnnotation`.
   */
  private def interestingNames(defns: Seq[Defn]): Set[String] =
    val parents = hierarchy(defns)
    val modules = defns.collect { case m: Defn.Module => m.name }.toSet
    val owners = defns.collect {
      case Defn.Define(_, Global.Member(owner, sig), _, _, _)
          if !modules(owner) && isEntryMethod(owner, sig, Set.empty) => owner.id
    }.toSet
    val annotations = parents.keysIterator
      .filter(cls => !modules(cls) && derivesFrom(cls, MacroAnnotation, parents)).map(_.id).toSet
    (owners ++ annotations) - MacroAnnotation.id

  private def isEntryMethod(owner: Global.Top, sig: Sig, modules: Set[Global.Top]): Boolean =
    sig.unmangled match
      case Sig.Method(id, types, scope) if !scope.isStatic && !id.contains("$default$") && id != "writeReplace" =>
        val params = types.init
        val isGetter = modules(owner) && params.isEmpty && types.last == Type.Ref(Global.Top("scala.Function1"))
        mentionsQuoted(types) || isGetter
      case _ => false

  /** Does one of `types` belong to `scala.quoted`? */
  private def mentionsQuoted(types: Seq[Type]): Boolean =
    types.exists {
      case Type.Ref(Global.Top(id), _, _) => id.startsWith("scala.quoted.")
      case Type.Array(t, _) => mentionsQuoted(Seq(t))
      case _ => false
    }

  /** The classes among `defns`, by name, with their parent and traits. */
  private def hierarchy(defns: Seq[Defn]): Map[Global.Top, Seq[Global.Top]] =
    defns.collect {
      case Defn.Class(_, name, parent, traits) => name -> (parent.toSeq ++ traits)
      case Defn.Module(_, name, parent, traits) => name -> (parent.toSeq ++ traits)
      case Defn.Trait(_, name, traits) => name -> traits
    }.toMap

  private def derivesFrom(cls: Global.Top, target: Global.Top, parents: Map[Global.Top, Seq[Global.Top]]): Boolean =
    cls == target || parents.getOrElse(cls, Nil).exists(derivesFrom(_, target, parents))

  private def isAnnotationTransform(d: Defn.Define, defns: Seq[Defn]): Boolean =
    d.name.sig.isMethod && !d.name.sig.isStatic && d.name.sig.unmangled.match
      case Sig.Method("transform", types, Sig.Scope.Public) => types.size == 4
      case _ => false
    && derivesFrom(d.name.top, MacroAnnotation, hierarchy(defns))

  private def findEntries(defns: Seq[Defn]): Seq[Entry] =
    val parents = hierarchy(defns)
    val modules = defns.collect { case m: Defn.Module => m.name }.toSet
    val entries = mutable.ArrayBuffer.empty[Entry]
    for d <- defns do d match
      case Defn.Define(_, name @ Global.Member(owner, sig), _, _, _) =>
        sig.unmangled match
          case Sig.Method(id, types, scope) if isEntryMethod(owner, sig, modules) =>
            val params = types.init
            entries += CallEntry(s"C ${owner.id} $id ${params.size} ${descriptors(params)}",
              owner, name, params, types.last,
              direct = scope.privateIn.isDefined || modules(owner))
          case Sig.Ctor(types) if derivesFrom(owner, MacroAnnotation, parents) =>
            entries += NewEntry(s"N ${owner.id} ${types.size} ${descriptors(types)}", owner, name, types)
          case _ =>
      case _ =>
    // The modules that declare or inherit a method with an entry, which the
    // executor loads to call it
    val owners = entries.collect { case e: CallEntry => e.key.split(' ')(1) }.toSet
    def hasEntries(cls: Global.Top): Boolean =
      owners(cls.id) || parents.getOrElse(cls, Nil).exists(hasEntries)
    val moduleEntries = modules.toSeq.sortBy(_.id).filter(hasEntries).map(m => ModuleEntry(s"M ${m.id}", m))
    (moduleEntries ++ entries).distinctBy(_.key)

  private def descriptors(types: Seq[Type]): String =
    if types.isEmpty then "-" else types.map(descriptor).mkString(",")

  /** A type as in the registry's keys: a JVM descriptor, with dots. */
  private def descriptor(t: Type): String = t match
    case Type.Bool => "Z"
    case Type.Byte => "B"
    case Type.Char => "C"
    case Type.Short => "S"
    case Type.Int => "I"
    case Type.Long => "J"
    case Type.Float => "F"
    case Type.Double => "D"
    case Type.Unit => "V"
    case Type.Array(e, _) => "[" + descriptor(e)
    case Type.Ref(Global.Top(id), _, _) => s"L$id;"
    case Type.Null => "Lscala.runtime.Null$;"
    case Type.Nothing => "Lscala.runtime.Nothing$;"
    case other => other.show

  private def isPrimitive(t: Type): Boolean = Type.box.contains(t) && t != Type.Ptr && t != Type.Size

  private def entrySig(i: Int): Sig =
    Sig.Method(s"entry$i", Seq(ObjectT, Type.Array(ObjectT), ObjectT), Sig.Scope.Public).mangled

  /** The glue module: `invoke(index, receiver, args)` calls `entry<index>`,
   *  which runs the entry.
   */
  private def glueModule(name: Global.Top, entries: Seq[Entry]): Seq[Defn] =
    val self = Type.Ref(name)
    val ctor =
      given fresh: nir.Fresh = nir.Fresh()
      val thisv = Val.Local(fresh(), self)
      Defn.Define(nir.Attrs.None, name.member(Sig.Ctor(Seq())), Type.Function(Seq(self), Type.Unit), Seq(
        Inst.Label(fresh(), Seq(thisv)),
        Inst.Let(Op.Call(Type.Function(Seq(Type.Ref(Invokers)), Type.Unit),
          Val.Global(Invokers.member(Sig.Ctor(Seq())), Type.Ptr), Seq(thisv)), Next.None),
        Inst.Ret(Val.Unit)))
    val entryTy = Type.Function(Seq(self, ObjectT, Type.Array(ObjectT)), ObjectT)
    val invoke =
      given fresh: nir.Fresh = nir.Fresh()
      val thisv = Val.Local(fresh(), self)
      val index = Val.Local(fresh(), Type.Int)
      val receiver = Val.Local(fresh(), ObjectT)
      val args = Val.Local(fresh(), Type.Array(ObjectT))
      val labels = entries.map(_ => fresh())
      val default = fresh()
      val insts = mutable.ArrayBuffer.empty[Inst]
      insts += Inst.Label(fresh(), Seq(thisv, index, receiver, args))
      insts += Inst.Switch(index, Next(default), labels.zipWithIndex.map((l, i) => Next.Case(Val.Int(i), Next(l))))
      for (label, i) <- labels.zipWithIndex do
        insts += Inst.Label(label, Seq())
        val id = fresh()
        insts += Inst.Let(id, Op.Call(entryTy, Val.Global(name.member(entrySig(i)), Type.Ptr), Seq(thisv, receiver, args)), Next.None)
        insts += Inst.Ret(Val.Local(id, ObjectT))
      insts += Inst.Label(default, Seq())
      insts += Inst.Ret(Val.Null)
      Defn.Define(nir.Attrs.None, name.member(InvokeSig),
        Type.Function(Seq(self, Type.Int, ObjectT, Type.Array(ObjectT)), ObjectT), insts.toSeq)
    val entryMethods = entries.zipWithIndex.map((entry, i) => entryMethod(name, i, entry, entryTy))
    Seq(Defn.Module(nir.Attrs.None, name, Some(Invokers), Seq()), ctor, invoke) ++ entryMethods

  private def entryMethod(glue: Global.Top, i: Int, entry: Entry, ty: Type.Function): Defn.Define =
    given fresh: nir.Fresh = nir.Fresh()
    val thisv = Val.Local(fresh(), Type.Ref(glue))
    val receiver = Val.Local(fresh(), ObjectT)
    val args = Val.Local(fresh(), Type.Array(ObjectT))
    val insts = mutable.ArrayBuffer.empty[Inst]
    insts += Inst.Label(fresh(), Seq(thisv, receiver, args))
    def let(op: Op): Val =
      val id = fresh()
      insts += Inst.Let(id, op, Next.None)
      Val.Local(id, op.resty)
    def arg(i: Int, ty: Type): Val =
      val raw = let(Op.Arrayload(ObjectT, args, Val.Int(i)))
      ty match
        case t if isPrimitive(t) => let(Op.Unbox(Type.box(t), raw))
        case ObjectT | Type.Unit => raw
        case t: Type.RefKind => let(Op.As(t, raw))
        case _ => raw
    def result(v: Val, ty: Type): Val =
      if isPrimitive(ty) then let(Op.Box(Type.box(ty), v)) else v
    entry match
      case ModuleEntry(_, module) =>
        insts += Inst.Ret(let(Op.Module(module)))
      case NewEntry(_, cls, ctorName, params) =>
        val obj = let(Op.Classalloc(cls, None))
        val argvs = params.zipWithIndex.map((t, i) => arg(i, t))
        let(Op.Call(Type.Function(Type.Ref(cls) +: params, Type.Unit), Val.Global(ctorName, Type.Ptr), obj +: argvs))
        insts += Inst.Ret(obj)
      case CallEntry(_, owner, method, params, res, direct) =>
        val recv = let(Op.As(Type.Ref(owner), receiver))
        val argvs = params.zipWithIndex.map((t, i) => arg(i, t))
        val fn = if direct then Val.Global(method, Type.Ptr) else let(Op.Method(recv, method.sig))
        val r = let(Op.Call(Type.Function(Type.Ref(owner) +: params, res), fn, recv +: argvs))
        res match
          case Type.Nothing | Type.Ref(Global.Top("scala.runtime.Nothing$"), _, _) =>
            insts += Inst.Unreachable(Next.None)
          case _ => insts += Inst.Ret(result(r, res))
    Defn.Define(nir.Attrs.None, glue.member(entrySig(i)), ty, insts.toSeq)

  /** `defns` with the body of each method named in `stubs` replaced. */
  private def stubbed(defns: Seq[Defn], stubs: Map[(String, String), String]): Seq[Defn] =
    defns.map {
      case d @ Defn.Define(attrs, name @ Global.Member(owner, sig), ty, _, _) if sig.isMethod || sig.isCtor =>
        val id = sig.unmangled match
          case Sig.Method(id, _, _) => id
          case _ => "<init>"
        stubs.get((owner.id, id)) match
          case None => d
          case Some(reason) =>
            given fresh: nir.Fresh = nir.Fresh()
            val params = ty.args.map(t => Val.Local(fresh(), t))
            if reason.startsWith("=identity") then
              Defn.Define(attrs, name, ty, Seq(Inst.Label(fresh(), params), Inst.Ret(params(1))))(using d.pos)
            else
              val exc = Val.Local(fresh(), Type.Ref(UnsupportedOp))
              val message = s"${owner.id}.$id does not run in snc's macros: $reason"
              Defn.Define(attrs, name, ty, Seq(
                Inst.Label(fresh(), params),
                Inst.Let(exc.id, Op.Classalloc(UnsupportedOp, None), Next.None),
                Inst.Let(Op.Call(Type.Function(Seq(Type.Ref(UnsupportedOp), StringT), Type.Unit),
                  Val.Global(UnsupportedOp.member(Sig.Ctor(Seq(StringT))), Type.Ptr), Seq(exc, Val.String(message))), Next.None),
                Inst.Throw(exc, Next.None)))(using d.pos)
      case d => d
    }

  private def write(file: Path, defns: Seq[Defn]): Unit =
    Files.createDirectories(file.getParent)
    val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING)
    try serializeBinary(defns, channel) finally channel.close()

end MacroGlue
