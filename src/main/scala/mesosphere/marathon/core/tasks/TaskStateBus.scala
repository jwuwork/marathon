package mesosphere.marathon.core.tasks

import org.apache.mesos.Protos.{TaskID, TaskStatus}

sealed trait MarathonTaskStatus {
  def terminal: Boolean = false

  def mesosStatus: Option[TaskStatus]
  def mesosHealth: Option[Boolean] = mesosStatus.flatMap { status =>
    if (status.hasHealthy) Some(status.getHealthy) else None
  }
}

object MarathonTaskStatus {
  case object LaunchRequested extends MarathonTaskStatus {
    override def mesosStatus: Option[TaskStatus] = None
  }
  case class Staging(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  case class Starting(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  case class Running(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus

  sealed trait Terminal extends MarathonTaskStatus {
    override def terminal: Boolean = true
  }
  object Terminal {
    def unapply(terminal: Terminal): Option[Terminal] = Some(terminal)
  }
  case class Finished(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Failed(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Killed(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Lost(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Error(mesosStatus: Option[TaskStatus]) extends Terminal
}

object TaskStateBus {

  case class TaskStatusUpdate(taskID: TaskID, status: MarathonTaskStatus)
}

/**
 * Allows notifications about task states.
 */
trait TaskStateBus {

}
