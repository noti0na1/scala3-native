package snc.rig

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.collection.mutable

import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir
import scala.scalanative.nir.{Defn, Global, Inst, Next, Op, Sig, Type, Val}
import scala.scalanative.nir.serialization.{deserializeBinary, serializeBinary}
import scala.scalanative.util.Scope

/** Generates the NIR that turns `snc` into a macro test rig: `snc` linked
 *  with macro implementations, which `dotty.tools.dotc.quoted.MacroRegistry`
 *  runs through numbered entries (see README.md, "Testing").
 *
 *  {{{
 *  rig-gen <out> <manifest>         write <out>/nir and <out>/entries.txt
 *  rig-gen --dump <class path> <class>...   print the NIR of classes
 *  }}}
 *
 *  Each line of the manifest describes a scope: `<scope> <prefix> <class
 *  path>`, separated by tabs. `.` names the global scope "", `-` means no
 *  prefix. With a prefix, the scope's NIR is copied to `<out>/nir` with every
 *  class it defines renamed into the package `<prefix>`, so that scopes that
 *  define the same classes (tests in the empty package) can be linked into
 *  one program; without one, its class path is linked as it is. The entries
 *  keep the classes' original names, which are those the compiler knows.
 *
 *  `<out>/nir` gets a class `snc.rig.Glue$<n>` per scope, which extends
 *  `MacroInvokers`, and `snc.rig.Main`, whose `main` registers them and runs
 *  `dotty.tools.dotc.SncMain`. Link it with `snc`'s class path, the class
 *  paths of the scopes without prefix, and `<out>/nir`, with `snc.rig.Main`
 *  as the main class.
 */
object RigGen:

  given nir.SourcePosition = nir.SourcePosition.NoPosition
  given nir.ScopeId = nir.ScopeId.TopLevel

  private val ObjectT = Type.Ref(Global.Top("java.lang.Object"))
  private val StringT = Type.Ref(Global.Top("java.lang.String"))
  private val Invokers = Global.Top("dotty.tools.dotc.quoted.MacroInvokers")
  private val Registry = Global.Top("dotty.tools.dotc.quoted.MacroRegistry$")
  private val SncMain = Global.Top("dotty.tools.dotc.SncMain")
  private val MacroAnnotation = Global.Top("scala.annotation.MacroAnnotation")
  private val InvokeSig = Sig.Method("invoke", Seq(Type.Int, ObjectT, Type.Array(ObjectT), ObjectT)).mangled
  private val RegisterSig = Sig.Method("register", Seq(StringT, StringT, Type.Ref(Invokers), Type.Unit)).mangled
  private val MainSig = Sig.Method("main", Seq(Type.Array(StringT), Type.Unit), Sig.Scope.PublicStatic).mangled

  final case class ScopeSpec(name: String, prefix: Option[String], classpath: Seq[Path])

  /** An entry of a scope: its key, and how to run it. */
  sealed trait Entry:
    def key: String
  final case class ModuleEntry(key: String, module: Global.Top) extends Entry
  final case class NewEntry(key: String, cls: Global.Top, ctor: Global.Member, params: Seq[Type]) extends Entry
  final case class CallEntry(key: String, owner: Global.Top, method: Global.Member, params: Seq[Type],
      result: Type, direct: Boolean) extends Entry

  def main(args: Array[String]): Unit =
    args.toList match
      case "--dump" :: cp :: classes => dump(cp, classes)
      case out :: manifest :: Nil => generate(Paths.get(out), readManifest(Paths.get(manifest)), Map.empty)
      case out :: manifest :: stubs :: Nil =>
        generate(Paths.get(out), readManifest(Paths.get(manifest)), readStubs(Paths.get(stubs)))
      case _ =>
        System.err.println("usage: rig-gen <out> <manifest> [<stubs>] | rig-gen --dump <class path> <class>...")
        sys.exit(2)

  def readManifest(file: Path): Seq[ScopeSpec] =
    scala.io.Source.fromFile(file.toFile).getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
      line.split('\t') match
        case Array(name, prefix, cp) =>
          ScopeSpec(if name == "." then "" else name, if prefix == "-" then None else Some(prefix),
            cp.split(':').filter(_.nonEmpty).map(Paths.get(_)).toSeq)
        case _ => throw new IllegalArgumentException(s"bad manifest line: $line")
    }.toSeq

  /** Methods to replace with stubs, by class and method name, with the
   *  reason: lines `<class> <method> <reason>`, separated by tabs. They are
   *  methods of macro code whose closure does not link, or does not work,
   *  for want of JDK APIs: the stub throws an `UnsupportedOperationException`
   *  only if the macro runs it. A reason that starts with `=identity` makes
   *  the stub return its first argument instead, for a method whose result
   *  is only cosmetic.
   */
  def readStubs(file: Path): Map[(String, String), String] =
    scala.io.Source.fromFile(file.toFile).getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
      line.split('\t') match
        case Array(cls, method, reason) => (cls, method) -> reason
        case Array(cls, method) => (cls, method) -> "it needs an API that is not available"
        case _ => throw new IllegalArgumentException(s"bad stub line: $line")
    }.toMap

  private val UnsupportedOp = Global.Top("java.lang.UnsupportedOperationException")

  /** `defns` with the body of each method named in `stubs` replaced. */
  def stubbed(defns: Seq[Defn], stubs: Map[(String, String), String]): Seq[Defn] =
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
              val message = s"snc macro rig: ${owner.id}.$id is a stub: $reason"
              Defn.Define(attrs, name, ty, Seq(
                Inst.Label(fresh(), params),
                Inst.Let(exc.id, Op.Classalloc(UnsupportedOp, None), Next.None),
                Inst.Let(Op.Call(Type.Function(Seq(Type.Ref(UnsupportedOp), StringT), Type.Unit),
                  Val.Global(UnsupportedOp.member(Sig.Ctor(Seq(StringT))), Type.Ptr), Seq(exc, Val.String(message))), Next.None),
                Inst.Throw(exc, Next.None)))(using d.pos)
      case d => d
    }

  /** The definitions of each NIR file on `classpath`, by file. */
  def readNir(classpath: Seq[Path])(using Scope): Seq[(Path, Seq[Defn])] =
    classpath.filter(p => Files.exists(p)).flatMap { entry =>
      val dir = VirtualDirectory.real(entry)
      dir.files.filter(_.toString.endsWith(".nir")).map(f => (f, deserializeBinary(dir, f)))
    }

  def dump(cp: String, classes: Seq[String]): Unit = Scope { implicit in =>
    val wanted = classes.toSet
    for (file, defns) <- readNir(cp.split(':').map(Paths.get(_)).toSeq) do
      val name = scala.scalanative.io.packageNameFromPath(file)
      if wanted.isEmpty || wanted(name) then
        println(s"// $file")
        defns.foreach(d => println(d.show))
  }

  def generate(out: Path, scopes: Seq[ScopeSpec], stubs: Map[(String, String), String]): Unit = Scope { implicit in =>
    val nirOut = out.resolve("nir")
    val report = new StringBuilder
    var annotationTransform: Option[CallEntry] = None
    val glues = scopes.zipWithIndex.map { (spec, i) =>
      val files = readNir(spec.classpath)
      val defns = files.flatMap(_._2)
      val defined = defns.map(_.name.top).toSet
      val rename = new Rename(spec.prefix, defined.map(_.id))
      val stubbedClasses = stubs.keySet.map(_._1)
      for (file, fileDefns) <- files do
        val hasStubs = fileDefns.exists(d => stubbedClasses(d.name.top.id))
        if spec.prefix.isDefined || hasStubs then
          val defns1 = if hasStubs then stubbed(fileDefns, stubs) else fileDefns
          val renamed = defns1.map(rename.onDefn)
          val dir = spec.prefix.fold(nirOut)(p => nirOut.resolve(p.replace('.', '/')))
          write(dir.resolve(file.toString.stripPrefix("/")), renamed)
      val entries = findEntries(defns, rename)
      for d <- defns do d match
        case d: Defn.Define if annotationTransform.isEmpty && isAnnotationTransform(d, defns) =>
          val Sig.Method(_, types, _) = d.name.sig.unmangled: @unchecked
          val params = types.init
          annotationTransform = Some(CallEntry(
            s"C ${MacroAnnotation.id} transform ${params.size} ${descriptors(params)}",
            MacroAnnotation, MacroAnnotation.member(d.name.sig), params, types.last, direct = false))
        case _ =>
      report ++= s"# scope '${spec.name}' (${entries.size} entries, ${defined.size} classes)\n"
      entries.foreach(e => report ++= e.key += '\n')
      (spec.name, entries)
    }
    // MacroAnnotation.transform is called through the trait, in the global scope
    val allGlues = annotationTransform match
      case Some(e) =>
        report ++= s"# scope '' (MacroAnnotation)\n${e.key}\n"
        glues :+ ("", Seq(e))
      case None => glues
    val glueNames = allGlues.zipWithIndex.map { case ((scope, entries), i) =>
      val name = Global.Top(s"snc.rig.Glue$$$i")
      write(nirOut.resolve(s"snc/rig/Glue$$$i.nir"), glueClass(name, entries))
      (scope, entries.map(_.key).mkString("\n"), name)
    }
    write(nirOut.resolve("snc/rig/Main.nir"), mainClass(glueNames))
    Files.createDirectories(out)
    Files.write(out.resolve("entries.txt"), report.toString.getBytes("UTF-8"))
  }

  private def write(file: Path, defns: Seq[Defn]): Unit =
    Files.createDirectories(file.getParent)
    val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING)
    try serializeBinary(defns, channel) finally channel.close()

  /** Does `sig` mention a type of `scala.quoted`? */
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

  /** The entries of a scope: its modules, the methods that mention a
   *  `scala.quoted` type (macro implementations take a `Quotes` or return
   *  an `Expr`), and the constructors of its macro annotations.
   */
  private def findEntries(defns: Seq[Defn], rename: Rename): Seq[Entry] =
    val parents = hierarchy(defns)
    val modules = defns.collect { case m: Defn.Module => m.name }.toSet
    val entries = mutable.ArrayBuffer.empty[Entry]
    for d <- defns do d match
      case Defn.Define(_, name @ Global.Member(owner, sig), _, _, _) =>
        sig.unmangled match
          case Sig.Method(id, types, scope) if !scope.isStatic && !id.contains("$default$") && id != "writeReplace" =>
            val params = types.init
            // A module's val of a context function type (`Quotes ?=> Expr[T]`)
            val isGetter = modules(owner) && params.isEmpty &&
              types.last == Type.Ref(Global.Top("scala.Function1"))
            if mentionsQuoted(types) || isGetter then
              entries += CallEntry(s"C ${owner.id} $id ${params.size} ${descriptors(params)}",
                rename.top(owner), rename.member(name), params.map(rename.onType), rename.onType(types.last),
                direct = scope.privateIn.isDefined || modules(owner))
          case Sig.Ctor(types) if derivesFrom(owner, MacroAnnotation, parents) =>
            entries += NewEntry(s"N ${owner.id} ${types.size} ${descriptors(types)}",
              rename.top(owner), rename.member(name), types.map(rename.onType))
          case _ =>
      case _ =>
    // The modules that declare or inherit a method with an entry, which the
    // executor loads to call it
    val owners = entries.collect { case e: CallEntry => e.key.split(' ')(1) }.toSet
    def hasEntries(cls: Global.Top): Boolean =
      owners(cls.id) || parents.getOrElse(cls, Nil).exists(hasEntries)
    val moduleEntries = modules.toSeq.sortBy(_.id).filter(hasEntries)
      .map(m => ModuleEntry(s"M ${m.id}", rename.top(m)))
    (moduleEntries ++ entries).distinctBy(_.key)

  private def descriptors(types: Seq[Type]): String =
    if types.isEmpty then "-" else types.map(descriptor).mkString(",")

  /** A type as in the registry's keys: a JVM descriptor, with dots. */
  def descriptor(t: Type): String = t match
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

  /** `snc.rig.Glue$<n>`: `invoke(index, receiver, args)` runs entry `index`. */
  private def glueClass(name: Global.Top, entries: Seq[Entry]): Seq[Defn] =
    val self = Type.Ref(name)
    val ctor =
      given fresh: nir.Fresh = nir.Fresh()
      val thisv = Val.Local(fresh(), self)
      Defn.Define(nir.Attrs.None, name.member(Sig.Ctor(Seq())), Type.Function(Seq(self), Type.Unit), Seq(
        Inst.Label(fresh(), Seq(thisv)),
        Inst.Let(Op.Call(Type.Function(Seq(Type.Ref(Invokers)), Type.Unit),
          Val.Global(Invokers.member(Sig.Ctor(Seq())), Type.Ptr), Seq(thisv)), Next.None),
        Inst.Ret(Val.Unit)))
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
      for (entry, label) <- entries.zip(labels) do
        insts += Inst.Label(label, Seq())
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
      insts += Inst.Label(default, Seq())
      insts += Inst.Ret(Val.Null)
      Defn.Define(nir.Attrs.None, name.member(InvokeSig),
        Type.Function(Seq(self, Type.Int, ObjectT, Type.Array(ObjectT)), ObjectT), insts.toSeq)
    Seq(Defn.Class(nir.Attrs.None, name, Some(Invokers), Seq()), ctor, invoke)

  /** `snc.rig.Main`: registers the glue, then runs `SncMain`. */
  private def mainClass(glues: Seq[(String, String, Global.Top)]): Seq[Defn] =
    val name = Global.Top("snc.rig.Main")
    given fresh: nir.Fresh = nir.Fresh()
    val argv = Val.Local(fresh(), Type.Array(StringT))
    val insts = mutable.ArrayBuffer.empty[Inst]
    def let(op: Op): Val =
      val id = fresh()
      insts += Inst.Let(id, op, Next.None)
      Val.Local(id, op.resty)
    insts += Inst.Label(fresh(), Seq(argv))
    for (scope, keys, glue) <- glues do
      val g = let(Op.Classalloc(glue, None))
      let(Op.Call(Type.Function(Seq(Type.Ref(glue)), Type.Unit), Val.Global(glue.member(Sig.Ctor(Seq())), Type.Ptr), Seq(g)))
      val registry = let(Op.Module(Registry))
      let(Op.Call(Type.Function(Seq(Type.Ref(Registry), StringT, StringT, Type.Ref(Invokers)), Type.Unit),
        Val.Global(Registry.member(RegisterSig), Type.Ptr), Seq(registry, Val.String(scope), Val.String(keys), g)))
    let(Op.Call(Type.Function(Seq(Type.Array(StringT)), Type.Unit), Val.Global(SncMain.member(MainSig), Type.Ptr), Seq(argv)))
    insts += Inst.Ret(Val.Unit)
    Seq(
      Defn.Class(nir.Attrs.None, name, Some(ObjectT.name), Seq()),
      Defn.Define(nir.Attrs.None, name.member(MainSig), Type.Function(Seq(Type.Array(StringT)), Type.Unit), insts.toSeq))

  /** Renames the classes `defined` into the package `prefix`, everywhere. */
  final class Rename(prefix: Option[String], defined: Set[String]) extends nir.Transform:
    private val sigs = mutable.HashMap.empty[Sig, Sig]

    def top(g: Global.Top): Global.Top = prefix match
      case Some(p) if defined(g.id) => Global.Top(s"$p.${g.id}")
      case _ => g

    def global(g: Global): Global = g match
      case t: Global.Top => top(t)
      case m: Global.Member => member(m)
      case _ => g

    def member(m: Global.Member): Global.Member = Global.Member(top(m.owner), sig(m.sig))

    def sig(s: Sig): Sig =
      if prefix.isEmpty then s
      else sigs.getOrElseUpdate(s, s.unmangled match
        case Sig.Method(id, types, scope) => Sig.Method(id, types.map(onType), sigScope(scope)).mangled
        case Sig.Field(id, scope) => Sig.Field(id, sigScope(scope)).mangled
        case Sig.Ctor(types) => Sig.Ctor(types.map(onType)).mangled
        case Sig.Proxy(id, types) => Sig.Proxy(id, types.map(onType)).mangled
        case Sig.Duplicate(of, types) => Sig.Duplicate(sig(of), types.map(onType)).mangled
        case _ => s)

    private def sigScope(s: Sig.Scope): Sig.Scope = s match
      case Sig.Scope.Private(in) => Sig.Scope.Private(global(in))
      case Sig.Scope.PrivateStatic(in) => Sig.Scope.PrivateStatic(global(in))
      case _ => s

    override def onDefn(defn: Defn): Defn =
      if prefix.isEmpty then defn
      else
        given nir.SourcePosition = defn.pos
        super.onDefn(defn) match
          case d: Defn.Var => d.copy(name = member(d.name))
          case d: Defn.Const => d.copy(name = member(d.name))
          case d: Defn.Declare => d.copy(name = member(d.name))
          case d: Defn.Define => d.copy(name = member(d.name))
          case d: Defn.Trait => d.copy(name = top(d.name), traits = d.traits.map(top))
          case d: Defn.Class => d.copy(name = top(d.name), parent = d.parent.map(top), traits = d.traits.map(top))
          case d: Defn.Module => d.copy(name = top(d.name), parent = d.parent.map(top), traits = d.traits.map(top))

    override def onInst(inst: Inst): Inst = inst match
      case i @ Inst.LinktimeIf(cond, thenp, elsep) =>
        given nir.SourcePosition = i.pos
        Inst.LinktimeIf(cond, onNext(thenp), onNext(elsep))
      case _ => super.onInst(inst)

    override def onOp(op: Op): Op = op match
      case Op.Classalloc(n, zone) => Op.Classalloc(top(n), zone.map(onVal))
      case Op.Fieldload(ty, v, n) => Op.Fieldload(onType(ty), onVal(v), member(n))
      case Op.Fieldstore(ty, v1, n, v2) => Op.Fieldstore(onType(ty), onVal(v1), member(n), onVal(v2))
      case Op.Field(v, n) => Op.Field(onVal(v), member(n))
      case Op.Method(v, s) => Op.Method(onVal(v), sig(s))
      case Op.Dynmethod(v, s) => Op.Dynmethod(onVal(v), sig(s))
      case Op.Module(n) => Op.Module(top(n))
      case Op.Box(ty, v) => Op.Box(onType(ty), onVal(v))
      case Op.Unbox(ty, v) => Op.Unbox(onType(ty), onVal(v))
      case _ => super.onOp(op)

    override def onVal(value: Val): Val = value match
      case Val.Global(n, ty) => Val.Global(global(n), onType(ty))
      case Val.ClassOf(n) => Val.ClassOf(top(n))
      case _ => super.onVal(value)

    override def onType(ty: Type): Type = ty match
      case Type.Ref(n, exact, nullable) => Type.Ref(top(n), exact, nullable)
      case _ => super.onType(ty)
  end Rename

end RigGen
