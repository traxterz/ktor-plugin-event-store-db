package io.traxter.eventstoredb

import com.eventstore.dbclient.AppendToStreamOptions
import com.eventstore.dbclient.ConnectionSettingsBuilder
import com.eventstore.dbclient.DeleteResult
import com.eventstore.dbclient.DeleteStreamOptions
import com.eventstore.dbclient.EventData
import com.eventstore.dbclient.EventStoreDBClient
import com.eventstore.dbclient.EventStoreDBClientSettings
import com.eventstore.dbclient.EventStoreDBConnectionString.parseOrThrow
import com.eventstore.dbclient.Position
import com.eventstore.dbclient.ReadAllOptions
import com.eventstore.dbclient.ReadResult
import com.eventstore.dbclient.ReadStreamOptions
import com.eventstore.dbclient.ResolvedEvent
import com.eventstore.dbclient.StreamRevision
import com.eventstore.dbclient.SubscribeToAllOptions
import com.eventstore.dbclient.SubscribeToStreamOptions
import com.eventstore.dbclient.Subscription
import com.eventstore.dbclient.SubscriptionFilter
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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

fun Application.EventStoreDB(config: EventStoreDB.Configuration.() -> Unit) =
    install(EventStoreDB, config)

typealias EventListener = suspend ResolvedEvent.() -> Unit
typealias ErrorEventListener = suspend (subscription: Subscription?, throwable: Throwable) -> Unit

interface EventStoreDB : CoroutineScope {
    data class Configuration(
        var connectionString: String? = null,
        var eventStoreSettings: EventStoreDBClientSettings =
            EventStoreDBClientSettings.builder().buildConnectionSettings(),
        var logger: Logger,
        var errorListener: ErrorEventListener = { subscription, throwable ->
            logger.error("Subscription[ ${subscription?.subscriptionId} ] failed due to due to ${throwable.message}")
        },
        var reSubscribeOnDrop: Boolean = true
    ) {
        fun eventStoreSettings(builder: ConnectionSettingsBuilder.() -> Unit) {
            eventStoreSettings = EventStoreDBClientSettings.builder().apply(builder).buildConnectionSettings()
        }
    }

    suspend fun appendToStream(
        streamName: String,
        eventType: String,
        message: String,
        options: AppendToStreamOptions = AppendToStreamOptions.get()
    ): WriteResult

    suspend fun appendToStream(
        streamName: String,
        eventData: EventData,
        options: AppendToStreamOptions = AppendToStreamOptions.get()
    ): WriteResult

    suspend fun readStream(streamName: String): ReadResult
    suspend fun readStream(streamName: String, maxCount: Long): ReadResult
    suspend fun readStream(streamName: String, options: ReadStreamOptions): ReadResult
    suspend fun readStream(streamName: String, maxCount: Long, options: ReadStreamOptions): ReadResult
    suspend fun readAll(): ReadResult
    suspend fun readAll(maxCount: Long): ReadResult
    suspend fun readAll(options: ReadAllOptions): ReadResult
    suspend fun readAll(maxCount: Long, options: ReadAllOptions): ReadResult
    suspend fun subscribeToStream(streamName: String, listener: EventListener): Subscription
    suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: EventListener
    ): Subscription

    suspend fun subscribeToAll(listener: EventListener): Subscription
    suspend fun subscribeToAll(options: SubscribeToAllOptions, listener: EventListener): Subscription
    suspend fun subscribeByStreamNameFiltered(prefix: Prefix, listener: EventListener): Subscription
    suspend fun subscribeByStreamNameFiltered(regex: Regex, listener: EventListener): Subscription
    suspend fun subscribeByEventTypeFiltered(prefix: Prefix, listener: EventListener): Subscription
    suspend fun subscribeByEventTypeFiltered(regex: Regex, listener: EventListener): Subscription
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
    @OptIn(ExperimentalCoroutinesApi::class)
    private val parent: CompletableJob = Job()
    override val coroutineContext: CoroutineContext
        get() = parent

    private val streamRevisionBySubscriptionId = ConcurrentHashMap<String, StreamRevision>()
    private val positionBySubscriptionId = ConcurrentHashMap<String, Position>()

    private val client = config.connectionString
        ?.let { connectionString -> EventStoreDBClient.create(parseOrThrow(connectionString)) }
        ?: EventStoreDBClient.create(config.eventStoreSettings)

    override suspend fun appendToStream(
        streamName: String,
        eventType: String,
        message: String,
        options: AppendToStreamOptions
    ): WriteResult =
        appendToStream(streamName, EventData.builderAsJson(eventType, message).build(), options)

    override suspend fun appendToStream(
        streamName: String,
        eventData: EventData,
        options: AppendToStreamOptions
    ): WriteResult =
        client.appendToStream(streamName, eventData).await()

    override suspend fun deleteStream(streamName: String): DeleteResult = client.deleteStream(streamName).await()
    override suspend fun deleteStream(streamName: String, options: DeleteStreamOptions.() -> Unit): DeleteResult =
        client.deleteStream(streamName, DeleteStreamOptions.get().apply(options)).await()

    override suspend fun subscribeToStream(
        streamName: String,
        listener: EventListener
    ): Subscription = subscribeToStream(streamName, SubscribeToStreamOptions.get(), listener)

    override suspend fun subscribeToStream(
        streamName: String,
        options: SubscribeToStreamOptions,
        listener: EventListener
    ): Subscription =
        subscriptionContext.let { context ->
            client.subscribeToStream(
                streamName,
                object : SubscriptionListener() {
                    override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
                        streamRevisionBySubscriptionId[subscription.subscriptionId] = event.originalEvent.streamRevision
                        launch(context) { listener(event) }
                    }

                    override fun onError(subscription: Subscription?, throwable: Throwable) {
                        launch(context) {
                            if (config.reSubscribeOnDrop && subscription != null)
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
        }

    override suspend fun subscribeToAll(listener: EventListener): Subscription = subscribeToAll(
        SubscribeToAllOptions.get(), listener
    )

    override suspend fun subscribeToAll(
        options: SubscribeToAllOptions,
        listener: EventListener
    ): Subscription =
        subscriptionContext.let { context ->
            client.subscribeToAll(
                object : SubscriptionListener() {
                    override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
                        positionBySubscriptionId[subscription.subscriptionId] = event.originalEvent.position
                        launch(context) { listener(event) }
                    }

                    override fun onError(subscription: Subscription?, throwable: Throwable) {
                        launch(context) {
                            if (config.reSubscribeOnDrop && subscription != null)
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
        }


    override suspend fun subscribeByStreamNameFiltered(prefix: Prefix, listener: EventListener): Subscription =
        SubscriptionFilter.newBuilder().withStreamNamePrefix(prefix.value).build()
            .let { subscribeToAll(SubscribeToAllOptions.get().filter(it), listener) }

    override suspend fun subscribeByStreamNameFiltered(regex: Regex, listener: EventListener): Subscription =
        SubscriptionFilter.newBuilder().withStreamNameRegularExpression(regex.pattern).build()
            .let { subscribeToAll(SubscribeToAllOptions.get().filter(it), listener) }

    override suspend fun subscribeByEventTypeFiltered(prefix: Prefix, listener: EventListener): Subscription =
        SubscriptionFilter.newBuilder().withEventTypePrefix(prefix.value).build()
            .let { subscribeToAll(SubscribeToAllOptions.get().filter(it), listener) }

    override suspend fun subscribeByEventTypeFiltered(regex: Regex, listener: EventListener): Subscription =
        SubscriptionFilter.newBuilder().withEventTypeRegularExpression(regex.pattern).build()
            .let { subscribeToAll(SubscribeToAllOptions.get().filter(it), listener) }

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

    private val subscriptionContextCounter = AtomicInteger(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val subscriptionContext: ExecutorCoroutineDispatcher
        get() =
            newSingleThreadContext("EventStoreDB-subscription-context-${subscriptionContextCounter.incrementAndGet()}")
}

@JvmInline
value class Prefix(val value: String)

val String.prefix get() = Prefix(this)
val String.regex get() = this.toRegex()
