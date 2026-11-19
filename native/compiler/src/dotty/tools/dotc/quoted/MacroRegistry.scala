package dotty.tools.dotc
package quoted

import scala.collection.mutable

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.util.SrcPos
import dotty.tools.io.AbstractFile

import Interpreter.StopInterpretation

/** Compiled macro code that is linked into the native compiler, as numbered
 *  entries. Code that registers them with `MacroRegistry` says what each
 *  entry does.
 */
abstract class MacroInvokers:
  /** Runs entry `index`: `receiver` is the object that a method is called on
   *  (null for other entries), and `args` are the arguments.
   */
  def invoke(index: Int, receiver: Object, args: Array[Object]): Object

/** Makes macro code available to `MacroRegistry` when an expansion first needs
 *  it, in a compiler that can link and load code: `snc-host` installs one,
 *  which links the class path entry that holds the code as an extension of
 *  the running compiler, loads it and registers its entries.
 */
trait MacroLoader:
  /** Registers the entries of the macro code in `owner`'s class path entry
   *  (a jar or directory, or the output directory for a class compiled by an
   *  earlier run), unless they are registered already, and returns whether it
   *  registered any. Throws `StopInterpretation` at `pos` if the code does not
   *  link or load.
   */
  def load(owner: Symbol, pos: SrcPos)(using Context): Boolean

/** The macro code that the native compiler runs: code linked into it, which
 *  a main class registers before compiling (the macro test rig), and code that
 *  a `MacroLoader` links and loads when an expansion needs it (`snc-host`).
 *
 *  Entries are registered in scopes. The entries of the scope "" are always
 *  visible; those of other scopes only when the environment variable
 *  `SNC_MACRO_SCOPES` lists the scope (comma-separated), so that several sets
 *  of macros that define the same classes can be linked into one compiler.
 */
object MacroRegistry:

  /** The key of an entry: one of
   *
   *  - `M <class>`: the instance of the module class `<class>`;
   *  - `N <class> <arity> <types>`: a new instance of `<class>`, from its
   *    constructor with these parameters;
   *  - `C <class> <name> <arity> <types>`: a call of method `<name>`, which
   *    `<class>` declares, with these parameters, on the receiver.
   *
   *  `<class>` is a class's binary name, `<name>` a method's mangled name,
   *  and `<types>` the erased parameter types, comma-separated, each in the
   *  form of a JVM descriptor with dots (`I`, `Lscala.quoted.Expr;`,
   *  `[Ljava.lang.String;`), or `-` for none.
   */
  final case class Key(kind: Char, className: String, name: String, arity: Int, types: String):
    def show: String = kind match
      case 'M' => s"M $className"
      case 'N' => s"N $className $arity $types"
      case _ => s"C $className $name $arity $types"

  object Key:
    def parse(line: String): Key =
      line.split(' ') match
        case Array("M", cls) => Key('M', cls, "", 0, "-")
        case Array("N", cls, arity, types) => Key('N', cls, "<init>", arity.toInt, types)
        case Array("C", cls, name, arity, types) => Key('C', cls, name, arity.toInt, types)
        case _ => throw new IllegalArgumentException(s"Bad macro registry key: $line")

  /** An entry: entry `index` of `invokers`. */
  final case class Entry(scope: String, key: Key, invokers: MacroInvokers, index: Int):
    def invoke(receiver: Object | Null, args: List[Object]): Object =
      invokers.invoke(index, receiver.asInstanceOf[Object], args.toArray)

  private val registered = mutable.ArrayBuffer.empty[(String, String, MacroInvokers)]

  /** Registers the entries of `invokers` in `scope`: `keys` has one key per
   *  line, and line `i` (from 0) is the key of entry `i`.
   */
  def register(scope: String, keys: String, invokers: MacroInvokers): Unit =
    registered += ((scope, keys, invokers))
    indexed = null

  /** Whether any macro code is linked in or loaded. */
  def nonEmpty: Boolean = registered.nonEmpty

  @volatile private var installedLoader: MacroLoader | Null = null

  /** Makes `loader` load the macro code that expansions need. */
  def installLoader(loader: MacroLoader): Unit = installedLoader = loader

  /** The loader of macro code, if this compiler can load code. */
  def loader: MacroLoader | Null = installedLoader

  /** The scopes that are registered, other than "". */
  def scopes: List[String] = registered.map(_._1).filter(_.nonEmpty).distinct.toList

  /** The scopes listed in `SNC_MACRO_SCOPES`. */
  def activeScopes: Set[String] =
    sys.env.get("SNC_MACRO_SCOPES").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)

  private var indexed: mutable.HashMap[(Char, String, String, Int), List[Entry]] | Null = null

  /** The visible entries for kind, class, name and arity. */
  def lookup(kind: Char, className: String, name: String, arity: Int): List[Entry] =
    if indexed == null then
      val active = activeScopes + ""
      val map = mutable.HashMap.empty[(Char, String, String, Int), List[Entry]]
      for (scope, keys, invokers) <- registered if active(scope) do
        for (line, index) <- keys.linesIterator.zipWithIndex if line.nonEmpty do
          val key = Key.parse(line)
          val id = (key.kind, key.className, key.name, key.arity)
          map(id) = Entry(scope, key, invokers, index) :: map.getOrElse(id, Nil)
      indexed = map
    indexed.nn.getOrElse((kind, className, name, arity), Nil)

end MacroRegistry

/** The executor of the macro code in `MacroRegistry`. Exceptions thrown by
 *  the macro code propagate as they are.
 */
class RegistryMacroExecutor extends MacroExecutor:
  import MacroRegistry.*

  def loadModule(moduleClass: Symbol, pos: SrcPos)(using Context): Object =
    find('M', moduleClass, moduleClass.binaryClassName, "", 0, Nil, moduleClass, pos)
      .invoke(null, Nil)

  def callMethod(receiver: Object, method: Symbol, args: List[Object], pos: SrcPos)(using Context): Object =
    val owner = method.owner
    find('C', owner, owner.binaryClassName, method.name.mangledString, args.length, paramTypes(method), method, pos)
      .invoke(receiver, args)

  def newInstance(constructor: Symbol, args: List[Object], pos: SrcPos)(using Context): Object =
    val cls = constructor.owner
    find('N', cls, cls.binaryClassName, "<init>", args.length, paramTypes(constructor), constructor, pos)
      .invoke(null, args)

  /** A `val` of a module, through its getter: the native compiler has no
   *  static fields of Scala code.
   */
  def staticField(field: Symbol, pos: SrcPos)(using Context): Object =
    val owner = field.owner
    if !owner.is(ModuleClass) then
      throw new StopInterpretation(em"Cannot read the static field ${field.showLocated} in a macro: the native compiler can only run Scala code", pos)
    val module = loadModule(owner, pos)
    find('C', owner, owner.binaryClassName, field.name.mangledString, 0, Nil, field, pos)
      .invoke(module, Nil)

  /** The entry for `sym`, or an error that says why there is none. With a
   *  `MacroLoader`, a miss loads the macro code of `owner`'s class path
   *  entry, and code that this run compiles suspends the compilation unit
   *  until the run is over, as on the JVM.
   */
  private def find(kind: Char, owner: Symbol, className: String, name: String, arity: Int,
      types: List[String], sym: Symbol, pos: SrcPos)(using Context): Entry =
    val file = owner.associatedFile
    val loader = MacroRegistry.loader
    if file != null && !file.ext.isScalaBinary && (loader == null || owner.isDefinedInCurrentRun) then
      // Defined in the sources of this run: its code exists only once the
      // run is over, so an entry with that name belongs to other code
      if loader != null && ctx.compilationUnit.isSuspendable then
        Interpreter.suspendOnMissing(owner, Interpreter.ClassOrigin.Source, pos)
      if loader != null && kind != 'N' && ctx.reporter.errorsReported then
        // A unit that cannot be suspended (a REPL line): the inliner has
        // already reported the macro as defined in the same source or run
        throw new scala.quoted.runtime.StopMacroExpansion
      throw new StopInterpretation(notFound(owner, sym), pos)
    def select(entries: List[Entry]): Option[Entry] = entries match
      case Nil => None
      case entry :: Nil => Some(entry)
      case entries =>
        val wanted = if types.isEmpty then "-" else types.mkString(",")
        entries.filter(_.key.types == wanted) match
          case entry :: _ => Some(entry)
          case Nil => throw new StopInterpretation(
            em"Cannot run ${sym.showLocated} in a macro: no entry of the macro registry has its parameter types ($wanted), only ${entries.map(_.key.types).mkString("; ")}", pos)
    select(lookup(kind, className, name, arity))
      .orElse:
        if loader != null && loader.load(owner, pos) then select(lookup(kind, className, name, arity))
        else None
      .getOrElse(throw new StopInterpretation(notFound(owner, sym), pos))

  private def notFound(owner: Symbol, sym: Symbol)(using Context) =
    val file = owner.associatedFile
    val what =
      if sym.is(Method) && !sym.isConstructor then i"the macro implementation ${sym.name} in ${owner.showLocated}"
      else i"${sym.showLocated}"
    val loads = MacroRegistry.loader != null
    if file != null && !file.ext.isScalaBinary then
      if loads then
        em"""Cannot expand this macro: $what is defined in $file, which this compilation run compiles,
            |and its code runs only once the run is over""" // the unit could not be suspended
      else
        em"""Cannot expand this macro: $what is defined in $file, which this compilation run compiles.
            |This snc runs only macro code from the class path: compile the macro definitions first, in a separate compilation, or use snc-host, which links and loads macro code"""
    else if loads then
      em"""Cannot expand this macro: $what${fromFile(file)} has no macro entry in its compiled code.
          |snc-host runs macro code compiled to NIR by Scala Native's compiler plugin: the NIR must be next to the TASTy"""
    else if !MacroRegistry.nonEmpty then
      em"""Macro expansion is not supported by this snc: it cannot run $what${fromFile(file)}.
          |snc-host, the same compiler linked as a live-execution host, links and loads macro code"""
    else
      val scopes = MacroRegistry.scopes
      val scopeHint =
        if scopes.isEmpty then ""
        else
          val active = MacroRegistry.activeScopes
          s"\nIts registry has scopes that SNC_MACRO_SCOPES makes visible; visible now: ${if active.isEmpty then "none" else active.mkString(", ")}"
      em"""Cannot expand this macro: $what${fromFile(file)} is not in the macro registry of this compiler.
          |This snc runs only the macros linked into it$scopeHint"""

  private def fromFile(file: AbstractFile | Null): String =
    if file == null then "" else s" from $file"

  /** The erased parameter types of `sym`, as in registry keys, including the
   *  parameters of a context function result type.
   */
  private def paramTypes(sym: Symbol)(using Context): List[String] =
    def extraParams(tp: Type): List[Type] = tp.widenDealias match
      case tp: AppliedType if defn.isContextFunctionType(tp) =>
        tp.args.init.map(arg => TypeErasure.erasure(arg)) ::: extraParams(tp.args.last)
      case _ => Nil
    val params = TypeErasure.erasure(sym.info) match
      case meth: MethodType => meth.paramInfos
      case _ => Nil
    (params ::: extraParams(sym.info.finalResultType)).map(descriptor)

  private def descriptor(tp: Type)(using Context): String = tp match
    case JavaArrayType(elem) => "[" + descriptor(elem)
    case _ =>
      val cls = tp.classSymbol
      if cls == defn.BooleanClass then "Z"
      else if cls == defn.ByteClass then "B"
      else if cls == defn.CharClass then "C"
      else if cls == defn.ShortClass then "S"
      else if cls == defn.IntClass then "I"
      else if cls == defn.LongClass then "J"
      else if cls == defn.FloatClass then "F"
      else if cls == defn.DoubleClass then "D"
      else if cls == defn.UnitClass then "V"
      else if cls == defn.AnyClass || cls == defn.ObjectClass || cls == defn.AnyValClass || !cls.exists then "Ljava.lang.Object;"
      else if cls == defn.NothingClass then "Lscala.runtime.Nothing$;"
      else if cls == defn.NullClass then "Lscala.runtime.Null$;"
      else "L" + cls.binaryClassName + ";"

end RegistryMacroExecutor
