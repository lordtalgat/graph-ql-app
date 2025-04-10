package kz.talgat.graphql.models

case class AuthenticationException(message: String) extends Exception(message)
