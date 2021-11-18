package io.traxter.eventstoredb

import com.eventstore.dbclient.RecordedEvent
import io.ktor.application.featureOrNull
import io.ktor.routing.Route
import io.ktor.routing.application
import io.ktor.util.pipeline.ContextDsl

@ContextDsl
fun Route.streams(context: EventStoreDB.() -> Unit) =
    application.featureOrNull(EventStoreDB)?.let { client ->
        context(client)
    } ?: error("EventStoreDB is not installed yet!")

fun EventStoreDB.subscribe(config: StreamsSubscription.() -> Unit) =
    config(StreamsSubscription(this))

fun EventStoreDB.subscribe(streamName: String, config: StreamSubscription.() -> Unit) =
    config(StreamSubscription(streamName, this))

inline fun <reified T> RecordedEvent.getEventDataAs(): T =
    getEventDataAs(T::class.java)

