package io.traxter.eventstoredb

import com.eventstore.dbclient.RecordedEvent
import io.ktor.application.Application
import io.ktor.application.featureOrNull
import io.ktor.application.install
import io.ktor.routing.Route
import io.ktor.routing.application
import io.ktor.util.pipeline.ContextDsl

@ContextDsl
fun Route.streams(context: EventStoreDB.() -> Unit) =
    context(application.eventStoreDb)

fun EventStoreDB.subscribe(config: StreamsSubscription.() -> Unit) =
    config(StreamsSubscription(this))

fun EventStoreDB.subscribe(streamName: String, config: StreamSubscription.() -> Unit) =
    config(StreamSubscription(streamName, this))

inline fun <reified T> RecordedEvent.getEventDataAs(): T =
    getEventDataAs(T::class.java)

val Application.eventStoreDb
    get() = featureOrNull(EventStoreDB) ?: install(EventStoreDB)
