package protector

import protector.EC2Instances._
import protector.logging.Logging
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.SetInstanceProtectionRequest
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{AttributeBooleanValue, Instance, ModifyInstanceAttributeRequest}

import scala.jdk.CollectionConverters._

class InstanceProtectionUpdater(
  ec2ApiClient: Ec2Client,
  asgApiClient :AutoScalingClient
) extends Logging {

  def execute(instanceProtection: InstanceProtection): Unit = for ((protect, instances) <- instanceProtection.instancesByProtection) {
    setProtection(protect, instances)
  }

  def setProtection(protect: Boolean, instances: Seq[Instance]): Unit = {
    logger.info(s"protect=$protect for ${instances.size} instances:\n${pretty(instances)}\n")
    instances.foreach(disableApiTermination(protect))
    for ((asgName, asgInstances) <- instancesGroupedByASG(instances)) {
      protectInstancesInASG(protect, asgName, asgInstances)
    }
  }

  private def pretty(instances: Iterable[Instance]): String =
    instances.toSeq.sortBy(_.name).map(i => s"  ${i.instanceId} (name='${i.name.mkString}')").mkString("\n")

  private def disableApiTermination(protect: Boolean)(instance: Instance): Unit = {
    ec2ApiClient.modifyInstanceAttribute(ModifyInstanceAttributeRequest.builder
      .instanceId(instance.instanceId)
      .disableApiTermination(AttributeBooleanValue.builder().value(protect).build())
      .build()
    )
  }

  private def protectInstancesInASG(protect: Boolean, asgName: String, asgInstances: Seq[Instance]): Unit = {
    asgApiClient.setInstanceProtection(SetInstanceProtectionRequest.builder
      .autoScalingGroupName(asgName)
      .instanceIds(asgInstances.map(_.instanceId).asJava)
      .protectedFromScaleIn(protect)
      .build()
    )
  }

  private def instancesGroupedByASG(instances: Seq[Instance]): Map[String, Seq[Instance]] =
    instances.groupBy(_.asgName) collect { case (Some(asgName), asgInstances) => asgName -> asgInstances }

}
