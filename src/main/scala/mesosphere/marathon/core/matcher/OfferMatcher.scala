package mesosphere.marathon.core.matcher

import org.apache.mesos.Protos.{Offer, OfferID, TaskInfo}

import scala.concurrent.duration.Deadline

/**
 * The interface of actors consuming tasks to request task launches.
 */
object OfferMatcher {
  /**
    * Send to an offer matcher to request a match.
    *
    * This should always be replied to with a LaunchTasks message.
    */
  case class MatchOffer(matchingDeadline: Deadline, remainingOffer: Offer)

  /**
    * Reply from an offer matcher to a MatchOffer. If the offer match
    * could not match the offer in any way it should simply leave the tasks
    * collection empty.
    *
    * To increase fairness between matchers, each normal matcher should only launch as
    * few tasks as possible per offer -- usually one. Multiple tasks could be used
    * if the tasks need to be colocated. The OfferMultiplexer tries to summarize suitable
    * matches from multiple offer matches into one response.
    *
    * Sending a LaunchTasks reply does not guarantee that these tasks can actually be launched.
    * The launcher of message should setup some kind of timeout mechanism.
    *
    * The receiver of this message should send a DeclineLaunch message to the launcher though if they
    * are any obvious reasons to deny launching these tasks.
    */
  case class LaunchTasks(offerId: OfferID, tasks: Seq[TaskInfo])

  /**
   * Send to the launcher of a LaunchTasks message when it becomes apparent that we cannot
   * try to launch the tasks for some reason.
   */
  case class DeclineLaunch(offerId: OfferID, tasks: Seq[TaskInfo])
}
