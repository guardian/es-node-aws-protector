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

    for {
      results <- Future.traverse(allInstances) { instance =>
        val address = Option(instance.publicDnsName).filter(_.nonEmpty)
        val client = ElasticClient(JavaClient(ElasticProperties(HostAddresses(address.toList, restApiPort = 9200).elasticNodeEndpoints)))
        for {
          response <- client.execute(ClusterStatsRequest())
        } yield response.result.clusterUUID -> instance
      }
    } yield results.groupMap(_._1)(_._2)
  }
}

object Boo {

  trait FunkStar {
    def getClientDetailsByUuidGiven(allInstances: Set[Instance])(implicit ec: ExecutionContext): Future[Map[String, ClientDetails]]

    def elasticClientFor(hostAddresses: HostAddresses): ElasticClient =
      ElasticClient(JavaClient(ElasticProperties(hostAddresses.elasticNodeEndpoints)))
  }

  case class FixedProxy(hostAddress: HostAddresses) extends FunkStar {
    override def getClientDetailsByUuidGiven(allInstances: Set[Instance])(implicit ec: ExecutionContext): Future[Map[String, ClientDetails]] = ???
  }

  case class MultipleDirectAccess(restApiPort: Int = 9200) extends FunkStar {
    def elasticClientFor(instances: Set[Instance]): ElasticClient =
      elasticClientFor(HostAddresses(instances.map(_.publicDnsName).toSeq, restApiPort))

    override def getClientDetailsByUuidGiven(allInstances: Set[Instance])(implicit ec: ExecutionContext): Future[Map[String, ClientDetails]] = for {
      results <- Future.traverse(allInstances) { instance =>
        val address = Option(instance.publicDnsName).filter(_.nonEmpty)
        val singleInstanceClient = elasticClientFor(HostAddresses(address.toList, restApiPort))
        for {
          response <- singleInstanceClient.execute(ClusterStatsRequest())
        } yield response.result.clusterUUID -> instance
      }
    } yield results.groupUp(_._1) { foo =>
      val matchingInstances: Set[Instance] = foo.map(_._2)
      val client = elasticClientFor(HostAddresses(matchingInstances.map(_.publicDnsName).toSeq, restApiPort))
      ClientDetails(client, Some(matchingInstances))
    }
  }
}