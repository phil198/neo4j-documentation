/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.cypher.docgen.tooling.tests

import org.neo4j.cypher.GraphIcing
import org.neo4j.exceptions.SyntaxException
import org.neo4j.cypher.docgen.tooling._
import org.neo4j.cypher.docgen.tooling.DocsExecutionResult
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.impl.coreapi.InternalTransaction
import org.scalatest.Assertions
import org.scalatest.FunSuiteLike
import org.scalatest.Matchers
import org.scalatest.Suite
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}

class QueryRunnerTest extends Suite
                      with FunSuiteLike
                      with Assertions
                      with Matchers
                      with GraphIcing  {
  test("invalid query fails") {
    val query = "MATCH n RETURN x"
    val result = runQuery(query)

    result.queryResults should have size 1
    result should haveATestFailureOfClass(query -> classOf[SyntaxException])
  }

  test("assertion failure comes through nicely") {
    val query = "MATCH (n) RETURN n"
    val result = runQuery(query, ResultAssertions(p => 1 should equal(2)))

    result.queryResults should have size 1
    result should haveATestFailureOfClass(query -> classOf[TestFailedException])
  }

  test("init query failing is reported as such") {
    val failingQuery = "YOU SHALL NOT PASS"
    val result = run(RunnableInitialization.empty, failingQuery, new GraphVizPlaceHolder(""))

    result.queryResults should have size 1
    result should haveATestFailureOfClass(failingQuery -> classOf[SyntaxException])
  }

  test("query with KeepState keeps data") {
    runQueriesWithBehavior(Seq(
      queryContent(
        query = "CREATE ({prop: 123})",
        databaseStateBehavior = KeepState),
      queryContent(
        query = "MATCH (n) RETURN n.prop",
        assertions = ResultAssertions(r => r.toSet.shouldEqual(Set(Map("n.prop" -> 123))))),
    ))
  }

  test("query with ClearState clears data even if it is a read query") {
    runQueriesWithBehavior(Seq(
      queryContent(
        query = "CREATE ({prop: 123})",
        databaseStateBehavior = KeepState),
      queryContent(
        query = "MATCH (n) RETURN n.prop",
        databaseStateBehavior = ClearState,
        assertions = ResultAssertions(r => r.toSet.shouldEqual(Set(Map("n.prop" -> 123))))),
      queryContent(
        query = "MATCH (n) RETURN n.prop",
        assertions = ResultAssertions(r => r.toSet.shouldEqual(Set.empty))),
    ))
  }

  private def runQuery(query: String, assertions: QueryAssertions = NoAssertions): TestRunResult =
    run(RunnableInitialization.empty, query, new TablePlaceHolder(assertions))

  private def run(init: RunnableInitialization, queryText: String, content: QueryResultPlaceHolder): TestRunResult = {
    val runner = new QueryRunner(noContentFormatter)
    runner.runQueries(contentsWithInit = Seq(ContentWithInit(init, Some(InitializationQuery(queryText)), content)), "title")
  }

  private def runQueriesWithBehavior(content: Seq[ContentWithInit]): TestRunResult =
    new QueryRunner(noContentFormatter).runQueries(contentsWithInit = content, "title")

  private def queryContent(
    query: String,
    databaseStateBehavior: DatabaseStateBehavior = ClearStateAfterUpdateOrError,
    assertions: QueryAssertions = NoAssertions
  ) = ContentWithInit(
    RunnableInitialization.empty,
    Some(Query(
      original = query,
      assertions = NoAssertions,
      myInit = RunnableInitialization.empty,
      content = NoContent,
      params = Seq(),
      databaseStateBehavior = databaseStateBehavior
    )),
    new TablePlaceHolder(assertions)
  )

  private def haveATestFailureOfClass[EXCEPTION <: Exception](queryAndClass: (String, Class[EXCEPTION])) =
    new HasATestFailureOfClass(queryAndClass)

  private def haveFailureFor(query: String) =
    new HasFailure(query)

  private def noContentFormatter(qg: GraphDatabaseQueryService, tx: InternalTransaction)(r: DocsExecutionResult) = NoContent
}

class HasATestFailureOfClass[EXCEPTION <: Exception](queryAndClass: (String, Class[EXCEPTION]))
  extends Matcher[TestRunResult] {

  def apply(result: TestRunResult) = {

    val (query, expectedType) = queryAndClass

    if (result.success)
      MatchResult(
        matches = false,
        s"""Did not contain a test failure for query [<$query>] of type $expectedType""",
        s"""Did contain a test failure for query [<$query>] of type $expectedType"""
      )
    else {
      val testFailure = QueryRunnerTest.errorForQuery(result, query)
      MatchResult(
        matchesDirectlyOrThroughCause(expectedType, testFailure.get),
        s"""Did not contain a test failure for query [<$query>] of type $expectedType - the failure found had type ${testFailure.get.getClass}""",
        s"""Did contain a test failure for query [<$query>] of type ${testFailure.get.getClass}"""
      )
    }
  }

  private def matchesDirectlyOrThroughCause(expected: Class[EXCEPTION], actual: Throwable): Boolean = {
    if(expected == actual.getClass)
      true
    else if(actual.getCause != null) {
      matchesDirectlyOrThroughCause(expected, actual.getCause)
    } else false
  }
}

class HasFailure(query: String)
  extends Matcher[TestRunResult] {

  def apply(result: TestRunResult) = {
    val testFailure = QueryRunnerTest.errorForQuery(result, query)

    val maybeFailure = testFailure.map(_.toString).getOrElse("")
    MatchResult(
      matches = testFailure.nonEmpty,
      s"""Did not contain a test failure for query [<$query>]""",
      s"""Did contain a test failure for query [<$query>]: $maybeFailure"""
    )
  }
}

object QueryRunnerTest {
  def errorForQuery(result: TestRunResult, query: String) = result.queryResults.collectFirst {
    case QueryRunResult(q, _, r) if query == q => r.left.toOption
  }.flatten
}
