package protector.discovery

import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.InstanceStateName.RUNNING
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, Filter, Instance}

import scala.jdk.CollectionConverters._

class InstanceDiscovery(
  ec2Client: Ec2Client,
  vpcId: String,
  tagFilters: Map[String, String]
) {

  def discoverEC2Instances(): Set[Instance] = {
    val describeInstancesResponse =
      ec2Client.describeInstances(requestFor(
        Map("vpc-id" -> vpcId) ++ prefixKeys("tag", tagFilters)
      ))

    describeInstancesResponse.reservations.asScala.toSet.flatMap(_.instances.asScala).filter(_.state.name == RUNNING)
  }

  private def requestFor(filterKeyAndValues: Map[String, String]) = DescribeInstancesRequest.builder().filters(
    filterKeyAndValues.map {
      case (key, value) => Filter.builder().name(key).values(Seq(value).asJava).build()
    }.toSeq.asJava
  ).build()

  private def prefixKeys(filterType: String, keyAndValues: Map[String, String]): Map[String, String] =
    for ((key, value) <- keyAndValues) yield s"$filterType:$key" -> value
}
