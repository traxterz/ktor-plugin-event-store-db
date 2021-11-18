package io.traxter.eventstoredb

import com.eventstore.dbclient.DeleteResult
import com.eventstore.dbclient.DeleteStreamOptions
import com.eventstore.dbclient.EventData
import com.eventstore.dbclient.EventStoreDBClient
import com.eventstore.dbclient.EventStoreDBConnectionString
import com.eventstore.dbclient.Position
import com.eventstore.dbclient.ReadAllOptions
import com.eventstore.dbclient.ReadResult
import com.eventstore.dbclient.ReadStreamOptions
import com.eventstore.dbclient.ResolvedEvent
import com.eventstore.dbclient.StreamRevision
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
import io.ktor.application.log
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

fun Application.EventStoreDB(config: EventStoreDB.Configuration.() -> Unit) =
    install(EventStoreDB, config)

typealias ResolvedEventListener = suspend ResolvedEvent.() -> Unit
typealias ErrorEventListener = suspend Subscription.(throwable: Throwable) -> Unit

interface EventStoreDB : CoroutineScope {
    data class Configuration(
        var connectionString: String = "esdb://localhost:2111,localhost:2112,localhost:2113?tls=true&tlsVerifyCert=false",
        var logger: Logger,
        var errorListener: ErrorEventListener = { throwable ->
            logger.error("Subscription[ ${this.subscriptionId} ] was dropped due to  due to ${throwable.message}")
        },
        var reSubscribeOnDrop: Boolean = true
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
    suspend fun subscribeToStream(streamName: String, listener: ResolvedEventListener): Subscription
    suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: ResolvedEventListener
    ): Subscription

    suspend fun subscribeToAll(listener: ResolvedEventListener): Subscription
    suspend fun subscribeToAll(options: SubscribeToAllOptions, listener: ResolvedEventListener): Subscription
    suspend fun deleteStream(streamName: String): DeleteResult
    suspend fun deleteStream(streamName: String, options: DeleteStreamOptions.() -> Unit): DeleteResult

    companion object Feature : ApplicationFeature<Application, Configuration, EventStoreDB> {
        override val key: AttributeKey<EventStoreDB> = AttributeKey("EventStoreDB")
        val ClosedEvent = EventDefinition<Unit>()

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): EventStoreDB {
            val applicationMonitor = pipeline.environment.monitor
            val config = Configuration(logger = pipeline.log).apply(configure)
            val plugin = EventStoreDbPlugin(config)

            applicationMonitor.subscribe(ApplicationStopPreparing) {
                plugin.shutdown()
                it.monitor.raise(ClosedEvent, Unit)
            }
            return plugin
        }
    }
}

internal class EventStoreDbPlugin(private val config: EventStoreDB.Configuration) : EventStoreDB {
    private val parent: CompletableJob = Job()
    override val coroutineContext: CoroutineContext
        get() = parent

    private val streamRevisionBySubscriptionId = ConcurrentHashMap<String, StreamRevision>()
    private val positionBySubscriptionId = ConcurrentHashMap<String, Position>()

    private val client = EventStoreDBClient
        .create(EventStoreDBConnectionString.parseOrThrow(config.connectionString))

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
        listener: ResolvedEventListener
    ): Subscription = subscribeToStream(streamName, SubscribeToStreamOptions.get(), listener)

    override suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: ResolvedEventListener
    ): Subscription =
        client.subscribeToStream(
            streamName,
            object : SubscriptionListener() {
                override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
                    streamRevisionBySubscriptionId[subscription.subscriptionId] = event.originalEvent.streamRevision
                    launch { listener(event) }
                }

                override fun onError(subscription: Subscription, throwable: Throwable) {
                    launch {
                        if (config.reSubscribeOnDrop)
                            subscribeToStream(
                                streamName,
                                options.fromRevision(streamRevisionBySubscriptionId[subscription.subscriptionId]),
                                listener
                            )
                        config.errorListener(subscription, throwable)
                    }
                }
            },
            options
        ).await()

    override suspend fun subscribeToAll(listener: ResolvedEventListener): Subscription = subscribeToAll(
        SubscribeToAllOptions.get(), listener
    )

    override suspend fun subscribeToAll(
        options: SubscribeToAllOptions,
        listener: ResolvedEventListener
    ): Subscription =
        client.subscribeToAll(
            object : SubscriptionListener() {
                override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
                    positionBySubscriptionId[subscription.subscriptionId] = event.originalEvent.position
                    launch { listener(event) }
                }

                override fun onError(subscription: Subscription, throwable: Throwable) {
                    launch {
                        if (config.reSubscribeOnDrop)
                            subscribeToAll(
                                options.fromPosition(positionBySubscriptionId[subscription.subscriptionId]),
                                listener
                            )
                        config.errorListener(subscription, throwable)
                    }
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
