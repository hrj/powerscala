package org.powerscala.hierarchy.event

import org.powerscala.event.processor.EventProcessor
import org.powerscala.event.{ListenMode, Listenable, EventState}
import org.powerscala.hierarchy.ChildLike
import org.powerscala.TypeFilteredIterator

/**
 * AncestorProcessor processes up the ancestry tree through parents firing events in DescentOf mode.
 *
 * @author Matt Hicks <matt@outr.com>
 */
trait AncestorProcessor[E, V, R] extends EventProcessor[E, V, R] {
  /**
   * If true the Descendants processing will run upon receipt of event.
   */
  protected def processAncestors = true

  override protected def fireAdditional(state: EventState[E], mode: ListenMode, listenable: Listenable) = {
    super.fireAdditional(state, mode, listenable)

    if (mode == ListenMode.Standard && processAncestors) {      // Only process ancestors on standard processing
      // Process up to the ancestry tree
      TypeFilteredIterator[Listenable](ChildLike.ancestors(listenable)).foreach {
        case parentListenable => if (!state.isStopPropagation && AncestorProcessor.shouldProcess) {
          fireInternal(state, Descendants, listenable)
        }
      }
    }
  }
}

object AncestorProcessor {
  private val doNotProcessKey = "ancestorsDoNotProcess"

  /**
   * For the current event processing don't process ancestors.
   */
  def doNotProcess() = EventState.current(doNotProcessKey) = true

  def shouldProcess = !EventState.current.getOrElse(doNotProcessKey, false)
}