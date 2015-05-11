package mesosphere.marathon.integration.setup

import java.util.Date

import akka.actor.ActorSystem
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2AppUpdate, V2Group, V2GroupUpdate }
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.event.{ Subscribe, Unsubscribe }
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.HttpResponse

import scala.concurrent.Await.result
import scala.concurrent.duration._

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ListAppsResult(apps: Seq[V2AppDefinition])
case class AppVersions(versions: Seq[String])
case class ListTasks(tasks: Seq[ITEnrichedTask])
case class ITHealthCheckResult(taskId: String, firstSuccess: Date, lastSuccess: Date, lastFailure: Date, consecutiveFailures: Int, alive: Boolean)
case class ITDeploymentResult(version: String, deploymentId: String)
@JsonIgnoreProperties(ignoreUnknown = true)
case class ITEnrichedTask(appId: String, id: String, host: String, ports: Seq[Integer], startedAt: Date, stagedAt: Date, version: String /*, healthCheckResults:Seq[ITHealthCheckResult]*/ )

case class ListDeployments(deployments: Seq[Deployment])

@JsonIgnoreProperties(ignoreUnknown = true)
case class Deployment(id: String, affectedApps: Seq[String])
/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(url: String, baseGroup: PathId, waitTime: Duration = 30.seconds)(implicit val system: ActorSystem) extends JacksonSprayMarshaller {
  import mesosphere.util.ThreadPoolContext.context

  require(baseGroup.absolute)

  implicit val v2appDefMarshaller = marshaller[V2AppDefinition]
  implicit val appUpdateMarshaller = marshaller[V2AppUpdate]
  implicit val v2groupMarshaller = marshaller[V2Group]
  implicit val groupUpdateMarshaller = marshaller[V2GroupUpdate]
  implicit val versionMarshaller = marshaller[ITDeploymentResult]

  def isInBaseGroup(pathId: PathId): Boolean = {
    pathId.path.startsWith(baseGroup.path)
  }

  def requireInBaseGroup(pathId: PathId): Unit = {
    require(isInBaseGroup(pathId), s"pathId $pathId must be in baseGroup ($baseGroup)")
  }

  //app resource ----------------------------------------------

  def listAppsInBaseGroup: RestResult[List[V2AppDefinition]] = {
    val pipeline = sendReceive ~> read[ListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.toList.filter(app => isInBaseGroup(app.id)))
  }

  def app(id: PathId): RestResult[V2AppDefinition] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[V2AppDefinition]
    val getUrl: String = s"$url/v2/apps$id"
    LoggerFactory.getLogger(getClass).info(s"get url = $getUrl")
    result(pipeline(Get(getUrl)), waitTime)
  }

  def createAppV2(app: V2AppDefinition): RestResult[V2AppDefinition] = {
    requireInBaseGroup(app.id)
    val pipeline = sendReceive ~> read[V2AppDefinition]
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/apps$id?force=$force")), waitTime)
  }

  def updateApp(id: PathId, app: V2AppUpdate, force: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> responseResult
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(pipeline(Put(putUrl, app)), waitTime)
  }

  def restartApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/apps$id/restart?force=$force")), waitTime)
  }

  def listAppVersions(id: PathId): RestResult[AppVersions] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[AppVersions]
    result(pipeline(Get(s"$url/v2/apps$id/versions")), waitTime)
  }

  //apps tasks resource --------------------------------------

  def tasks(appId: PathId): RestResult[List[ITEnrichedTask]] = {
    requireInBaseGroup(appId)
    val pipeline = addHeader("Accept", "application/json") ~> sendReceive ~> read[ListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps$appId/tasks")), waitTime)
    res.map(_.tasks.toList)
  }

  def killAllTasks(appId: PathId, scale: Boolean = false): RestResult[ListTasks] = {
    requireInBaseGroup(appId)
    val pipeline = sendReceive ~> read[ListTasks]
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks?scale=$scale")), waitTime)
  }

  def killTask(appId: PathId, taskId: String, scale: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(appId)
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks/$taskId?scale=$scale")), waitTime)
  }

  //group resource -------------------------------------------

  def listGroupsInBaseGroup: RestResult[Set[Group]] = {
    val pipeline = sendReceive ~> read[Group]
    val root = result(pipeline(Get(s"$url/v2/groups")), waitTime)
    root.map(_.groups.filter(group => isInBaseGroup(group.id)))
  }

  def listGroupVersions(id: PathId): RestResult[List[String]] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[Array[String]] ~> toList[String]
    result(pipeline(Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  def group(id: PathId): RestResult[Group] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[Group]
    result(pipeline(Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: V2GroupUpdate): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(group.groupId)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups$id?force=$force")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: PathId, group: V2GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def rollbackGroup(groupId: PathId, version: String, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(groupId)
    updateGroup(groupId, V2GroupUpdate(None, version = Some(Timestamp(version))), force)
  }

  //deployment resource ------

  def listDeploymentsForBaseGroup(): RestResult[List[Deployment]] = {
    val pipeline = sendReceive ~> read[Array[Deployment]] ~> toList[Deployment]
    result(pipeline(Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).exists(id => isInBaseGroup(id))
      }
    }
  }

  //event resource ---------------------------------------------

  def listSubscribers: RestResult[EventSubscribers] = {
    val pipeline = sendReceive ~> read[EventSubscribers]
    result(pipeline(Get(s"$url/v2/eventSubscriptions")), waitTime)
  }

  def subscribe(callbackUrl: String): RestResult[Subscribe] = {
    val pipeline = sendReceive ~> read[Subscribe]
    result(pipeline(Post(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  def unsubscribe(callbackUrl: String): RestResult[Unsubscribe] = {
    val pipeline = sendReceive ~> read[Unsubscribe]
    result(pipeline(Delete(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  //metrics ---------------------------------------------

  def metrics(): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Get(s"$url/metrics")), waitTime)
  }
}
