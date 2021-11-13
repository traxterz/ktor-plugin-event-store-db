package io.traxter.eventstoredb

import com.eventstore.dbclient.RecordedEvent

inline fun <reified T> RecordedEvent.getEventDataAs(): T =
    getEventDataAs(T::class.java)
