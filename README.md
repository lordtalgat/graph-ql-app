## GraphQL with Sangria on Scala

A small GraphQL server example modeled after the "howtographql" tutorial: `User`s post `Link`s and can `Vote` on them. Built with:

- [Scala](https://www.scala-lang.org/) 2.12
- [Sangria](https://sangria-graphql.github.io/) — the GraphQL schema and query execution engine
- [Akka HTTP](https://doc.akka.io/docs/akka-http/current/) — the HTTP server
- [Slick](https://scala-slick.org/) over an in-memory [H2](http://www.h2database.com/html/main.html) database — data access

## Prerequisites

- A JDK (8+)
- [sbt](https://www.scala-sbt.org/) (version pinned in `project/build.properties`)

## Running the server

```bash
sbt run
```

sbt (via [sbt-revolver](https://github.com/spray/sbt-revolver)) will compile and start the server, and automatically recompile and restart it whenever the source code changes.

The server listens on port 8080. Once it's running:

- Open [http://localhost:8080](http://localhost:8080) in a browser for the bundled GraphiQL UI, where you can run queries and mutations interactively.
- Or send requests directly to the `/graphql` endpoint, e.g. `POST http://localhost:8080/graphql`.

Use a different `PORT` if you've changed it in the configuration (see `Server.scala`).

## Example queries

Try these in GraphiQL:

```graphql
query {
  allLinks {
    url
    description
    postedBy {
      name
    }
    votes {
      user {
        name
      }
    }
  }
}
```

```graphql
mutation {
  createLink(url: "https://example.com", description: "An example link", postedById: 1) {
    id
    url
  }
}
```

```graphql
mutation {
  login(email: "talgat@mail.com", password: "password") {
    id
    name
  }
}
```

The seed data includes two users (`talgat@mail.com` and `shakirov@mail.com`, both with password `password`) and a few example links — see `DBSchema.scala` for the full seed set.

## Database Configuration

This example uses an in-memory H2 SQL database. The schema and example data are re-created every time the server starts, so nothing persists between runs.

If you would like to change the database configuration or use a different database, update `src/main/resources/application.conf`.

## Running tests

```bash
sbt test
```
