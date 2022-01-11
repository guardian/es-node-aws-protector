package protector

import protector.EC2Instances.RichInstance
import protector.analysis.{Cluster, RequiredNodesSummary}
import protector.discovery.{ElasticClusterScanner, ElasticsearchClustersDiscovery, InstanceDiscovery}
import protector.logging.Logging
import protector.monitoring.NodeUptimeReporter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class ESNodeProtectorService(
  nodeUptimeReporter: NodeUptimeReporter,
  elasticsearchClustersDiscovery: ElasticsearchClustersDiscovery,
  elasticClusterScanner: ElasticClusterScanner,
  instanceProtectionUpdater: InstanceProtectionUpdater
) extends Logging {

  def runOnce(): Future[Unit] = {
    for {
      instancesByCluster <- elasticsearchClustersDiscovery.execute()
      _ <- Future.traverse(instancesByCluster.values) {
        instances =>

      }
    } yield ()

    val clusterScanResult = elasticClusterScanner.scan()
    for {
      clusterSummary <- Cluster.forClient(clusterScanResult.client)
    } yield {
      // as we have this data, now is a convenient time to report uptime
      nodeUptimeReporter.reportUptime(clusterSummary)

      val instanceProtection = InstanceProtection(
        RequiredNodesSummary.forCluster(clusterSummary),
        clusterScanResult.instances
      )

      logger.info(instanceProtection.summaryTable)

      instanceProtectionUpdater.execute(instanceProtection)
    }
  }
}

object ESNodeProtectorService {
  def executeAndWait(): Unit = {
    val service: ESNodeProtectorService = new ESNodeProtectorService(
      new NodeUptimeReporter(AWS.CloudWatchSync),
      elasticClusterScanner: ElasticClusterScanner,
      new InstanceProtectionUpdater(AWS.EC2Sync, AWS.ASGSync)
    );
    Await.ready(service.runOnce(), Duration.Inf)
  }

}