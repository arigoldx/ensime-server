// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.model

import scala.collection.mutable
import scala.reflect.internal.util.{ NoPosition, Position, RangePosition }
import org.ensime.api
import org.ensime.api._
import org.ensime.core.{ FqnToSymbol, RichPresentationCompiler }
import org.ensime.indexer.{ MethodName, PackageName }
import org.ensime.indexer.database.DatabaseService._
import org.ensime.vfs._
import org.ensime.util.ensimefile._

trait ModelBuilders {
  self: RichPresentationCompiler with FqnToSymbol =>

  import rootMirror.RootPackage

  def locateSymbolPos(sym: Symbol, needPos: PosNeeded): Option[SourcePosition] = {
    _locateSymbolPos(sym, needPos).orElse({
      sym.companionSymbol match {
        case NoSymbol => None
        case s: Symbol => _locateSymbolPos(s, needPos)
      }
    })
  }

  def _locateSymbolPos(sym: Symbol, needPos: PosNeeded): Option[SourcePosition] = {
    if (sym == NoSymbol || needPos == PosNeededNo)
      None
    else if (sym.pos != NoPosition) {
      if (needPos == PosNeededYes || needPos == PosNeededAvail) {
        OffsetSourcePositionHelper.fromPosition(sym.pos)
      } else
        Some(EmptySourcePosition())
    } else {
      // only perform operations is actively requested - this is comparatively expensive
      if (needPos == PosNeededYes) {
        val fqn = toFqn(sym).fqnString
        logger.debug(s"$sym ==> $fqn")

        val hit = search.findUnique(fqn)
        logger.debug(s"search: $fqn = $hit")
        hit.flatMap(LineSourcePositionHelper.fromFqnSymbol(_)(config, vfs)).flatMap { sourcePos =>
          if (sourcePos.file.isScala)
            askLinkPos(sym, sourcePos.file).
              flatMap(pos => OffsetSourcePositionHelper.fromPosition(pos))
          else
            Some(sourcePos)
        }
      } else
        None
    }
  }

  // When inspecting a type, transform a raw list of TypeMembers to a sorted
  // list of InterfaceInfo objects, each with its own list of sorted member infos.
  def prepareSortedInterfaceInfo(members: Iterable[Member], parents: Iterable[Type]): Iterable[InterfaceInfo] = {
    // ...filtering out non-visible and non-type members
    val visMembers: Iterable[TypeMember] = members.flatMap {
      case m @ TypeMember(sym, tpe, true, _, _) => List(m)
      case _ => List.empty
    }

    val parentMap = parents.map(_.typeSymbol -> List[TypeMember]()).toMap
    val membersMap = visMembers.groupBy {
      case TypeMember(sym, _, _, _, _) => sym.owner
    }
    // Create a list of pairs [(typeSym, membersOfSym)]
    val membersByOwner = (parentMap ++ membersMap).toList.sortWith {
      // Sort the pairs on the subtype relation
      case ((s1, _), (s2, _)) => s1.tpe <:< s2.tpe
    }

    membersByOwner.map {
      case (ownerSym, members) =>

        // If all the members in this interface were
        // provided by the same view, remember that
        // view for later display to user.
        val byView = members.groupBy(_.viaView)
        val viaView = if (byView.size == 1) {
          byView.keys.headOption.filter(_ != NoSymbol)
        } else { None }

        // Do one top level sort by name on members, before
        // subdividing into kinds of members.
        val sortedMembers = members.toList.sortWith { (a, b) =>
          a.sym.nameString <= b.sym.nameString
        }

        // Convert type members into NamedTypeMemberInfos
        // and divide into different kinds..

        val nestedTypes = new mutable.ArrayBuffer[NamedTypeMemberInfo]()
        val constructors = new mutable.ArrayBuffer[NamedTypeMemberInfo]()
        val fields = new mutable.ArrayBuffer[NamedTypeMemberInfo]()
        val methods = new mutable.ArrayBuffer[NamedTypeMemberInfo]()

        for (tm <- sortedMembers) {
          val info = NamedTypeMemberInfo(tm)
          val decl = info.declAs
          if (decl == DeclaredAs.Method) {
            if (info.name == "this") {
              constructors += info
            } else {
              methods += info
            }
          } else if (decl == DeclaredAs.Field) {
            fields += info
          } else if (decl == DeclaredAs.Class || decl == DeclaredAs.Trait ||
            decl == DeclaredAs.Interface || decl == DeclaredAs.Object) {
            nestedTypes += info
          }
        }

        val sortedInfos = nestedTypes ++ fields ++ constructors ++ methods

        new InterfaceInfo(TypeInfo(ownerSym.tpe, PosNeededAvail, sortedInfos), viaView.map(_.name.toString))
    }
  }

  object PackageInfo {
    def root: PackageInfo = fromSymbol(RootPackage)

    def fromPath(path: String): PackageInfo =
      toSymbol(PackageName(path.split('.').toList)) match {
        case NoSymbol => nullInfo
        case packSym => fromSymbol(packSym)
      }

    val nullInfo = new PackageInfo("NA", "NA", List.empty)

    private def sortedMembers(items: Iterable[EntityInfo]) = {
      items.toList.sortBy(_.name)
    }

    def fromSymbol(sym: Symbol): PackageInfo = {
      val members = sortedMembers(packageMembers(sym).flatMap(packageMemberInfoFromSym))
      if (sym.isRoot || sym.isRootPackage) {
        new PackageInfo("root", "_root_", members)
      } else {
        new PackageInfo(sym.name.toString, sym.fullName, members)
      }
    }

    def packageMemberInfoFromSym(sym: Symbol): Option[EntityInfo] = {
      try {
        if (sym == RootPackage) {
          Some(root)
        } else if (sym.hasPackageFlag) {
          Some(fromSymbol(sym))
        } else if (!sym.nameString.contains("$") && (sym != NoSymbol) && (sym.tpe != NoType)) {
          if (sym.isClass || sym.isTrait || sym.isModule ||
            sym.isModuleClass || sym.isPackageClass) {
            Some(TypeInfo(sym.tpe, PosNeededAvail))
          } else {
            None
          }
        } else {
          None
        }
      } catch {
        case e: Throwable => None
      }
    }
  }

  object TypeInfo {

    // use needPos=PosNeededYes sparingly as it potentially causes lots of I/O
    def apply(typ: Type, needPos: PosNeeded = PosNeededNo, members: Iterable[EntityInfo] = List.empty): TypeInfo = {
      val tpe = typ match {
        case et: ExistentialType => et.underlying
        case t => t
      }
      def basicTypeInfo(tpe: Type): BasicTypeInfo = {
        val typeSym = tpe.typeSymbol
        val symbolToLocate = if (typeSym.isModuleClass) typeSym.sourceModule else typeSym
        val symPos = locateSymbolPos(symbolToLocate, needPos)
        api.BasicTypeInfo(
          shortName(tpe).underlying,
          declaredAs(typeSym),
          fullName(tpe).underlying,
          tpe.typeArgs.map(TypeInfo(_)),
          members,
          symPos,
          Nil
        )
      }
      tpe match {
        case arrow if isArrowType(arrow) => ArrowTypeInfoBuilder(tpe)
        case tpe: NullaryMethodType => basicTypeInfo(tpe.resultType)
        case tpe: Type => basicTypeInfo(tpe)
        case _ => nullInfo
      }
    }

    def nullInfo = BasicTypeInfo("NA", DeclaredAs.Nil, "NA")
  }

  object ParamSectionInfoBuilder {
    def apply(params: Iterable[Symbol]): ParamSectionInfo = {
      new ParamSectionInfo(
        params.map { s => (s.nameString, TypeInfo(s.tpe)) },
        params.exists(_.isImplicit)
      )
    }
  }

  object SymbolInfo {

    def apply(sym: Symbol): SymbolInfo = {
      val tpe = askOption(sym.tpe) match {
        case None => NoType
        case Some(t) => t
      }
      val nameString = sym.nameString
      val (name, localName) = if (sym.isClass || sym.isTrait || sym.isModule ||
        sym.isModuleClass || sym.isPackageClass) {
        (fullName(tpe).underlying, nameString)
      } else {
        (nameString, nameString)
      }
      val ownerTpe = if (sym.owner != NoSymbol && sym.owner.tpe != NoType) {
        Some(sym.owner.tpe)
      } else None
      new SymbolInfo(
        name,
        localName,
        locateSymbolPos(sym, PosNeededYes),
        TypeInfo(tpe, PosNeededAvail)
      )
    }
  }

  object CompletionInfoBuilder {
    def fromSymbol(sym: Symbol, relevance: Int): CompletionInfo =
      fromSymbolAndType(sym, sym.tpe, relevance)

    def fromSymbolAndType(sym: Symbol, tpe: Type, relevance: Int): CompletionInfo = {
      val typeInfo = TypeInfo(tpe)
      CompletionInfo(
        Some(typeInfo),
        sym.nameString,
        relevance,
        None,
        isInfix(sym.nameString, typeInfo)
      )
    }

    private def isInfix(symName: String, typeInfo: TypeInfo): Boolean = {

      def isEligibleForInfix(symName: String, paramSections: Iterable[ParamSectionInfo]) = {
        import InfixChecker._

        if (hasWrongParametersSet(paramSections)) false
        else if (isSymbolString(symName)) true
        else !(isSymbolNameTooLong(symName) || isExcluded(symName))
      }

      typeInfo match {
        case ArrowTypeInfo(_, _, _, paramSections, _) => isEligibleForInfix(symName, paramSections)
        case _ => false
      }
    }

    /*
     * The boolean logic in this object is reversed in order to do as few checks as possible
     */
    private object InfixChecker {
      val MAX_NAME_LENGTH_FOR_INFIX = 3
      val PARAMETER_SETS_NUM_FOR_INFIX = 1
      val PARAMETER_SETS_NUM_FOR_INFIX_WITH_IMPLICIT = 2
      val PARAMETER_LIST_SIZE_FOR_INFIX = 1
      val EXCLUDED = Set("map")

      def isSymbolString(s: String) = s.forall(isSymbol)
      private def isSymbol(c: Char) = !Character.isLetterOrDigit(c)

      def hasWrongParametersSet(paramSections: Iterable[ParamSectionInfo]) =
        isWrongParametersSetNumber(paramSections) ||
          isFirstParameterSetImplicit(paramSections) ||
          hasWrongArity(paramSections)
      private def isWrongParametersSetNumber(paramSections: Iterable[ParamSectionInfo]) =
        !(paramSections.size == PARAMETER_SETS_NUM_FOR_INFIX
          || (paramSections.size == PARAMETER_SETS_NUM_FOR_INFIX_WITH_IMPLICIT
            && paramSections.tail.head.isImplicit))
      private def isFirstParameterSetImplicit(paramSections: Iterable[ParamSectionInfo]) = paramSections.head.isImplicit
      private def hasWrongArity(paramSections: Iterable[ParamSectionInfo]) = paramSections.head.params.size != PARAMETER_LIST_SIZE_FOR_INFIX

      def isExcluded(symbolName: String) = EXCLUDED.contains(symbolName)

      def isSymbolNameTooLong(symbolName: String) = symbolName.length > MAX_NAME_LENGTH_FOR_INFIX
    }
  }

  object NamedTypeMemberInfo {
    def apply(m: TypeMember): NamedTypeMemberInfo = {
      val decl = declaredAs(m.sym)
      val pos = if (m.sym.pos == NoPosition) None else Some(EmptySourcePosition())
      val descriptor = toFqn(m.sym) match {
        case MethodName(_, _, desc) => Some(desc.descriptorString)
        case _ => None
      }
      new NamedTypeMemberInfo(m.sym.nameString, TypeInfo(m.tpe), pos, descriptor, decl)
    }
  }

  object ArrowTypeInfoBuilder {
    def apply(tpe: Type): ArrowTypeInfo = {
      tpe match {
        case args: ArgsTypeRef if args.typeSymbol.fullName.startsWith("scala.Function") =>
          val tparams = args.args
          val result = TypeInfo(tparams.last)
          val params =
            if (tparams.isEmpty) Nil
            else tparams.init.zipWithIndex.map { case (tpe, idx) => ("_" + idx, TypeInfo(tpe)) }
          ArrowTypeInfo(shortName(tpe).underlying, fullName(tpe).underlying, result, ParamSectionInfo(params, isImplicit = false) :: Nil, Nil)

        case TypeRef(_, definitions.ByNameParamClass, args) =>
          val result = TypeInfo(args.head)
          ArrowTypeInfo(shortName(tpe).underlying, fullName(tpe).underlying, result, Nil, Nil)

        case _: MethodType | _: PolyType =>
          new ArrowTypeInfo(
            shortName(tpe).underlying,
            fullName(tpe).underlying,
            TypeInfo(tpe.finalResultType),
            tpe.paramss.map(ParamSectionInfoBuilder.apply),
            tpe.typeParams.map(tp => TypeInfo.apply(tp.tpe))
          )
        case _ => nullInfo()
      }
    }

    def nullInfo() = {
      new ArrowTypeInfo("NA", "NA", TypeInfo.nullInfo, List.empty, Nil)
    }
  }
}

object LineSourcePositionHelper {

  def fromFqnSymbol(sym: FqnSymbol)(implicit config: EnsimeConfig, vfs: EnsimeVFS): Option[LineSourcePosition] =
    (sym.sourceFileObject, sym.line, sym.offset) match {
      case (None, _, _) => None
      case (Some(fo), lineOpt, offsetOpt) =>
        val file = EnsimeFile(fo.getURL)
        Some(new LineSourcePosition(file, lineOpt.getOrElse(0)))
    }

}

object OffsetSourcePositionHelper {
  def fromPosition(p: Position): Option[OffsetSourcePosition] = p match {
    case NoPosition => None
    case realPos =>
      Some(new OffsetSourcePosition(EnsimeFile(realPos.source.file.path), realPos.point))
  }
}

object ERangePositionHelper {
  def fromRangePosition(rp: RangePosition): ERangePosition = new ERangePosition(rp.source.path, rp.point, rp.start, rp.end)
}

object BasicTypeInfo {
  def apply(name: String, declAs: DeclaredAs, fullName: String): BasicTypeInfo =
    api.BasicTypeInfo(name, declAs, fullName, Nil, Nil, None, Nil)
  def unapply(bti: BasicTypeInfo): Option[(String, DeclaredAs, String)] =
    bti match {
      case api.BasicTypeInfo(a, b, c, Nil, Nil, None, Nil) => Some((a, b, c))
      case _ => None
    }
}

