package com.freeletics.rxredux

import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import com.facebook.testing.screenshot.Screenshot
import com.freeletics.rxredux.businesslogic.pagination.PaginationStateMachine
import io.reactivex.subjects.Subject
import timber.log.Timber
import java.util.*


/**
 * This is a simple queue system ensures that a screenshot is taken of every state transition.
 *
 * This ensures that if state transitions happens very quickly one after each other it adds some delay
 * and processes them sequentially by waiting until first state is rendered (layouted) and drawn (take also a screenshot)
 * and then continue processing the next state.
 */
class QueueingScreenshotTaker(
    private val rootView: View,
    private val subject: Subject<PaginationStateMachine.State>,
    private val dispatchRendering: (PaginationStateMachine.State) -> Unit
) : ViewTreeObserver.OnPreDrawListener {

    init {
        rootView.viewTreeObserver.addOnPreDrawListener(this)
    }

    private val activity: Activity = rootView.getActivity()

    private val handler = Handler(Looper.getMainLooper())

    private val queue: Queue<QueueEntry> = LinkedList()
    private var screenshotCounter = 1

    fun enqueue(state: PaginationStateMachine.State) {
        Timber.d("Enqueueing $state")
        queue.offer(QueueEntry(state, QueuedState.ENQUEUD))
        dispatchNextWaitingStateIfNothingWaitedToBeDrawn()
    }

    private fun dispatchNextWaitingStateIfNothingWaitedToBeDrawn() {
        if (queue.isNotEmpty()) {
            val queueEntry = queue.peek()
            if (queueEntry.queuedState == QueuedState.ENQUEUD) {
                // handler.postDelayed(Runnable {
                Timber.d("Ready to render (layouting) ${queueEntry.state}. Queue $queue")
                queueEntry.queuedState = QueuedState.WAITING_TO_BE_DRAWN
                dispatchRendering(queueEntry.state)
                // }, 1000)
            } else {
                Timber.d("Cannot dispatchNextWaitingStateIfNothingWaitedToBeDrawn() because head of queue is already waiting to be drawn $queue")
            }
        }
    }

    override fun onPreDraw(): Boolean {
        Timber.d("onPreDraw. Queue $queue")
        if (queue.isNotEmpty()) {
            val topOfQueue = queue.peek()
            Timber.d("Top of the queue $topOfQueue")
            if (topOfQueue.queuedState == QueuedState.WAITING_TO_BE_DRAWN) {
                topOfQueue.queuedState = QueuedState.WAITING_FOR_SCREENSHOT
                handler.postDelayed({
                    val (state, _) = queue.poll()
                    Screenshot.snapActivity(activity).setName("MainView State ${screenshotCounter++}")
                        .record()
                    Timber.d("Drawn $state. Screenshot taken. Queue $queue")
                    subject.onNext(state)
                    dispatchNextWaitingStateIfNothingWaitedToBeDrawn()
                }, 1000) // Wait until all frames has been drawn
            }
        }
        return true
    }

    private fun View.getActivity(): Activity {
        var context = getContext()
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = (context as ContextWrapper).baseContext
        }

        throw RuntimeException("Could not find parent Activity for $this")
    }

    private enum class QueuedState {
        ENQUEUD,
        WAITING_TO_BE_DRAWN,
        WAITING_FOR_SCREENSHOT
    }

    private data class QueueEntry(
        val state: PaginationStateMachine.State,
        var queuedState: QueuedState
    )
}