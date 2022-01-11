package protector.discovery

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.cluster.ClusterStatsRequest
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import protector.HostAddresses
import software.amazon.awssdk.services.ec2.model.Instance

import scala.concurrent.{ExecutionContext, Future}

case class ClusterDiscovery(clusterUuid: String, instances: Set[Instance])

class ElasticsearchClustersDiscovery(instanceDiscovery: InstanceDiscovery)(implicit ec: ExecutionContext) {
  def execute(): Future[Map[String, Set[Instance]]] = {
    val instances: Set[Instance] = instanceDiscovery.discoverEC2Instances().toSet

    for {
      results <- Future.traverse(instances) { instance =>
        val address = Option(instance.publicDnsName).filter(_.nonEmpty)
        val client = ElasticClient(JavaClient(ElasticProperties(HostAddresses(address.toList, restApiPort = 9200).elasticNodeEndpoints)))
        for {
          response <- client.execute(ClusterStatsRequest())
        } yield response.result.clusterUUID -> instance
      }
    } yield results.groupMap(_._1)(_._2)
  }
}
