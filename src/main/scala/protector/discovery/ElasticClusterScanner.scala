package protector.discovery

import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.sksamuel.elastic4s.http.{JavaClient, JavaClientSniffed, SniffingConfiguration}
import protector.{AWS, HostAddresses}
import protector.logging.Logging
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, DescribeInstancesResponse, Filter, Instance}

import scala.jdk.CollectionConverters._



trait ElasticClusterScanner {
  def scan(): ClusterScanResult
}

object ElasticClusterScanner {
  object ConnectingEndpoints {

    /** For running in DEV & DEVTO[PROD/CODE].
     *
     * From Ophan:
     * "Note that node-sniffing is NOT wanted for clients running in dev - if we're connecting to an Elasticsearch
     * node running our dev box, the port and host are fixed, and even if we're connecting from dev to a cluster
     * hosted in AWS, we do this through a *single fixed port* made available with `es_connect.sc`, using SSM and
     * ssh port-forwarding."
     */
    case class Fixed(hostAddress: HostAddresses) extends ElasticClusterScanner {
      override def createElastic4sClientFor(addressesSnapshot: HostAddresses): ElasticClient =
        ElasticClient(JavaClient(ElasticProperties(addressesSnapshot.elasticNodeEndpoints)))
    }

    /** Programs that are running in AWS are granted direct access to all node endpoints in the Elasticsearch cluster
     * by the `AccessElasticsearch` security group specified on the cluster EC2 instances. Those endpoints need to
     * initially be discovered by AWS EC2 tagging, and then the list of those nodes needs to be kept up-to-date with a
     * client that is configured to perform node-sniffing (querying Elasticsearch to discover all new nodes as they
     * join the cluster).
     */
    case class MultipleVarying(stageTag: String) extends ElasticsearchClientFactory with Logging {

      logger.info(s"stageTag=$stageTag")
      val stageAndAppFilters = Map("Stage" -> stageTag, "App" -> "elasticsearch-7")

      def discoverEC2Instances(): Iterable[Instance] = {
        val describeInstancesResult: DescribeInstancesResponse =
          AWS.EC2Sync.describeInstances(DescribeInstancesRequest.builder().filters(
            stageAndAppFilters.map {
              case (tagName, value) => Filter.builder().name(s"tag:$tagName").values(Seq(value).asJava).build()
            }.toSeq.asJava
          ).build())

        describeInstancesResult.reservations.asScala.flatMap(_.instances.asScala).filter(_.state.name == RUNNING)
      }

      override def lookupHostAddresses(): HostAddresses = HostAddresses(
        discoverEC2Instances().map(_.publicDnsName).filter(_.nonEmpty).toList,
        restApiPort = 9200
      )

      override def createElastic4sClientFor(addressesSnapshot: HostAddresses): ElasticClient = {
        ElasticClient(JavaClientSniffed(ElasticProperties(addressesSnapshot.elasticNodeEndpoints), SniffingConfiguration()))
      }
    }

  }
}

/**
 * A cluster scan result tells us only what EC2 instances are in a cluster, and provides
 * us with an Elasticsearch client for querying the clusters state.
 *
 * By itself, it doesn't tell us anything about the state of the Elasticsearch nodes,
 * what roles they are performing, etc. That information comes in `ClusterSummary`
 */
case class ClusterScanResult(
  instances: Seq[Instance],
  client: com.sksamuel.elastic4s.ElasticClient
)