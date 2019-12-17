package oled.inference

import java.text.DecimalFormat

import lomrf.logic.{AtomSignature, EvidenceAtom, FunctionMapping, PredicateCompletion, PredicateCompletionMode}
import lomrf.logic.parser.KBParser
import lomrf.mln.grounding.MRFBuilder
import lomrf.mln.inference.ILP
import lomrf.mln.learning.structure.ClauseConstructor
import lomrf.mln.model.{ConstantsDomainBuilder, EvidenceBuilder, KB, MLN}
import oled.datahandling.Example
import oled.logic.{Clause, Literal}
import lomrf.mln.model.AtomIdentityFunctionOps._
import oled.app.runutils.RunningOptions
import oled.learning.Types.InferredState

import scala.io.Source
import scala.collection.mutable

/**
 * Created by nkatz at 13/12/19
 */

object MAPSolver {

  type MAPState = Map[String, Boolean]
  type MLNConstsToASPAtomsMap = mutable.Map[String, String]

  def solve(rules: List[Clause], e: Example, inertiaAtoms: Set[Literal], inps: RunningOptions): InferredState = {

    val queryAtoms = Set(
      AtomSignature("HoldsAt", 2),
      AtomSignature("InitiatedAt", 2),
      AtomSignature("TerminatedAt", 2)
    )

    /* Get BK etc */
    val mlnBKFile = s"${inps.entryPath}/MAPInferenceBK.mln"

    val (kb, constants) = KB.fromFile(mlnBKFile)
    val formulas = kb.formulas

    val parser = new KBParser(kb.predicateSchema.map { case (x, y) => x -> y.toVector }, kb.functionSchema)

    /* Input definitive clauses, whose structure is learnt over time */
    val definiteClauses = rules.map { rule =>
      val head = Literal.toMLNClauseLiteral(rule.head).tostringMLN
      val body = rule.body.map(Literal.toMLNClauseLiteral(_).tostringMLN).mkString(" ^ ")
      parser.parseDefiniteClause(s"${format(rule.weight)} $head :- $body")
    }

    /* Read the definite clauses from the BK file. FOR DEBUGGING */
    //val definiteClauses = kb.definiteClauses

    val obs = e.observations ++ inertiaAtoms.map(x => x.tostring)
    val ee = Example(e.queryAtoms, obs, e.time)

    val (functionMappings, mlnEvidenceAtoms, mlmConstsToAspAtomsMap) = getFunctionMappings(ee, inps.globals.BK_WHOLE_EC)

    // Adding constants
    val const = ConstantsDomainBuilder.from(constants)

    for ((_, (returnValue, symbol)) <- functionMappings) {
      val splitFunction = symbol.split(",").toVector
      val functionSymbol = splitFunction.head
      val args = splitFunction.tail
      val (returnValueDomain, argDomains) = kb.functionSchema(AtomSignature(functionSymbol, args.length))
      const += returnValueDomain -> returnValue
      argDomains.zip(args).foreach { case (domain, value) => const += domain -> value }
    }

    for (atom <- mlnEvidenceAtoms) {
      val args = atom.terms.map(x => lomrf.logic.Constant(x.tostring)).toVector
      val domains = kb.predicateSchema(AtomSignature(atom.predSymbol, args.length))
      domains.zip(args).foreach { case (domain, value) => const += domain -> value.symbol }
    }







    /*val domains = const.result()
    domains.foreach { case (name, set) =>
      val constants = set.iterator
      println(s"$name: [${constants.mkString(",")}]")
    }*/

    val evidenceBuilder = EvidenceBuilder(kb.predicateSchema, kb.functionSchema, queryAtoms, Set.empty, const.result())


    for (entry <- functionMappings) {
      val functionReturnConstant = entry._2._1
      val functionStr = entry._2._2
      val splitFunction = functionStr.split(",").toList
      val functionSymbol = splitFunction.head
      val functionArgs = splitFunction.tail.toVector
      evidenceBuilder.functions += new FunctionMapping(functionReturnConstant, functionSymbol, functionArgs)
    }



    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "active", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "appear", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "inactive", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "walking", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "abrupt", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "running", Vector("ref"))
    evidenceBuilder.functions += new FunctionMapping("ADSANdlj", "disappear", Vector("ref"))



    for (atom <- mlnEvidenceAtoms) {
      val predicate = atom.predSymbol
      val args = atom.terms.map(x => lomrf.logic.Constant(x.tostring)).toVector
      evidenceBuilder.evidence += EvidenceAtom.asTrue(predicate, args)
    }

    for (atom <- inertiaAtoms) {
      val predicate = atom.predSymbol.capitalize
      val fluent = atom.terms.head.asInstanceOf[Literal]
      val fluentConst = s"${fluent.predSymbol.capitalize}_${fluent.terms.map(x => x.tostring.capitalize).mkString("_")}"
      if (!mlmConstsToAspAtomsMap.keySet.contains(fluentConst)) mlmConstsToAspAtomsMap(fluentConst) = fluent.tostring
      val timeConst = atom.terms.tail.head.tostring
      val args = Vector(fluentConst, timeConst).map(x => lomrf.logic.Constant(x)).toVector
      evidenceBuilder.evidence += EvidenceAtom.asTrue(predicate, args)
    }

    val evidence = evidenceBuilder.result()



    println("  Predicate completion...")
    val resultedFormulas = PredicateCompletion(formulas, definiteClauses.toSet, PredicateCompletionMode.Decomposed)(kb.predicateSchema, kb.functionSchema, constants)
    val cnf = ClauseConstructor.makeCNF(resultedFormulas)(constants).toVector

    // This prints out the lifted rules in CNF form.
    //println(cnf.map(_.toText()).mkString("\n"))

    val mln = MLN(kb.schema, evidence, queryAtoms, cnf)
    val builder = new MRFBuilder(mln, createDependencyMap = true)
    val mrf = builder.buildNetwork

    /* FOR DEBUGGING (print out the ground program) */
    /*val constraints = mrf.constraints.iterator()
    while (constraints.hasNext) {
      constraints.advance()
      val constraint = constraints.value()
      println(constraint.decodeFeature(10000)(mln))
    }*/

    val solver = new ILP(mrf)
    //solver.infer()

    println("    Calling the solver...")
    val s = solver.infer()

    var result = Map.empty[String, Boolean]

    val it = s.mrf.atoms.iterator()
    while(it.hasNext) {
      it.advance()
      val a = it.value()
      val atom = a.id.decodeAtom(mln).get
      val state = a.getState
      // keep only inferred as true atoms, get the rest via CWA.
      //if (state) result += atom -> state //result += atom -> state
      result += atom -> state
    }

    val resultToASP = MAPInferredStateToASP(result, mlmConstsToAspAtomsMap)
    resultToASP //result
  }

  def MAPInferredStateToASP(mapState: MAPState, constsToAtoms: MLNConstsToASPAtomsMap) = {
    mapState.map { case (mlnAtom, truthValue) =>
      val _mlnAtom = {
        val (head, tail) = (mlnAtom.head, mlnAtom.tail)
        head.toString.toLowerCase() + tail
      }
      val aspAtom = constsToAtoms.foldLeft(_mlnAtom) { (x, y) =>
        x.replaceAll(y._1, y._2)
      }
      aspAtom -> truthValue
    }
  }

  def format(x: Double) = {
    val defaultNumFormat = new DecimalFormat("0.############")
    defaultNumFormat.format(x)
  }


  /**
   * This method extracts function mappings from the current batch, stuff like
   * Running_ID0 = running(ID0)
   * Enter_ID0 = enter(ID0)
   * Meeting_ID0_ID0 = meeting(ID0, ID0)...
   * It also converts ASP evidence atoms to MLN evidence atoms and
   * generates next/2 instances for LoMRF. It needs to extract fluent/1, event/1 and next/2 signatures form the batch data
   *
   * */
  def getFunctionMappings(exmpl: Example, bkFile: String) = {

    var functionMappings = scala.collection.mutable.Map[String, (String, String)]()

    val additonalDirectives = s"event(X) :- happensAt(X,_).\n#show.\n#show event/1.\n#show fluent_all/1.#show next/2."
    val source = Source.fromFile(bkFile)
    var bk = source.getLines().toList
    source.close()
    bk = bk :+ additonalDirectives

    val all = ( exmpl.toASP() ++ bk)
    val stuff = ASPSolver.solve(all.mkString("\n"))

    val (fluents, events, nextAtoms) = stuff.foldLeft(List[String](), List[String](), List[String]()) { (x, y) =>
      if (y.startsWith("fluent")) (x._1 :+ y, x._2, x._3)
      else if (y.startsWith("event")) (x._1, x._2 :+ y, x._3)
      else if (y.startsWith("next")) (x._1, x._2, x._3 :+ y)
      else throw new RuntimeException(s"Unexpected input: $y")
    }

    // Populate the function mapping map.
    (fluents ++ events) foreach { x =>
      val parsed = Literal.parse(x)
      val atom = parsed.terms.head
      val atomStr = atom.tostring
      val functor = atom.asInstanceOf[Literal].predSymbol
      val args =  atom.asInstanceOf[Literal].terms

      // This is the term that represents the MLN constant (function value) that is generated from this atom.
      // For instance, given the atom meeting(id0,id2), the corresponding constant term is Meeting_Id0_Id2
      val constantTerm = s"${functor.capitalize}_${args.map(_.tostring.capitalize).mkString("_")}"

      // This represent the MLN function that correspond to this term.
      // For instance, given the atom meeting(id0,id2), the corresponding function term is meeting(Id0,Id2)
      // This is represented by the string "meeting,Id0,Id2", so that the function symbol and the arguments may
      // be easily extracted by splitting with "," at the MAPInference and generate the input for populating the
      // functions of the evidence builder object.
      val functionTerm = s"$functor,${args.map(_.tostring.capitalize).mkString(",")}"

      if (!functionMappings.keySet.contains(atomStr)) functionMappings += atomStr -> (constantTerm, functionTerm)
    }

    // These are used to match the terms in the atoms of the MAP-inferred state and facilitate the conversion to ASP form.
    // It is a map of the form
    // Meeting_ID0_ID2 -> meeting(id0,id2)
    val MLNConstantsToASPAtomsMap = functionMappings.map{ case (k, v) => v._1 -> k }

    // Convert ASP atoms to MLN representation.
    val MLNEvidenceAtoms = (exmpl.observations ++ nextAtoms).map { x =>
      val parsed = Literal.parse(x)
      Literal.toMLNFlat(parsed)
    }
    (functionMappings, MLNEvidenceAtoms, MLNConstantsToASPAtomsMap)
  }


}
