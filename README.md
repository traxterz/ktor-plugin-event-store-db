# Ktor Plugin EventStoreDB

[EventStoreDB](https://www.eventstore.com) is an open-source database technology that stores your critical data in streams of immutable events. It was built from the ground up for Event Sourcing, we believe that makes it the best solution in the market for building event-sourced systems. 
Event Sourcing offers some great benefits over state-oriented systems.

This [Plugin](https://ktor.io/docs/creating-custom-plugins.html) is an seamless integration into the world of [Ktor Server](https://ktor.io), which is a lightweight server application framework to handle
http requests and more for the **JVM**.

## Installation

For now, you can use Jitpack to install Khome locally. Just add the following lines to your build.gradle or maven file.

### Gradle
```kotlin
repositories {
    // ...
    maven("https://jitpack.io")
}
dependencies {
    // ...
    implementation("com.github.tracksterz:ktor-plugin-event-store-db:VERSION")
}
```

### Maven
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependency>
    <groupId>com.github.tracksterz</groupId>
    <artifactId>ktor-plugin-event-store-db</artifactId>
    <version>VERSION</version>
</dependency>
```

### Ktor
#### Version I - Standard
```kotlin
fun main() {
    embeddedServer(CIO, port = 8080, host = "localhost") {
        install(EventStoreDB) {// this:EventStoreDB.Configuration
            connectionString = "esdb://localhost:2111,localhost:2112,localhost:2113?tls=true&tlsVerifyCert=false"
        }

    }.start(wait = true)
}
```

#### Version II - Convenient
```kotlin
fun main() {
    embeddedServer(CIO, port = 8080, host = "localhost") {
        EventStoreDB {// this:EventStoreDB.Configuration
            connectionString = "esdb://localhost:2111,localhost:2112,localhost:2113?tls=true&tlsVerifyCert=false"             
        }
    }.start(wait = true)
}
```
## What to expect

You can expect a light wight wrapper around the official EventStoreDB Java clcient with some convience functions that port
the sdk into the Kotlin world and into Ktor Server.

After you installed the Ktor plugin as described above you can use it in the known routing kotlin DSL as follows:

```kotlin

```
