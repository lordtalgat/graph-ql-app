package kz.talgat.graphql

import kz.talgat.graphql.models.{AuthenticationException, AuthorizationException, User}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

case class MyContext(dao: DAO, currentUser: Option[User] = None){
  def login(email: String, password: String): User = {
    val userOpt = Await.result(dao.authenticate(email, password), Duration.Inf)
    userOpt.getOrElse(
      throw AuthenticationException("email or password are incorrect!")
    )
  }

  def ensureAuthenticated() =
    if(currentUser.isEmpty)
      throw AuthorizationException("You do not have permission. Please sign in.")
}
