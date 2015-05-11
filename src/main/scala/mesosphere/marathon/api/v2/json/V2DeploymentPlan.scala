package mesosphere.marathon.api.v2.json

import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }

final case class V2DeploymentPlan(
  id: String,
  original: V2Group,
  target: V2Group,
  steps: Seq[DeploymentStep],
  version: Timestamp)

object V2DeploymentPlan {
  def apply(plan: DeploymentPlan): V2DeploymentPlan =
    V2DeploymentPlan(
      plan.id,
      V2Group(plan.original),
      V2Group(plan.target),
      plan.steps,
      plan.version)
}
