# SQLite PostgreSQL Query Bridge

Java17 and Netty4.1.118.Final project scaffold. Query bridge business implementation is not yet present.

Run Maven from the project root. `.mvn/maven.config` selects the independent local dependency repository `.m2/repository`.

```sh
mvn test
mvn package
```

Dependencies: Netty transport and handlers, SQLite JDBC3.49.1.0, JSqlParser5.0, PostgreSQL JDBC42.7.5 for client examples, and JUnit Jupiter5.11.4.
