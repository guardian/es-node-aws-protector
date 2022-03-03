package protector.discovery

import com.madgag.scala.collection.decorators._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.cluster.ClusterStatsRequest
import com.sksamuel.elastic4s.requests.nodes.NodeInfoRequest
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import protector.HostAddresses
import protector.analysis.Cluster
import protector.discovery.Boo.FunkStar
import software.amazon.awssdk.services.ec2.model.Instance

import scala.concurrent.{ExecutionContext, Future}

case class ClusterDiscovery(
  cluster: Cluster,
  instancesByNode: Map[Cluster.Node, Instance]
) {
  val instances: Set[Instance] = instancesByNode.values.toSet
}
case class InstanceAndClusterCensus(
  clusterDiscoveryByClusterUuid: Map[String, ClusterDiscovery],
  allCandidateInstances: Set[Instance]
) {
  val instancesMatchingClusters: Set[Instance] =
    clusterDiscoveryByClusterUuid.values.toSet.flatMap(_.instances)

  require(instancesMatchingClusters.diff(allCandidateInstances).isEmpty)
}

case class ClientDetails(client: ElasticClient, matchingInstancesAreKnown: Option[Set[Instance]])

class ElasticsearchClustersDiscovery(
  instanceDiscovery: InstanceDiscovery,
  funkStar: FunkStar
)(implicit ec: ExecutionContext) {

  def matchUp(client: ElasticClient, candidateInstances: Set[Instance]): Future[ClusterDiscovery] = for {
    cluster <- Cluster.forClient(client)
  } yield ClusterDiscovery(cluster, (for {
    node <- cluster.nodes
    instance <- candidateInstances.find(_.privateIpAddress == node.ip.toString)
  } yield node -> instance).toMap)

  def execute(): Future[InstanceAndClusterCensus] = {
    val allInstances: Set[Instance] = instanceDiscovery.discoverEC2Instances()
    for {
      clientsByUuid <- funkStar.getClientDetailsByUuidGiven(allInstances)
      clusterDiscoveryByClusterUuid <- Future.traverse(clientsByUuid.toSeq) {
        case (clusterUuid, clientDetails) =>
          matchUp(clientDetails.client, clientDetails.matchingInstancesAreKnown.getOrElse(allInstances)).map(clusterUuid -> _)
      }
    } yield InstanceAndClusterCensus(clusterDiscoveryByClusterUuid.toMap, allInstances)
  }
}

object Boo {

  trait FunkStar {
    implicit val ec: ExecutionContext

    def getClientDetailsByUuidGiven(allInstances: Set[Instance]): Future[Map[String, ClientDetails]]

    def elasticClientFor(hostAddresses: HostAddresses): ElasticClient =
      ElasticClient(JavaClient(ElasticProperties(hostAddresses.elasticNodeEndpoints)))

    def clusterUuidFor(elasticClient: ElasticClient): Future[String] = for {
      response <- elasticClient.execute(ClusterStatsRequest())
    } yield response.result.clusterUUID
  }

  case class FixedProxy(hostAddress: HostAddresses)(implicit val ec: ExecutionContext) extends FunkStar {
    override def getClientDetailsByUuidGiven(allInstances: Set[Instance]): Future[Map[String, ClientDetails]] = {
      val client = elasticClientFor(hostAddress)
      for {
        clusterUuid <- clusterUuidFor(client)
      } yield Map(clusterUuid -> ClientDetails(client, matchingInstancesAreKnown = None))
    }
  }

  case class MultipleDirectAccess(restApiPort: Int = 9200)(implicit val ec: ExecutionContext) extends FunkStar {
    def elasticClientFor(instances: Set[Instance]): ElasticClient =
      elasticClientFor(HostAddresses(instances.map(_.publicDnsName).toSeq, restApiPort))

    override def getClientDetailsByUuidGiven(allInstances: Set[Instance]): Future[Map[String, ClientDetails]] = for {
      clusterUuidsAndInstances <- Future.traverse(allInstances)(instance => clusterUuidFor(instance).map(_ -> instance))
    } yield clusterUuidsAndInstances.groupMap(_._1)(_._2).mapV { clusterInstances =>
      ClientDetails(elasticClientFor(clusterInstances), matchingInstancesAreKnown=Some(clusterInstances))
    }

    def clusterUuidFor(instance: Instance): Future[String] = clusterUuidFor(elasticClientFor(Set(instance)))
  }
}