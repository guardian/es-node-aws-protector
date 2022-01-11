package protector

import software.amazon.awssdk.services.ec2.model.Instance

import java.time.{Clock, Duration}
import scala.jdk.CollectionConverters._

object EC2Instances {
  implicit class RichInstance(instance: Instance) {
    val tags: Map[String, String] = instance.tags.asScala.map(tag => tag.key -> tag.value).toMap
    val asgName: Option[String] = tags.get("aws:autoscaling:groupName")
    val name: Option[String] = tags.get("Name")

    def age(implicit clock: Clock = Clock.systemUTC()): Duration = Duration.between(instance.launchTime, clock.instant())
  }
}