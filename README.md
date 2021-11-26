# Ktor Plugin EventStoreDB

[EventStoreDB](https://www.eventstore.com) is an open-source database technology that stores your critical data in
streams of immutable events. It was built from the ground up for Event Sourcing, we believe that makes it the best
solution in the market for building event-sourced systems. Event Sourcing offers some great benefits over state-oriented
systems.

This [Plugin](https://ktor.io/docs/creating-custom-plugins.html) is an seamless integration into the world
of [Ktor Server](https://ktor.io), which is a lightweight server application framework to handle http requests and more
for the **JVM**.

## Installation

You can use Jitpack to install the plugin in your Ktor project. Just add the following lines to your build.gradle or maven file.

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

## What not to expect

This plugin is based on the official event
store [gRPC](https://developers.eventstore.com/clients/grpc/#connection-details) client. Therefore, we are limited to
the functionality of the client in the version set [here](gradle.properties).

Not yet implemented are:

- Subscription groups
- Projection management

## What to expect

You can expect a lightweight wrapper around the official EventStoreDB Java client with some convenience functions that
port the sdk into the Kotlin world and into Ktor Server.

If you don´t know [EventStoreDB](https://www.eventstore.com), pleaser read
the [documentation](https://developers.eventstore.com/clients/grpc/#getting-started) first.

## Documention

### Accessing the client

```kotlin
val Application.eventStoreDb
    get() = featureOrNull(EventStoreDB) ?: install(EventStoreDB)
```

The plugin provide you with this extension val fetching the client or if absent installing it. Therefore, the client is
almost "everywhere" accessible if you follow Ktor´s extension function pattern. After you installed Ktor plugin as described above you can fetch it in your code like this.

In case you use [Koin for Ktor](https://insert-koin.io/docs/reference/koin-ktor/ktor) just add the client to Koin as
simple as this:

```kotlin
val client = install(ServerSentEvents)
modules(module { single { client } })
```

And get the instance where ever you need them:

```kotlin
val eventStoreDBClient = get<EventStoreDB>()
// or lazy
val eventStoreDBClient by instance<EventStoreDB>()
```

### Appending events

```kotlin
suspend fun appendToStream(
    streamName: String,
    eventType: String,
    message: String,
    options: AppendToStreamOptions
): WriteResult

suspend fun appendToStream(streamName: String, eventData: EventData, options: AppendToStreamOptions): WriteResult
```

For appending events we provide these to functions, expecting either
an [EventData](https://developers.eventstore.com/clients/grpc/appending-events.html#append-your-first-event) object,
which is borrowed from the underlying java client, or for your convenience, you can pass minimal needed information to
append a stream. There is no need to inject options, since they default to the default options. Further information on
handling [EventData](https://developers.eventstore.com/clients/grpc/appending-events.html#working-with-eventdata) and
AppendToStreamOptions can be found in
the [docs](https://developers.eventstore.com/clients/grpc/appending-events.html#appending-events).

#### Example

```kotlin
suspend fun Application.saveEvent(streamName: String, eventType: String, event: CustomEvent) {
    val eventData = EventData.builderAsJson(eventType, event)
    eventStoreDb.appendToStream(streamName, eventData)
}
```

### Reading events

```kotlin
suspend fun readStream(streamName: String): ReadResult

suspend fun readStream(streamName: String, maxCount: Long): ReadResult

suspend fun readStream(streamName: String, options: ReadStreamOptions): ReadResult

suspend fun readStream(streamName: String, maxCount: Long, options: ReadStreamOptions): ReadResult

suspend fun readAll(): ReadResult

suspend fun readAll(maxCount: Long): ReadResult

suspend fun readAll(options: ReadAllOptions): ReadResult

suspend fun readAll(maxCount: Long, options: ReadAllOptions): ReadResult
```

Similar to appending events to an stream of events, you can read them with several options. As you might assume, we also
borrowed parts here from the underlying client why we just refer to the java
client [documentation](https://developers.eventstore.com/clients/grpc/reading-events.html#reading-events) for further
information. Basically what we added here is the suspending nature which fits into Ktor´s concurrency model.

#### Example

```kotlin
suspend fun Application.readAllUserEvents(): List<UserEvent> {
    val readResult: List<ResolvedEvent> = eventStoreDb.readStream("user", maxCount = 10)
    println(readResult.size) // prints 10
    return readResult.map { UserEvent.fromResolvedEvent(it) }
}
```

### Subscribing to a single stream

Subscriptions allow you to subscribe to a stream and receive notifications about new events added to the stream. You
provide an event listener and an optional starting point to the subscription. The listener is called for each event from
the starting point onward. If events already exist, the handler will be called for each event one by one until it
reaches the end of the stream. From there, the server will notify the handler whenever a new event appears.

```kotlin
suspend fun subscribeToStream(streamName: String, listener: ResolvedEventListener): Subscription

suspend fun subscribeToStream(
    streamName: String,
    options: SubscribeToStreamOptions,
    listener: ResolvedEventListener
): Subscription
```

Further information, surprise surprise, can be
found [here](https://developers.eventstore.com/clients/grpc/subscriptions.html#subscription-basics).

Every subscription needs to be provided with an event listener which has the following signature:

```kotlin
typealias EventListener = suspend ResolvedEvent.() -> Unit
``` 

As you can see, the listener gets the resolved event with all the event information as a receiver attached. See the
implementation further down below.

Furthermore, there is a global error event listener that gets executed whenever a subscription fails. This is the
default error listener.

```kotlin
typealias ErrorEventListener = suspend (subscription: Subscription?, throwable: Throwable) -> Unit

var errorListener: ErrorEventListener =
    { subscription, throwable -> logger.error("Subscription[ ${subscription?.subscriptionId} ] failed due to due to ${throwable.message}") }
```

You can customize this in the plugin configuration section:

```kotlin
EventStoreDB {
    errorHandler = { subscription, throwable ->
        // your custom code goes here  
    }
}
```

Everytime a subscription drops, you would rarely want to reprocess all the events again. So you'd need to store the
current position of the subscription somewhere, and then use it to restore the subscription from the point where it
dropped off.

To manually do it on every subscription sounds a bit verbose, so we implemented it as the default behaviour of every
subscription function. Check out the implementation for details:

```kotlin
object : SubscriptionListener() {
    override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
        streamRevisionBySubscriptionId[subscription.subscriptionId] = event.originalEvent.streamRevision
        launch { listener(event) }
    }

    override fun onError(subscription: Subscription?, throwable: Throwable) {
        launch {
            if (config.reSubscribeOnDrop && subscription != null)
                subscribeToStream(
                    streamName,
                    options.fromRevision(streamRevisionBySubscriptionId[subscription.subscriptionId]),
                    listener
                )
            config.errorListener(subscription, throwable)
        }
    }
}
```

## Subscribing to all streams

Subscribing to `$all` is much the same as subscribing to a single stream. The handler will be called for every event
appended after the starting position. Check out
the [docs](https://developers.eventstore.com/clients/grpc/subscriptions.html#subscribing-to-all) for further
information.

```kotlin
suspend fun subscribeToAll(listener: EventListener): Subscription

suspend fun subscribeToAll(options: SubscribeToAllOptions, listener: EventListener): Subscription
```

In most cases you do not want to receive all events from all streams. Therefore, there is a handy server site filtering
option we like to point out here. See the
full [documentation](https://developers.eventstore.com/clients/grpc/subscriptions.html#filter-options) for all details.
For the most common filtering we provided dedicated functions:

```kotlin
suspend fun subscribeByStreamNameFiltered(prefix: Prefix, listener: EventListener): Subscription

suspend fun subscribeByStreamNameFiltered(regex: Regex, listener: EventListener): Subscription

suspend fun subscribeByEventTypeFiltered(prefix: Prefix, listener: EventListener): Subscription

suspend fun subscribeByEventTypeFiltered(regex: Regex, listener: EventListener): Subscription
```

#### Example:

###### Prefix

```kotlin
fun Application.subscribeToCustomerStreams() = launch {
    eventStoreDb.subscribeByStreamNameFiltered("customer-".prefix) {//this:ResolvedEvent
        val event = when (event.eventType) {
            "CustomersMailAddressChanged" -> event.getEventDataAs<CustomersMailAddressChanged>()
            else -> log.error("Received unknown event type: [ ${event.eventType} ]")
        }
        customerAggegration.applyEvent(event)
    }
}
```

###### Regex

```kotlin
fun Application.subscribeToAllNonSystemEvents() = launch {
    eventStoreDb.subscribeByEventTypeFiltered("/^[^\\$].*/".regex) {//this:ResolvedEvent
        logService.logEvent(event.eventType, event.eventData)
    }
}
```

## Kotlin DSL - WIP

For those of you who are fans of Kotlin DSL´s, we provide an experimental version of the EventStoreDB as a Kotlin DSL.

### Streams API
```kotlin
fun Application.configureStreamsSubscriptions() = launch {
    routing {
        streams {
            subscribe {
                authenticated("admin", "password") {
                    filter {
                        eventType {
                            prefixed("customer-") {
                                val event = when (event.eventType) {
                                    "CustomersMailAddressChanged" -> event.getEventDataAs<CustomersMailAddressChanged>()
                                    else -> log.error("Received unknown event type: [ ${event.eventType} ]")
                                }
                                customerAggegration.applyEvent(event)
                            }

                            regex("/^[^\\$].*/".regex) {
                                logService.logEvent(event.eventType, event.eventData)
                            }
                        }
                    }
                }
                filter {
                    eventType {
                        prefixed("order-") {
                            val event = when (event.eventType) {
                                "OrderReceived" -> event.getEventDataAs<OrderReceived>()
                                else -> log.error("Received unknown event type: [ ${event.eventType} ]")
                            }
                            orderAggegration.applyEvent(event)
                        }

                        regex("/^[^\\$].*/".regex) {
                            logService.logEvent(event.eventType, event.eventData)
                        }
                    }
                }

                start { /** replay all events **/ }
                end { /** receive only new events **/ }
            }
        }
    }
}
```
### Stream API

```kotlin
fun Application.configureOrderStreamSubscriptions() = launch {
    routing {
        streams { 
            subscribe("order") {
                authenticated("admin", "password") {
                    start { /** replay all events **/ }
                    end { /** receive only new events **/ }
                    revision(1013L) { /** receive only specific events **/ }
                }

                start { /** replay all events **/ }
                end { /** receive only new events **/ }
                revision(1013L) { /** receive only specific events **/ }
            }
        }
    }
}
```

If you find any bugs or want to contribute, feel free to contact us!
