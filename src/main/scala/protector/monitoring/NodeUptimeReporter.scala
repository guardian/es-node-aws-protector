package protector.monitoring

import protector.analysis.Cluster
import protector.logging.Logging
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model._

import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class NodeUptimeReporter(
  cloudWatchClient: CloudWatchClient
)(implicit ec: ExecutionContext) extends Logging {
  private val baseDimensions = Seq[Dimension](
    Dimension.builder.name("Stage").value("PROD").build(),
    Dimension.builder.name("Stack").value("ophan").build()
  )
  val maxNumberOfMetricItems = 20 // https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html

  def metricDatumFor(name: String, duration: Duration, extraDimension: Option[Dimension]= None): MetricDatum = MetricDatum.builder
    .metricName(name)
    .dimensions(baseDimensions ++ extraDimension: _*)
    .value(duration.getSeconds.toDouble)
    .unit(StandardUnit.SECONDS)
    .build()

  def reportUptime(cluster: Cluster): Unit = Future {
    logger.info(s"Asked to report uptime for $cluster")

    val metricData: Seq[MetricDatum] =
      for (node <- cluster.nodes.toSeq.sortBy(_.uptime).take(maxNumberOfMetricItems - 1)) yield {
        metricDatumFor("Uptime", node.uptime, Some(Dimension.builder.name("NodeName").value(node.name).build))
      }

    val minUptimeDatumOpt = for (minUptime <- cluster.nodes.map(_.uptime).toSeq.sorted.headOption) yield {
      metricDatumFor("MinimumUptime", minUptime)
    }

    val metricDataRequest = PutMetricDataRequest.builder
      .namespace("Elasticsearch")
      .metricData(metricData ++ minUptimeDatumOpt: _*)
      .build

    Try(cloudWatchClient.putMetricData(metricDataRequest)) match {
      case Failure(ex) =>
        logger.error("Could not send MinimumUptime for Elasticsearch to AWS - the AWS MinimumUptime alarm may go off", ex)
      case Success(result) =>
        logger.info(s"Sent cloudwatch metric data request-id=${result.responseMetadata.requestId}")
    }
  }
}
