package kz.talgat.graphql

import org.scalatest.FunSuite
import sangria.execution.Executor
import sangria.marshalling.sprayJson._
import sangria.parser.QueryParser
import spray.json._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

// Deliberately does not reference the GraphQLServer object: merely touching it
// would initialize its `private val dao`, which calls DBSchema.createDatabase a
// second time against the same fixed H2-mem URL ("jdbc:h2:mem:howtographqldb")
// kept alive by `keepAliveConnection = true` -- the second `databaseSetup` DDL
// run would then fail with "table LINKS already exists". Each spec that needs
// its own DAO must create its own database directly, as this one does.
class CreateVoteAuthSpec extends FunSuite {
  implicit val ec: ExecutionContext = ExecutionContext.global

  private def run(query: String): JsObject = {
    val Success(queryAst) = QueryParser.parse(query)
    val dao = DBSchema.createDatabase
    val result = Executor.execute(
      GraphQLSchema.SchemaDefinition,
      queryAst,
      MyContext(dao),
      variables = JsObject.empty,
      deferredResolver = GraphQLSchema.Resolver,
      middleware = AuthMiddleware :: Nil
    )
    Await.result(result, 10 seconds).asInstanceOf[JsObject]
  }

  test("logged-in user can vote in the same document") {
    val response = run(
      """
        |mutation {
        |  login(email: "talgat@mail.com", password: "password") {
        |    id
        |  }
        |  createVote(linkId: 1) {
        |    id
        |    user {
        |      id
        |    }
        |  }
        |}
      """.stripMargin)

    assert(!response.fields.contains("errors"), s"expected no errors, got: $response")

    val createVote = response.fields("data").asJsObject.fields("createVote").asJsObject
    assert(createVote.fields("user").asJsObject.fields("id") == JsNumber(1))
  }
}
