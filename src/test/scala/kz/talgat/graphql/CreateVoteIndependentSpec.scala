package kz.talgat.graphql

import org.scalatest.FunSuite

import kz.talgat.graphql.models.{AuthenticationException, AuthorizationException}
import sangria.execution.{ExceptionHandler => EHandler, HandledException, Executor}
import sangria.marshalling.sprayJson._
import sangria.parser.QueryParser
import spray.json._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Success

class CreateVoteIndependentSpec extends FunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // WIRING FIX (adjudicated as "bug in test" — see session-log.md): the
  // original version called DBSchema.createDatabase inside `run`, so every
  // one of this suite's 5 tests created a second/third/... database against
  // the same fixed H2-mem URL, which collided with "table already exists"
  // after the first test. ScalaTest's FunSuite constructs one instance of
  // this class and runs all its `test{}` blocks against it sequentially, so
  // hoisting `dao` to a val here creates the database exactly once for the
  // whole suite, shared across tests -- the fixture pattern given to the
  // isolated session didn't anticipate multiple tests in one spec file.
  val dao: DAO = DBSchema.createDatabase

  // WIRING FIX (adjudicated as "ambiguity in spec/harness" — see
  // session-log.md): without an exceptionHandler, Sangria's default masks
  // any unhandled resolver exception as a generic "Internal server error",
  // hiding AuthorizationException's real message. GraphQLServer.scala's
  // real HTTP path *does* supply this exact handler, so this reproduces
  // production behaviour -- inlined rather than referencing
  // GraphQLServer.ErrorHandler directly, which would re-trigger the
  // GraphQLServer-singleton double-DB-init problem noted in T1.
  val errorHandler: EHandler = EHandler {
    case (_, AuthenticationException(message)) => HandledException(message)
    case (_, AuthorizationException(message)) => HandledException(message)
  }

  def run(query: String): JsObject = {
    val Success(queryAst) = QueryParser.parse(query)
    val result = Executor.execute(
      GraphQLSchema.SchemaDefinition,
      queryAst,
      MyContext(dao),
      variables = JsObject.empty,
      deferredResolver = GraphQLSchema.Resolver,
      middleware = AuthMiddleware :: Nil,
      exceptionHandler = errorHandler
    )
    Await.result(result, 10.seconds).asInstanceOf[JsObject]
  }

  // -----------------------------------------------------------------------
  // Preserved behaviour + ADDED: Authorized tag / identity derived from
  // ctx.ctx.currentUser.get.id
  //
  // This is the proof-test the risk note's author suggested: login and
  // createVote as sibling mutation fields in one document, asserting the
  // created vote's userId matches the logged-in user (user id 1), not any
  // client-supplied value (there is none anymore).
  // -----------------------------------------------------------------------
  test("login and createVote succeed together, with the vote attributed to the logged-in user (positive)") {
    val response = run(
      """
        |mutation {
        |  login(email: "talgat@mail.com", password: "password") { id }
        |  createVote(linkId: 3) { id user { id } link { id } }
        |}
      """.stripMargin)

    assert(!response.fields.contains("errors"), s"expected no errors, got: $response")

    val data = response.fields("data").asJsObject
    val loginResult = data.fields("login").asJsObject
    assert(loginResult.fields("id") == JsNumber(1))

    val vote = data.fields("createVote").asJsObject
    // WIRING FIX (adjudicated as "ambiguity in spec" — see session-log.md):
    // the original flagged uncertainty about field names was justified --
    // Vote's GraphQL type excludes userId/linkId and exposes nested
    // user{...}/link{...} objects instead (ExcludeFields + AddFields in
    // VoteType, unrelated to and unchanged by this task). Same expectation
    // (vote attributed to user 1, on link 3), read via the actual field
    // shape instead of the flat Scala case-class field names.
    assert(vote.fields("user").asJsObject.fields("id") == JsNumber(1), "vote must be attributed to the authenticated user (id 1), not any other id")
    assert(vote.fields("link").asJsObject.fields("id") == JsNumber(3))
    assert(vote.fields.contains("id"))
  }

  // -----------------------------------------------------------------------
  // MODIFIED: argument list is now (linkId: Int!) only — positive case with
  // a second user, also reinforcing that identity comes from the session
  // (REMOVED: cross-user attribution is no longer possible/needed since
  // there is no userId argument to spoof).
  // -----------------------------------------------------------------------
  test("a second authenticated user can createVote using only the linkId argument (positive)") {
    // Fixture: user 2 (Shakirov) has already voted on link 2 only, so link 1
    // is still open for user 2 to vote on.
    val response = run(
      """
        |mutation {
        |  login(email: "shakirov@mail.com", password: "password") { id }
        |  createVote(linkId: 1) { id user { id } link { id } }
        |}
      """.stripMargin)

    assert(!response.fields.contains("errors"), s"expected no errors, got: $response")

    val data = response.fields("data").asJsObject
    assert(data.fields("login").asJsObject.fields("id") == JsNumber(2))

    val vote = data.fields("createVote").asJsObject
    assert(vote.fields("user").asJsObject.fields("id") == JsNumber(2), "vote must be attributed to user 2's own session identity")
    assert(vote.fields("link").asJsObject.fields("id") == JsNumber(1))
  }

  // -----------------------------------------------------------------------
  // ADDED: Authorized tag triggers AuthMiddleware.beforeField ->
  // ctx.ensureAuthenticated() -> AuthorizationException when there is no
  // currentUser.
  // REMOVED: the ability to call createVote with no prior authentication.
  // -----------------------------------------------------------------------
  test("createVote without any prior login is rejected with an authorization error (negative)") {
    val response = run(
      """
        |mutation {
        |  createVote(linkId: 1) { id }
        |}
      """.stripMargin)

    assert(response.fields.contains("errors"), s"expected a GraphQL error for an unauthenticated call, got: $response")

    val errors = response.fields("errors").asInstanceOf[JsArray]
    assert(errors.elements.nonEmpty)

    // FLAG: the exact shape/location of the AuthorizationException message
    // inside the sangria error payload (top-level "message" field vs. a
    // nested "errors" array per field, etc.) isn't pinned down by the
    // provided signatures, so this check is a loose substring match rather
    // than an exact structural assertion.
    val errorsText = errors.toString.toLowerCase
    assert(
      errorsText.contains("permission") || errorsText.contains("sign in") || errorsText.contains("authoriz"),
      s"expected an authorization-related error message, got: $errors"
    )

    // Per harness convention: a rejected call has no "data" for that field,
    // or the field's data is null. Accept either shape.
    response.fields.get("data") match {
      case Some(data: JsObject) =>
        data.fields.get("createVote") match {
          case Some(v) => assert(v == JsNull, s"expected createVote to be null on failure, got: $v")
          case None    => // acceptable: field simply absent
        }
      case _ => // acceptable: no "data" key at all
    }
  }

  // -----------------------------------------------------------------------
  // MODIFIED: UserIdArg removed from createVote's arguments.
  // REMOVED: the userId GraphQL argument — any caller still sending it gets
  // a GraphQL argument-validation error ("Unknown argument 'userId' on
  // field 'createVote'") rather than a successful mutation.
  // REMOVED: the ability for user A to attribute a vote to a different
  // user B's id — demonstrated here by showing the old-style call is
  // rejected outright rather than silently accepted/ignored.
  // -----------------------------------------------------------------------
  test("createVote sent with the removed userId argument fails GraphQL argument validation even when authenticated (negative)") {
    // WIRING FIX (adjudicated as "ambiguity in spec/harness" — see
    // session-log.md): the original FLAG's uncertainty was correct --
    // an unknown-argument failure is a *query validation* error, which
    // sangria.execution.Executor.execute raises synchronously (before
    // returning a Future at all), not a GraphQL "errors" entry in a
    // successful response. Same expected behaviour (the call is rejected,
    // no vote is created), asserted via `intercept` instead of a return
    // value.
    val caught = intercept[sangria.execution.ValidationError] {
      run(
        """
          |mutation {
          |  login(email: "talgat@mail.com", password: "password") { id }
          |  createVote(linkId: 1, userId: 2) { id }
          |}
        """.stripMargin)
    }

    val message = caught.getMessage.toLowerCase
    assert(
      message.contains("userid") || message.contains("unknown argument"),
      s"expected the error to reference the removed/unknown 'userId' argument, got: ${caught.getMessage}"
    )
  }

  // -----------------------------------------------------------------------
  // Edge case: the same removed-argument rejection should also apply with
  // no authentication at all — i.e. argument validation isn't bypassed or
  // superseded by the auth check in either direction.
  // -----------------------------------------------------------------------
  test("createVote sent with the removed userId argument and no authentication is also rejected (edge case)") {
    // FLAG: uncertain whether GraphQL argument/schema validation runs
    // before AuthMiddleware's beforeField check in this executor pipeline.
    // Either ordering should still result in an overall failure (one error
    // or the other), so this test only asserts that the call fails, not
    // which specific error fires first.
    //
    // WIRING FIX (same ambiguity as the previous test): query validation
    // runs before field resolution (and before any auth check), so this
    // also throws ValidationError synchronously rather than returning a
    // response with an "errors" entry. Confirms the FLAG's instinct that
    // the ordering is validation-first, without needing to guess further.
    intercept[sangria.execution.ValidationError] {
      run(
        """
          |mutation {
          |  createVote(linkId: 1, userId: 2) { id }
          |}
        """.stripMargin)
    }
  }
}
