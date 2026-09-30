# Device MQTT Broker

Java17 and Netty4.1.118.Final Maven project scaffold. Broker business implementation is not yet present.

Run Maven from the project root. Dependencies are stored in the local `.m2/repository` directory selected by `.mvn/maven.config`.

```sh
mvn test
mvn package
```

Dependencies include Netty MQTT codecs and handlers, SQLite JDBC3.49.1.0, Eclipse Paho1.2.5 for client examples, and JUnit Jupiter5.11.4.
