/*
 * Copyright (C) 2016  Nikos Katzouris
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package orl.learning.oled

import orl.datahandling.InputHandling.InputSource
import orl.app.runutils.RunningOptions
import orl.datahandling.Example
import orl.inference.ASPSolver
import orl.learning.Learner
import orl.learning.Types.StartOver
import orl.learning.structure.{OldStructureLearningFunctions, RuleExpansion}
import orl.learning.woledmln.WoledMLNLearnerUtils
import orl.logic.{Clause, Literal}

/**
  * Created by nkatz at 12/2/20
  *
  * Implements the abstract methods of the abstract parent class, to learn crisp ASP rules in n onlne fashion.
  *
  * This is an implementation of the original OLED algorithm.
  */

class OLEDLearner[T <: InputSource](inps: RunningOptions, trainingDataOptions: T,
    testingDataOptions: T, trainingDataFunction: T => Iterator[Example],
    testingDataFunction: T => Iterator[Example]) extends Learner(inps, trainingDataOptions, testingDataOptions, trainingDataFunction, testingDataFunction) {

  def process(exmpl: Example) = {

    val inferredState = Map.empty[String, Boolean]
    var tpCounts = 0
    var fpCounts = 0
    var fnCounts = 0
    var totalGroundings = 0
    var rulesCompressed = List.empty[Clause]
    var inferenceTime = 0.0
    var scoringTime = 0.0

    rulesCompressed = state.getBestRules(inps.globals, "score") //.filter(x => x.score(inps.scoringFun) >= 0.9)

    if (rulesCompressed.nonEmpty) {
      val inferredState = ASPSolver.crispLogicInference(rulesCompressed, exmpl, inps.globals)
      val (_tpCounts, _fpCounts, _fnCounts, _totalGroundings, _inertiaAtoms) =
        WoledMLNLearnerUtils.scoreAndUpdateWeights(exmpl, inferredState, state.getAllRules(inps, "all").toVector, inps, logger)
      tpCounts = _tpCounts
      fpCounts = _fpCounts
      fnCounts = _fnCounts
      totalGroundings = _totalGroundings
      inertiaAtoms = _inertiaAtoms.toSet
    } else {
      fnCounts = exmpl.queryAtoms.size
    }

    updateStats(tpCounts, fpCounts, fnCounts, totalGroundings)

    this.inertiaAtoms = inertiaAtoms
    this.inertiaAtoms = Set.empty[Literal] // Use this to difuse inertia

    logger.info(batchInfoMsg(rulesCompressed, tpCounts, fpCounts, fnCounts, inferenceTime, scoringTime))

    if (!withHandCrafted) {
      var newInit = List.empty[Clause]
      var newTerm = List.empty[Clause]

      if (fpCounts > 0 || fnCounts > 0) {

        /*if (!inps.weightLean) {
          val topInit = state.initiationRules.filter(_.body.nonEmpty)
          val topTerm = state.terminationRules.filter(_.body.nonEmpty)
          val growNewInit = OldStructureLearningFunctions.growNewRuleTest(topInit, e, inps.globals, "initiatedAt")
          val growNewTerm = OldStructureLearningFunctions.growNewRuleTest(topTerm, e, inps.globals, "terminatedAt")
          //newInit = if (growNewInit) OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) else Nil
          //newTerm = if (growNewTerm) OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) else Nil
          newInit = OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) //if (growNewInit) generateNewRules(topInit, e, "initiatedAt", inps.globals) else Nil
          newTerm = OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) //if (growNewTerm) generateNewRules(topTerm, e, "terminatedAt", inps.globals) else Nil
        }*/

        //This is the "correct one" so far.
        val theory = rulesCompressed
        val newRules = OldStructureLearningFunctions.generateNewRules(theory, exmpl, inps)
        val (init, term) = newRules.partition(x => x.head.predSymbol == "initiatedAt")

        newInit = init //.filter(p => !state.isBlackListed(p))
        newTerm = term //.filter(p => !state.isBlackListed(p))

        val allNew = newInit ++ newTerm
        if (allNew.nonEmpty) WoledMLNLearnerUtils.showNewRulesMsg(fpCounts, fnCounts, allNew, logger)
        state.updateRules(newInit ++ newTerm, "add", inps)

      }

      val newRules = newInit ++ newTerm

      // score the new rules and update their weights
      val newRulesWithRefs = newRules.flatMap(x => x.refinements :+ x).toVector
      WoledMLNLearnerUtils.scoreAndUpdateWeights(exmpl, inferredState, newRulesWithRefs, inps, logger, newRules = true)

      /* Rules' expansion. */
      // We only need the top rules for expansion here.
      val init = state.initiationRules
      val term = state.terminationRules
      val expandedTheory = RuleExpansion.expandRules(init ++ term, inps, logger)

      state.updateRules(expandedTheory._1, "replace", inps)

      //val pruningSpecs = new PruningSpecs(0.8, 2, 100)
      //val pruned = state.pruneRules(pruningSpecs, inps, logger)
    }
  }

  def generateNewRules(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    generateNewRulesConservative(existingTheory, ex, inps)
    //generateNewRulesEager(existingTheory, ex, inps)
  }

  /**
    * Generates new rules by (minimally) abducing new rule heads from the data, using the
    * existing rules in the theory to avoid abducing redundant atoms.
    */
  def generateNewRulesConservative(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    OldStructureLearningFunctions.generateNewRules(existingTheory, ex, inps)
  }

  /**
    * Generates new rules directly from the commited mistakes.
    * This method does not actually use the existing theory.
    */
  def generateNewRulesEager(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    val topInit = state.initiationRules.filter(_.body.nonEmpty)
    val topTerm = state.terminationRules.filter(_.body.nonEmpty)
    //val growNewInit = OldStructureLearningFunctions.growNewRuleTest(topInit, ex, inps.globals, "initiatedAt")
    //val growNewTerm = OldStructureLearningFunctions.growNewRuleTest(topTerm, ex, inps.globals, "terminatedAt")
    //newInit = if (growNewInit) OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) else Nil
    //newTerm = if (growNewTerm) OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) else Nil
    val newInit = OldStructureLearningFunctions.generateNewRulesOLED(topInit, ex, "initiatedAt", inps.globals) //if (growNewInit) generateNewRules(topInit, e, "initiatedAt", inps.globals) else Nil
    val newTerm = OldStructureLearningFunctions.generateNewRulesOLED(topTerm, ex, "terminatedAt", inps.globals) //if (growNewTerm) generateNewRules(topTerm, e, "terminatedAt", inps.globals) else Nil
    newInit ++ newTerm
  }

  /**
    * Prints statistics & evaluates on test set (if one provided)
    * */
  def wrapUp() = {
    logger.info(s"\nFinished the data")
    if (repeatFor > 0) {
      self ! new StartOver
    } else if (repeatFor == 0) {
      val theory = state.getAllRules(inps, "top")

      showStats(theory)

      if (trainingDataOptions != testingDataOptions) { // test set given, eval on that
        val testData = testingDataFunction(testingDataOptions)
        WoledMLNLearnerUtils.evalOnTestSet(testData, theory, inps)
      }

      shutDown()

    } else { // Just to be on the safe side...
      throw new RuntimeException("This should never have happened (repeatFor is negative).")
    }
  }

}
