package org.deepdive.ddlog

import scala.collection.immutable.HashMap
import scala.collection.immutable.HashSet

// semantic checker for ddlog
class DeepDiveLogSemanticChecker(program: DeepDiveLog.Program) {

  val namesUsedInHead: Set[String] =
    program collect {
      case s: SchemaDeclaration => s.a.name
      case s: ExtractionRule if s.supervision isEmpty => s.headName
      case s: FunctionCallRule => s.output
    } toSet

  val schemaDeclarationByName: Map[String, SchemaDeclaration] =
    program collect {
      case s: SchemaDeclaration => s.a.name -> s
    } toMap

  val functionDeclarationByName: Map[String, FunctionDeclaration] =
    program collect {
      case s: FunctionDeclaration => s.functionName -> s
    } toMap

  def check(): Unit = {
    program foreach check
  }

  // throw exception
  def error(stmt: Statement, message: String) {
    sys.error(message + s"\n${DeepDiveLogPrettyPrinter.print(stmt)}")
  }

  // check a statement
  def check(stmt: Statement) {
    checkRelationDefined(stmt)
    checkFunctionDefined(stmt)
    checkVariableRelationSchema(stmt)
    checkNumberOfColumns(stmt)
    checkQuantifiedBody(stmt)
    checkWeight(stmt)
    checkVariableBindings(stmt)
    checkSupervisionLabelType(stmt)
  }

  // iterate over all atoms contained in the body list and apply the checker
  def checkBodyAtoms(checker: BodyAtom => Unit): List[Body] => Unit = bodies => {
    bodies foreach {
      case a: BodyAtom => checker(a)
      case a: QuantifiedBody => checkBodyAtoms(checker)(a.bodies)
      case _ =>
    }
  }

  // check if relations in the body are defined
  def checkRelationDefined(stmt: Statement) {
    val stmtStr = DeepDiveLogPrettyPrinter.print(stmt)
    def checkRelation(name: String) {
      if (!(namesUsedInHead contains name))
        error(stmt, s"""relation "${name}" is not defined""")
    }
    def checkAtom(a: BodyAtom) = checkRelation(a.name)
    def check = checkBodyAtoms(checkAtom)
    stmt match {
      case s: ExtractionRule => s.q.bodies foreach check
      case s: InferenceRule => {
        s.q.bodies foreach check
        s.head.terms map (_.name) foreach checkRelation
      }
      case s: FunctionCallRule => s.q.bodies foreach check
      case _ =>
    }
  }

  // check if a function is defined when it's called
  def checkFunctionDefined(stmt: Statement) {
    stmt match {
      case s: FunctionCallRule => {
        if (!(functionDeclarationByName.keySet contains s.function))
          error(stmt, s"""function "${s.function}" is not defined""")
      }
      case _ =>
    }
  }

  // check if the user use reserved column names
  def checkVariableRelationSchema(stmt: Statement) {
    val reservedSet = Set("id", "label")
    stmt match {
      case decl: SchemaDeclaration => {
        if (decl.isQuery) {
          decl.a.terms.foreach { case name =>
            if (reservedSet contains name)
              error(stmt, s"""variable relation contains reserved column "${name}" """)
          }
        }
      }
      case _ =>
    }
  }

  // check if the number of columns match schema declaration
  def checkNumberOfColumns(stmt: Statement) {
    def check(name: String, size: Int) {
      if ((schemaDeclarationByName.keySet contains name) &&
        (size != schemaDeclarationByName(name).a.terms.size))
        error(stmt, s""""${name}": number of columns in the query does not match number of columns in the schema""")
    }
    def checkBodyAtom(a: BodyAtom) = check(a.name, a.terms.size)
    def checkHeadAtom(a: HeadAtom) = check(a.name, a.terms.size)
    def checkCq(cq: ConjunctiveQuery) {
      cq.bodies foreach checkBodyAtoms(checkBodyAtom)
    }
    stmt match {
      case s: ExtractionRule => {
        check(s.headName, s.q.headTerms.size)
        checkCq(s.q)
      }
      case s: InferenceRule => {
        s.head.terms foreach checkHeadAtom
        checkCq(s.q)
      }
      case _ =>
    }
  }

  // check if quantified body contains required number of atoms
  def checkQuantifiedBody(stmt: Statement) {
    def checkBody(bodies: List[Body]) {
      bodies.foreach {
        case b: QuantifiedBody => {
          b.modifier match {
            case OuterModifier() =>
              if ((b.bodies collect { case x: BodyAtom => 1 }).size != 1)
                error(stmt, s"One and only one atom should be supplied in OPTIONAL modifier")
            case ExistModifier(_) =>
              if ((b.bodies collect { case x: BodyAtom => 1 }).size == 0)
                error(stmt, s"At least one atom should be supplied in EXISTS modifier")
            case AllModifier() =>
              if ((b.bodies collect { case x: BodyAtom => 1 }).size == 0)
                error(stmt, s"At least one atom should be supplied in ALL modifier")
          }
        }
        case _ =>
      }
    }
    stmt match {
      case s: ExtractionRule => s.q.bodies foreach checkBody
      case s: InferenceRule => s.q.bodies foreach checkBody
      case _ =>
    }
  }

  // check if the weights makes sense
  def checkWeight(stmt: Statement) {
    stmt match {
      case s: InferenceRule => {
        if ((s.weights.variables collect { case x: ConstExpr => x }).size >= 2)
          error(stmt, s"Weight variables can contain at most one constant")
      }
      case _ =>
    }
  }

  def checkSupervisionLabelType(s: ExtractionRule, expType: VariableType, supVariable: VarPattern, b: BodyAtom) {
    if (schemaDeclarationByName contains b.name)
      b.terms.zipWithIndex.foreach {
        case (`supVariable`, index) => {
          val expColumnType = expType match {
            case BooleanType() => "boolean"
            case MultinomialType(_) => "int"
          }
          if (schemaDeclarationByName(b.name).a.types(index).toLowerCase != expColumnType) {
            val actualColumnType = schemaDeclarationByName(b.name).a.types(index).toLowerCase
            error(s, s"Supervision column ${supVariable.name} should be ${expColumnType} type, but is ${actualColumnType} type")
          }
        }
        case _ =>
      }
  }

  def checkSupervisionLabelType(s: ExtractionRule, expType: VariableType, supVariable: VarPattern, body: Body) {
    body match {
      case qb: QuantifiedBody => qb.bodies.foreach { b => checkSupervisionLabelType(s, expType, supVariable, b) }
      case b: BodyAtom => checkSupervisionLabelType(s, expType, supVariable, b)
      case _ =>
    }
  }

  def checkSupervisionLabelType(stmt: Statement) {
    stmt match {
      case s: ExtractionRule if (schemaDeclarationByName contains s.headName) && (schemaDeclarationByName(s.headName).variableType nonEmpty) => {
        val headType = schemaDeclarationByName(s.headName).variableType.get
        s.supervision foreach {
          case b: BooleanConst => {
            if (headType != BooleanType) {
              error(s, s"Supervision column ${s.supervision} should be boolean type but is ${headType} type")
            }
          }
          case VarExpr(varname) =>
            s.q.bodies.foreach { bodies: List[Body] =>
              bodies.foreach { b =>
                checkSupervisionLabelType(s, headType, VarPattern(varname), b)
              }
            }
          case _ =>
          // XXX assume the rest of the expressions are correct
        }
      }
      case _ =>
    }
  }

  // check if variables have bindings in the body
  def checkVariableBindings(stmt: Statement) {
    // check variable bindings in a conjunctive query
    def checkCq(cq: ConjunctiveQuery, additionalUsedVars: Set[String] = Set()) {
      // collect variable definitions and usages
      val varDefs = cq.bodies flatMap (_ flatMap DeepDiveLogSemanticChecker.collectDefinedVars) toSet
      val varUses = (cq.bodies flatMap (_ flatMap DeepDiveLogSemanticChecker.collectUsedVars) toSet) ++
        (cq.headTerms flatMap DeepDiveLogSemanticChecker.collectUsedVars) ++
        (additionalUsedVars)
      val varUndefined = varUses -- varDefs
      if (varUndefined nonEmpty) error(stmt, s"Variable ${varUndefined mkString (", ")} must have bindings")
    }
    stmt match {
      case s: ExtractionRule => checkCq(s.q, (s.supervision.toSet flatMap { e: Expr => DeepDiveLogSemanticChecker.collectUsedVars(e) }))
      case s: InferenceRule => checkCq(s.q, (s.weights.variables flatMap DeepDiveLogSemanticChecker.collectUsedVars toSet))
      case _ =>
    }
  }

  // partial function that returns relation name defined by the rule
  def definedRelationName(rule: Statement): Option[String] = rule match {
    case decl: SchemaDeclaration => Some(decl.a.name)
    case rule: ExtractionRule => Some(rule.headName)
    case fncall: FunctionCallRule => Some(fncall.output)
    case _ => None
  }

  // shorthand for checking if a set of more rules redefine anything in existing rules
  // (useful for checking queries against a program)
  def checkNoRedefinition(existingRules: List[Statement], moreRules: List[Statement]) = {
    val relationsDefinedByProgram = existingRules flatMap definedRelationName
    val relationsDefinedByExtraRules = moreRules flatMap definedRelationName
    val relationsRedefinedByExtraRules = relationsDefinedByExtraRules intersect relationsDefinedByProgram
    if (relationsRedefinedByExtraRules nonEmpty)
      sys.error(s"Following relations must not be redefined: ${
        relationsRedefinedByExtraRules map {
          "'" + _ + "'"
        } mkString (", ")
      }")
  }

}

object DeepDiveLogSemanticChecker extends DeepDiveLogHandler {

  // collect variables used in the expression
  def collectUsedVars(expr: Expr): Set[String] = expr match {
    case VarExpr(name) => Set(name)
    case FuncExpr(function, args, agg) => args flatMap collectUsedVars toSet
    case BinaryOpExpr(lhs, op, rhs) => collectUsedVars(lhs) ++ collectUsedVars(rhs)
    case TypecastExpr(lhs, rhs) => collectUsedVars(lhs)
    case IfThenElseExpr(ifCondThenExprs, optElseExpr) =>
      (ifCondThenExprs flatMap { case (ifCond, thenExpr) =>
        collectUsedVars(ifCond) ++ collectUsedVars(thenExpr)
      } toSet) ++ (optElseExpr.toSet flatMap { e: Expr => collectUsedVars(e) })
    case ArrayElementExpr(array, index) => collectUsedVars(array) ++ collectUsedVars(index)
    case _ => Set()
  }

  def collectUsedVars(pattern: Pattern): Set[String] = pattern match {
    case VarPattern(name) => Set(name)
    case ExprPattern(e) => collectUsedVars(e)
    case PlaceholderPattern() => Set()
  }

  def collectDefinedVars(pattern: Pattern): Set[String] = pattern match {
    case VarPattern(name) => Set(name)
    case _ => Set()
  }

  def collectUsedVars(cond: Cond): Set[String] = cond match {
    case ExprCond(e) => collectUsedVars(e)
    case CompoundCond(lhs, op, rhs) => collectUsedVars(lhs) ++ collectUsedVars(rhs)
    case NegationCond(c) => collectUsedVars(c)
  }

  def collectUsedVars(body: Body): Set[String] = body match {
    case a: BodyAtom => a.terms flatMap collectUsedVars toSet
    case a: QuantifiedBody => a.bodies flatMap collectUsedVars toSet
    case a: Cond => collectUsedVars(a)
  }

  def collectDefinedVars(body: Body): Set[String] = body match {
    case a: BodyAtom => a.terms flatMap collectDefinedVars toSet
    case a: QuantifiedBody => a.bodies flatMap collectDefinedVars toSet
    case a: Cond => Set()
  }

  // run the checker
  override def run(program: DeepDiveLog.Program, config: DeepDiveLog.Config) = {
    new DeepDiveLogSemanticChecker(program).check()
  }

}
