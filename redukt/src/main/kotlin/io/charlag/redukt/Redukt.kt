package io.charlag.redukt

import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.observables.ConnectableObservable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject

/**
 * A function to create a new Knot: connection point for incoming events, reducers and epics.
 * It may resemble the 'Store' from Redux but it's not the same thing - you cannot dispatch events
 * to it and you cannot access state directly (though you may like 'Store' around Knot).
 * @param initial The starting state
 * @param eventsSource External events which will be fed to reducers first and to epics afterwards
 * @param reducer Root reducer. Given old state and event should return new state. It should be a
 * pure function, without references to anything besides its parameters
 * @param rootEpic Root Epic
 * @param S Type of the state
 * @return Stream of events - external ones and ones dispatched by the Epics
 *
 * @author charlag
 */
fun <S> createKnot(
        initial: S,
        eventsSource: Observable<Event>,
        reducer: (S, Event) -> S,
        rootEpic: Epic<S>
): ConnectableObservable<Event> {
    return Observable.create<Event> { observer ->
        val state = BehaviorSubject.createDefault(initial)
        val events = PublishSubject.create<Event>()

        events.withLatestFrom(state, BiFunction<Event, S, EventBundle<S, Event>> { ev, oldState ->
            val newState = reducer(oldState, ev)
            state.onNext(newState)
            EventBundle(ev, newState, oldState)
        })
                .applyEpic(rootEpic)
                .subscribe({ t ->
                    observer.onNext(t)
                    events.onNext(t)
                })
        eventsSource.subscribe(events)
    }
            .publish()
}

        /**
         *
         */
typealias Epic<S> = (Observable<out EventBundle<S, Event>>) -> Observable<out Event>

/**
 * Data class used in Epics.
 * @property event Event which was fed to the Knot
 * @property newState State which was returned by the reducer
 * @property oldState State present before encounting the event
 */
data class EventBundle<out S, out E>(val event: E, val newState: S, val oldState: S)

interface Event

fun <S> Observable<out EventBundle<S, Event>>.applyEpic(epic: Epic<S>) =
        let(epic)

fun <S> Observable<out EventBundle<S, Event>>.applyEpics(
        vararg epics: Epic<S>): Observable<Event> {
    return Observable.merge(epics.map { it(this) })
}

fun <S> epicOf(vararg epics: Epic<S>): Epic<S> =
        { upstream -> upstream.applyEpics(*epics) }

inline fun <S, reified T> makeMapEpic(type: Class<T>,
                                      noinline mapper: ((EventBundle<S, T>) -> Event)): Epic<S> {
    return { upstream ->
        upstream.ofEventType(type).map(mapper)
    }
}

inline fun <S, reified T> switchMapEpic(type: Class<T>,
                                        noinline mapper: ((EventBundle<S, T>) -> Observable<Event>)): Epic<S> {
    return { upstream ->
        upstream.ofEventType(type).switchMap(mapper)
    }
}

inline fun <S, reified T> Observable<out EventBundle<S, Any>>.ofEventType()
        : Observable<EventBundle<S, T>> =
        filter { it.event is T }
                .map {
                    @Suppress("UNCHECKED_CAST")
                    it as EventBundle<S, T>
                }

inline fun <S, reified T> Observable<out EventBundle<S, Any>>.ofEventType(type: Class<T>)
        : Observable<EventBundle<S, T>> = filter { type.isInstance(it.event) }
        .map {
            @Suppress("UNCHECKED_CAST")
            it as EventBundle<S, T>
        }

fun <S> Observable<out EventBundle<S, Any>>.filterStateChanged():
        Observable<out EventBundle<S, Any>> = distinctUntilChanged { first, second -> first.newState != second.newState }
