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

/** The macro code that is linked into the native compiler, which cannot load
 *  code. A test build of `snc` that has macro implementations linked in
 *  registers them here before compiling.
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

  /** Whether any macro code is linked in. */
  def nonEmpty: Boolean = registered.nonEmpty

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

  /** The entry for `sym`, or an error that says why there is none. */
  private def find(kind: Char, owner: Symbol, className: String, name: String, arity: Int,
      types: List[String], sym: Symbol, pos: SrcPos)(using Context): Entry =
    val file = owner.associatedFile
    if file != null && !file.ext.isScalaBinary then
      // Defined in the sources of this run: an entry of that name is other code
      throw new StopInterpretation(notFound(owner, sym), pos)
    lookup(kind, className, name, arity) match
      case entry :: Nil => entry
      case Nil => throw new StopInterpretation(notFound(owner, sym), pos)
      case entries =>
        val wanted = if types.isEmpty then "-" else types.mkString(",")
        entries.filter(_.key.types == wanted) match
          case entry :: _ => entry
          case Nil => throw new StopInterpretation(
            em"Cannot run ${sym.showLocated} in a macro: no entry of the macro registry has its parameter types ($wanted), only ${entries.map(_.key.types).mkString("; ")}", pos)

  private def notFound(owner: Symbol, sym: Symbol)(using Context) =
    val file = owner.associatedFile
    val what =
      if sym.is(Method) && !sym.isConstructor then i"the macro implementation ${sym.name} in ${owner.showLocated}"
      else i"${sym.showLocated}"
    if file != null && !file.ext.isScalaBinary then
      em"""Cannot expand this macro: $what is defined in $file, which this compilation run compiles.
          |snc runs only macro code from the class path: compile the macro definitions first, in a separate compilation"""
    else if !MacroRegistry.nonEmpty then
      em"""Macro expansion is not supported by the native compiler yet: it cannot run $what${fromFile(file)}"""
    else
      val scopes = MacroRegistry.scopes
      val scopeHint =
        if scopes.isEmpty then ""
        else
          val active = MacroRegistry.activeScopes
          s"\nIts registry has scopes that SNC_MACRO_SCOPES makes visible; visible now: ${if active.isEmpty then "none" else active.mkString(", ")}"
      em"""Cannot expand this macro: $what${fromFile(file)} is not in the macro registry of this compiler.
          |The native compiler cannot load macro code yet; this snc runs only the macros linked into it$scopeHint"""

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
