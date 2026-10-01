# SQLite PostgreSQL Query Bridge

A read-only bridge that lets any PostgreSQL client (protocol 3.0) run
SELECT queries against a SQLite database file. Java 17 + Netty 4.1.118.

## Build

```sh
mvn package
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

## Run

```sh
# create the demo database (once)
java -cp "target/classes:$(cat target/cp.txt)" com.example.bridge.MakeExampleDb example.db

# start the bridge: <sqlite-file> <port>  (listens on 127.0.0.1 only)
java -cp "target/classes:$(cat target/cp.txt)" com.example.bridge.Main example.db 5544
```

Connect with any PostgreSQL client, no password, e.g.:

```sh
psql -h 127.0.0.1 -p 5544 -U demo -d example -c "SELECT * FROM users"
```

## Demo (real pgjdbc client: query, error recovery, cancel)

```sh
java -cp "target/classes:$(cat target/cp.txt)" com.example.bridge.DemoClient 5544
```

## Tests

```sh
mvn test
```

13 tests cover: startup/SSL-refusal/framing/illegal-length (raw sockets),
joins/aggregates/subqueries/WITH, BLOB→lowercase-hex, NULL vs empty string,
UTF-8, rejection of writes/PRAGMA/ATTACH/transactions/multi-statements,
error recovery, independent sessions, sequential queries and CancelRequest.

## Architecture

| File | Role |
|---|---|
| `protocol/PgProtocolDecoder.java` | Startup (SSL refuse, CancelRequest, v3.0), message framing; half/sticky packets, length limits |
| `protocol/PgMessages.java` | Backend message builders (auth, params, key data, rows, errors…) |
| `session/Session.java` | Per-connection state, pid/secret, ordered query queue |
| `session/SessionRegistry.java` | Routes CancelRequest (pid+secret) to the owning session |
| `sql/SqlGuard.java` | Pre-execution validation: single SELECT/WITH only (JSqlParser) |
| `sql/QueryEngine.java` | Read-only SQLite JDBC, row streaming with bounded writes/backpressure, cancellation |
| `BridgeServer.java` / `BridgeHandler.java` / `Main.java` | Netty wiring on 127.0.0.1 |

## Behavior notes

- SSLRequest is answered with 'N' (SSL refused); only protocol 3.0 startup is accepted.
- AuthenticationOk + ParameterStatus + BackendKeyData + ReadyForQuery are sent at startup.
- Simple Query protocol only; extended protocol messages get a polite error and the
  connection stays usable. Empty queries get EmptyQueryResponse.
- All columns are described as `text`; numbers are stringified, BLOBs become lowercase
  hex, NULL and empty string stay distinct, UTF-8 and column names are preserved.
- The SQLite file is opened read-only (`mode=ro`); rejected statements are never executed.
- Rows are streamed one-by-one and writes pause when the client is slow (channel
  writability), so slow clients neither buffer whole result sets nor block others.
- CancelRequest on a separate connection with matching pid+secret interrupts the running
  SQLite statement (`57014 canceling statement due to user request`); wrong secrets are
  ignored and races with query completion are harmless.

