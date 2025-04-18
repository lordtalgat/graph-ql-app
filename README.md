## GraphQL with Sangria on Scala

```bash
sbt run
```

SBT will automatically compile and restart the server whenever the source code changes.

After the server is started you can run queries interactively using by opening [http://localhost:8080](http://localhost:8080) in a browser.

Use different PORT if you've changed it int he configuration.

## Database Configuration

This example uses an in-memory [H2](http://www.h2database.com/html/main.html) SQL database. The schema and example data will be re-created every time server starts.

If you would like to change the database configuration or use a different database, then please update `src/main/resources/application.conf`.
