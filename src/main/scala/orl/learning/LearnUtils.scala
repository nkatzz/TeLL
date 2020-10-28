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

package orl.learning

import orl.app.runutils.RunningOptions
import orl.datahandling.Example
import orl.inference.ASPSolver
import orl.logic.{Clause, Constant, Literal}

import scala.io.Source
import scala.util.matching.Regex

/**
  * Created by nkatz at 17/4/20
  */

object LearnUtils {

  def main(args: Array[String]) = {
    val resultsPath = "/home/nkatz/dev/vagmcs/BK/move/crossval-results"
    //val resultsPath = "/home/nkatz/dev/vagmcs/BK/rendezVous/crossval-results"
    getFinalF1Score(resultsPath)
  }

  def parseTheoryFromFile(inps: RunningOptions, filePath: String) = {
      def matches(p: Regex, str: String) = p.pattern.matcher(str).matches
    if (filePath == "") Nil
    else {
      val source = Source.fromFile(filePath)
      val list = source.getLines.filter(line => !matches("""""".r, line) && !line.startsWith("%"))
      val modes = inps.globals.MODEHS ++ inps.globals.MODEBS
      val rulesList = list.map(x => Clause.parse(x, modes)).toList
      source.close
      rulesList
    }
  }

  def setTypePredicates(newRules: List[Clause], inps: RunningOptions) = {
    val mh = inps.globals.MODEHS
    val mb = inps.globals.MODEBS
    newRules.map(_.setTypeAtoms(mh ++ mb))
  }

  def setTypePredicates(rule: Clause, inps: RunningOptions) = {
    val mh = inps.globals.MODEHS
    val mb = inps.globals.MODEBS
    rule.setTypeAtoms(mh ++ mb)
  }

  def getFinalF1Score(resultsFile: String) = {
    val source = Source.fromFile(resultsFile)
    val list = source.getLines.filter(x => x.startsWith("TPs")).toList
    val f = (x: String) => x.split(": ")(1).toInt
    val split = list.map(x => x.split(",")).map(x => (f(x(0)), f(x(1)), f(x(2))))
    val (tps, fps, fns) = split.foldLeft(0, 0, 0) { (x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3) }
    val precision = tps.toDouble / (tps.toDouble + fps.toDouble)
    val recall = tps.toDouble / (tps.toDouble + fns.toDouble)
    val f1score = (2 * precision * recall) / (precision + recall)
    println(precision, recall, f1score)
  }

  def reScoreAndPrune(finalTheory: List[Clause], inps: RunningOptions, data: Iterator[Example]) = {
    if (finalTheory.nonEmpty) reScore(finalTheory, data, inps)
      def function(rule: Clause) = if (rule.head.predSymbol == "initiatedAt") rule.precision else rule.recall
    val pruned = finalTheory.filter(x => function(x) > inps.pruneThreshold) // && x.seenExmplsNum > inps.minEvalOn)
    pruned
  }

  def reScore(theory: List[Clause], data: Iterator[Example], params: RunningOptions) = {
    for (x <- data) OLEDRuleScoringOld(theory, x, params)
  }

  /**
    * The old method of rule scoring in OLED, where each rule's precision is calculated from the
    * rule's groundings on an interpretation, not on the inferred state generated by the entire
    * theory currently available.
    *
    * For this to work the theory should be split in two, the initiatedAt part and the terminatedAt part.
    *
    */

  def OLEDRuleScoringOld(rules: List[Clause], example: Example, inps: RunningOptions) = {

      def marked(): (String, Map[String, Clause]) = {
        val allRefinements = rules flatMap (_.refinements)
        val allRules = rules ++ allRefinements
        val markedTheory = rules map (x => markedRule(x))
        val markedRefinements = allRefinements map (x => markedRule(x))
        val allRulesMarked = markedTheory ++ markedRefinements
        val hashCodesClausesMap = (allRules map (x => x.##.toString -> x)).toMap
        val rulePredicates = hashCodesClausesMap.keySet.map(x => s"rule($x). ").mkString("\n")
        (allRulesMarked.map(_.tostring).mkString("\n") + rulePredicates, hashCodesClausesMap)
      }

      def markedRule(rule: Clause) = {
        val modes = inps.globals.MODEHS ++ inps.globals.MODEBS
        Clause(head = Literal(predSymbol = "marked",
                              terms      = List(Constant(rule.##.toString), rule.head)), body = rule.withTypePreds(modes).body)
      }

    val globals = inps.globals
    val targetClass = rules.head.head.predSymbol

    val e = example.toASP().mkString(" ")
    val _marked = marked()
    val markedProgram = _marked._1
    val markedMap = _marked._2
    val exmplCountRules = globals.EXAMPLE_COUNT_RULE

    val show = globals.SHOW_TPS_ARITY_2 + globals.SHOW_FPS_ARITY_2 +
      globals.SHOW_FNS_ARITY_2 + globals.SHOW_TIME + globals.SHOW_INTERPRETATIONS_COUNT

    val include = {
      targetClass match {
        case "initiatedAt" => globals.INCLUDE_BK(globals.BK_INITIATED_ONLY_MARKDED)
        case "terminatedAt" => globals.INCLUDE_BK(globals.BK_TERMINATED_ONLY_MARKDED)
      }
    }

    val program = e + include + exmplCountRules + markedProgram + show
    val result = ASPSolver.solve(program)

    val (exampleCounts, coverageCounts) = result.foldLeft(List[String](), List[String]()) { (x, y) =>
      val (exCount, coverageCounts) = (x._1, x._2)
      if (y.startsWith("tps") || y.startsWith("fps") || y.startsWith("fns")) {
        (exCount, coverageCounts :+ y)

      } else if (y.startsWith("countGroundings")) {
        (exCount :+ y, coverageCounts)
      } else {
        throw new RuntimeException(s"Don't know what to do with what the solver" +
          s" returned.\nExpected tps/2,fps/2,fns/2,countGroundings/1 got\n${result}")
      }
    }

    if (exampleCounts.length > 1)
      throw new RuntimeException(s"Only one countGroundings/1 atom was expected, got ${exampleCounts.mkString(" ")} instead.")

    val c = exampleCounts.head.split("\\(")(1).split("\\)")(0).toInt

    rules foreach { x =>
      x.seenExmplsNum += c //times//*100 // interps
      x.refinements.foreach(y => y.seenExmplsNum += c)
    }

    val parse = (atom: String) => {
      val tolit = Literal.parse(atom)
      val (what, hashCode, count) = (tolit.predSymbol, tolit.terms.head.tostring, tolit.terms.tail.head.tostring)
      (what, hashCode, count)
    }

    val updateCounts = (what: String, hashCode: String, count: String) => {
      val clause = markedMap(hashCode)
      what match {
        case "tps" => clause.tps += count.toInt
        case "fps" => clause.fps += count.toInt
        case "fns" => clause.fns += count.toInt
      }
    }

    coverageCounts foreach { x =>
      val (what, hashCode, count) = parse(x)
      updateCounts(what, hashCode, count)
    }
  }

}
