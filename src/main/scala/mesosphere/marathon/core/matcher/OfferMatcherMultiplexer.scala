package mesosphere.marathon.core.matcher

import akka.actor._
import akka.event.LoggingReceive
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.Protos.{Offer, OfferID, Resource, TaskInfo}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scala.concurrent.duration.Deadline
import scala.util.Random
import scala.util.control.NonFatal

/**
 * The OfferMatcherMultiplexer actor offers one interface to a
 * dynamic collection of matchers.
 */
object OfferMatcherMultiplexer {
  def props(random: Random): Props = {
    Props(new OfferMatcherMultiplexer(random))
  }

  sealed trait ChangeMatchersRequest
  case class AddMatcher(consumer: ActorRef) extends ChangeMatchersRequest
  case class RemoveMatcher(consumer: ActorRef) extends ChangeMatchersRequest

  sealed trait ChangeConsumersResponse
  case class MatcherAdded(consumer: ActorRef) extends ChangeConsumersResponse
  case class MatcherRemoved(consumer: ActorRef) extends ChangeConsumersResponse

  private val log = LoggerFactory.getLogger(getClass)
  private case class OfferData(
    offer: Offer,
    deadline: Deadline,
    sender: ActorRef,
    consumerQueue: Queue[ActorRef],
    tasks: Seq[TaskInfo])
  {
    def addConsumer(consumer: ActorRef): OfferData = copy(consumerQueue = consumerQueue.enqueue(consumer))
    def nextConsumerOpt: Option[(ActorRef, OfferData)] = {
      consumerQueue.dequeueOption map {
        case (nextConsumer, newQueue) => nextConsumer -> copy(consumerQueue = newQueue)
      }
    }

    def addTasks(added: Seq[TaskInfo]): OfferData = {
      val offerResources: Seq[Resource] = offer.getResourcesList.asScala
      val taskResources: Seq[Resource] = added.flatMap(_.getResourcesList.asScala)
      val leftOverResources = ResourceUtil.consumeResources(offerResources, taskResources)
      val leftOverOffer = offer.toBuilder.clearResources().addAllResources(leftOverResources.asJava).build()
      copy(
        offer = leftOverOffer,
        tasks = added ++ tasks
      )
    }
  }
}

private class OfferMatcherMultiplexer private(random: Random) extends Actor with ActorLogging {
  private[this] var livingMatchers: Set[ActorRef] = Set.empty
  private[this] var offerQueues: Map[OfferID, OfferMatcherMultiplexer.OfferData] = Map.empty

  override def receive: Receive = {
    Seq[Receive](
      processTerminated,
      changingConsumers,
      receiveProcessOffer,
      processLaunchTasks
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def processTerminated: Receive = LoggingReceive.withLabel("processTerminated") {
    case Terminated(consumer) =>
      livingMatchers -= consumer
  }

  private[this] def changingConsumers: Receive = LoggingReceive.withLabel("changingConsumers") {
    case OfferMatcherMultiplexer.AddMatcher(consumer) =>
      livingMatchers += consumer
      offerQueues.mapValues(_.addConsumer(consumer))
      sender() ! OfferMatcherMultiplexer.MatcherAdded(consumer)

    case OfferMatcherMultiplexer.RemoveMatcher(consumer) =>
      livingMatchers -= consumer
      sender() ! OfferMatcherMultiplexer.MatcherRemoved(consumer)
  }

  private[this] def receiveProcessOffer: Receive = LoggingReceive.withLabel("receiveProcessOffer") {
    case OfferMatcher.MatchOffer(deadline, offer) if deadline.isOverdue() =>
      sender() ! OfferMatcher.LaunchTasks(offer.getId, tasks = Seq.empty)

    case processOffer@OfferMatcher.MatchOffer(deadline, offer: Offer) =>
      // setup initial offer data
      val randomizedConsumers = random.shuffle(livingMatchers).to[Queue]
      val data = OfferMatcherMultiplexer.OfferData(offer, deadline, sender(), randomizedConsumers, Seq.empty)
      offerQueues += offer.getId -> data

      // deal with the timeout
      context.system.scheduler.scheduleOnce(deadline.timeLeft, self, OfferMatcher.LaunchTasks(offer.getId, Seq.empty))

      // process offer for the first time
      continueWithOffer(offer.getId, Seq.empty)
  }

  private[this] def processLaunchTasks: Receive = {
    case OfferMatcher.LaunchTasks(offerId, tasks) => continueWithOffer(offerId, tasks)
  }

  private[this] def continueWithOffer(offerId: OfferID, addedTasks: Seq[TaskInfo]): Unit = {
    offerQueues.get(offerId).foreach { data =>
      val dataWithTasks = try {
        data.addTasks(addedTasks)
      } catch {
        case NonFatal(e) =>
          log.error(s"unexpected error processing tasks for $offerId from ${sender()}", e)
          data
      }

      val nextConsumerOpt = if (data.deadline.isOverdue()) {
        log.warning(s"Deadline for ${data.offer.getId} overdue. Scheduled ${data.tasks.size} tasks.")
        None
      } else {
        dataWithTasks.nextConsumerOpt
      }

      nextConsumerOpt match {
        case Some((nextConsumer, newData)) =>
          nextConsumer ! OfferMatcher.MatchOffer(newData.deadline, newData.offer)
          offerQueues += offerId -> newData
        case None =>
          data.sender ! OfferMatcher.LaunchTasks(dataWithTasks.offer.getId, dataWithTasks.tasks)
          offerQueues -= offerId
      }
    }
  }
}

