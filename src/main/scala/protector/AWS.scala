package protector

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder
import com.amazonaws.services.autoscaling.model.SetInstanceProtectionRequest
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{DescribeInstancesRequest, Filter, Instance, ModifyInstanceAttributeRequest}

import scala.collection.convert.ImplicitConversions._

object AWS {

  val credentialsProviderChain = new AWSCredentialsProviderChain(
    new ProfileCredentialsProvider("ophan"),
    new EnvironmentVariableCredentialsProvider() // Apparently needed for Lambdas, see: https://stackoverflow.com/a/31578329/438886
  )

  val region = EU_WEST_1

  implicit class RichInstance(instance: Instance) {
    val tags: Map[String, String] = instance.getTags.toSeq.map(tag => tag.getKey -> tag.getValue).toMap
    val asgName: Option[String] = tags.get("aws:autoscaling:groupName")
    val name: Option[String] = tags.get("Name")
  }

  val asgClient = AmazonAutoScalingClientBuilder.standard()
    .withCredentials(credentialsProviderChain).withRegion(region).build()


  val ec2Client = AmazonEC2ClientBuilder.standard()
    .withCredentials(credentialsProviderChain).withRegion(region).build()

  def disableApiTermination(protect: Boolean)(instance: Instance) {
    ec2Client.modifyInstanceAttribute(
      new ModifyInstanceAttributeRequest()
        .withInstanceId(instance.getInstanceId).withDisableApiTermination(protect))
  }

  def setProtection(protect: Boolean, instances: Set[Instance]) {
    println(s"Setting protect=$protect for ${instances.size} instances")
    instances.foreach(disableApiTermination(protect))
    for ((asgName, asgInstances) <- instancesGroupedByASG(instances)) {
      protectInstancesInASG(protect, asgName, asgInstances)
    }
  }

  private def protectInstancesInASG(protect: Boolean, asgName: String, asgInstances: Set[Instance]) = {
    val result = asgClient.setInstanceProtection(new SetInstanceProtectionRequest()
      .withAutoScalingGroupName(asgName)
      .withInstanceIds(asgInstances.map(_.getInstanceId))
      .withProtectedFromScaleIn(protect))
  }

  private def pretty(instances: Traversable[Instance]): String =
    s"[${instances.toSeq.sortBy(_.name).map(i => s"  ${i.getInstanceId} (name='${i.name.mkString}')").mkString("\n")}]"

  def instancesGroupedByASG(instances: Set[Instance]): Map[String, Set[Instance]] = {

    val instancesByASGName = instances.groupBy(_.asgName) collect {
      case (Some(asgName), asgInstances) => asgName -> asgInstances
    }

    println(instancesByASGName.mapValues(pretty).mkString("\n"))
    instancesByASGName
  }

  def instancesWithAppTag(appTag: String): Set[Instance] = {
    println(s"Going to get EC2 instances")
    ec2Client.describeInstances(
      new DescribeInstancesRequest().withFilters(
        new Filter("tag:Stage", List("PROD")),
        new Filter("tag:App", List(appTag)))).getReservations.flatMap(_.getInstances).toSet
  }
}
