package io.traxter.eventstoredb

import com.eventstore.dbclient.DeleteResult
import com.eventstore.dbclient.DeleteStreamOptions
import com.eventstore.dbclient.EventData
import com.eventstore.dbclient.EventStoreDBClient
import com.eventstore.dbclient.EventStoreDBConnectionString
import com.eventstore.dbclient.ReadAllOptions
import com.eventstore.dbclient.ReadResult
import com.eventstore.dbclient.ReadStreamOptions
import com.eventstore.dbclient.ResolvedEvent
import com.eventstore.dbclient.SubscribeToAllOptions
import com.eventstore.dbclient.SubscribeToStreamOptions
import com.eventstore.dbclient.Subscription
import com.eventstore.dbclient.SubscriptionListener
import com.eventstore.dbclient.WriteResult
import io.ktor.application.Application
import io.ktor.application.ApplicationFeature
import io.ktor.application.ApplicationStopPreparing
import io.ktor.application.EventDefinition
import io.ktor.application.install
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

fun Application.EventStoreDB(config: EventStoreDB.Configuration.() -> Unit) =
    install(EventStoreDB, config)

interface EventStoreDB : CoroutineScope {
    data class Configuration(
        var connectionString: String = "esdb://localhost:2111,localhost:2112,localhost:2113?tls=true&tlsVerifyCert=false"
    )

    suspend fun appendToStream(streamName: String, eventType: String, message: String): WriteResult
    suspend fun readStream(streamName: String): ReadResult
    suspend fun readStream(streamName: String, maxCount: Long): ReadResult
    suspend fun readStream(streamName: String, options: ReadStreamOptions): ReadResult
    suspend fun readStream(streamName: String, maxCount: Long, options: ReadStreamOptions): ReadResult
    suspend fun readAll(): ReadResult
    suspend fun readAll(maxCount: Long): ReadResult
    suspend fun readAll(options: ReadAllOptions): ReadResult
    suspend fun readAll(maxCount: Long, options: ReadAllOptions): ReadResult
    suspend fun subscribeToStream(streamName: String, listener: suspend ResolvedEvent.() -> Unit): Subscription
    suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: suspend ResolvedEvent.() -> Unit
    ): Subscription

    suspend fun subscribeToAll(listener: suspend ResolvedEvent.() -> Unit): Subscription
    suspend fun subscribeToAll(options: SubscribeToAllOptions, listener: suspend ResolvedEvent.() -> Unit): Subscription
    suspend fun deleteStream(streamName: String): DeleteResult
    suspend fun deleteStream(streamName: String, options: DeleteStreamOptions.() -> Unit): DeleteResult

    companion object Feature : ApplicationFeature<Application, Configuration, EventStoreDB> {
        override val key: AttributeKey<EventStoreDB> = AttributeKey("EventStoreDB")
        val ClosedEvent: EventDefinition<Unit> = EventDefinition()

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): EventStoreDB {
            val applicationMonitor = pipeline.environment.monitor
            val config = Configuration().apply(configure)
            val plugin = EventStoreDbPlugin(config)

            applicationMonitor.subscribe(ApplicationStopPreparing) {
                plugin.shutdown()
                it.monitor.raise(ClosedEvent, Unit)
            }
            return plugin
        }
    }
}

internal class EventStoreDbPlugin(config: EventStoreDB.Configuration) : EventStoreDB {
    private val parent: CompletableJob = Job()
    override val coroutineContext: CoroutineContext
        get() = parent

    private val client = EventStoreDBClient.create(EventStoreDBConnectionString.parseOrThrow(config.connectionString))

    override suspend fun appendToStream(streamName: String, eventType: String, message: String): WriteResult =
        with(client) {
            val eventData = EventData.builderAsBinary(eventType, message.toByteArray()).build()
            appendToStream(streamName, eventData).await()
        }

    override suspend fun deleteStream(streamName: String): DeleteResult = client.deleteStream(streamName).await()
    override suspend fun deleteStream(streamName: String, options: DeleteStreamOptions.() -> Unit): DeleteResult =
        client.deleteStream(streamName, DeleteStreamOptions.get().apply(options)).await()

    override suspend fun subscribeToStream(
        streamName: String,
        listener: suspend ResolvedEvent.() -> Unit
    ): Subscription = subscribeToStream(streamName, SubscribeToStreamOptions.get(), listener)

    override suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: suspend ResolvedEvent.() -> Unit
    ): Subscription =
        client.subscribeToStream(
            streamName,
            object : SubscriptionListener() {
                override fun onEvent(subscription: Subscription?, event: ResolvedEvent) {
                    launch { listener(event) }
                }
            },
            options
        ).await()

    override suspend fun subscribeToAll(listener: suspend ResolvedEvent.() -> Unit): Subscription = subscribeToAll(
        SubscribeToAllOptions.get(), listener
    )

    override suspend fun subscribeToAll(
        options: SubscribeToAllOptions,
        listener: suspend ResolvedEvent.() -> Unit
    ): Subscription =
        client.subscribeToAll(
            object : SubscriptionListener() {
                override fun onEvent(subscription: Subscription?, event: ResolvedEvent) {
                    launch { listener(event) }
                }
            },
            options
        ).await()

    override suspend fun readStream(streamName: String): ReadResult =
        client.readStream(streamName).await()

    override suspend fun readStream(streamName: String, maxCount: Long): ReadResult =
        client.readStream(streamName, maxCount).await()

    override suspend fun readStream(streamName: String, options: ReadStreamOptions): ReadResult =
        client.readStream(streamName, options).await()

    override suspend fun readStream(streamName: String, maxCount: Long, options: ReadStreamOptions): ReadResult =
        client.readStream(streamName, maxCount, options).await()

    override suspend fun readAll(): ReadResult = client.readAll().await()
    override suspend fun readAll(maxCount: Long): ReadResult = client.readAll(maxCount).await()
    override suspend fun readAll(options: ReadAllOptions): ReadResult = client.readAll(options).await()
    override suspend fun readAll(maxCount: Long, options: ReadAllOptions): ReadResult =
        client.readAll(maxCount, options).await()

    fun shutdown() =
        parent.complete().also { client.shutdown() }
}
