package protector

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent
import com.google.common.base.Splitter
import protector.AWS._

import scala.collection.convert.ImplicitConversions._

case class EC2Cluster(clusterName: String, appTag: String) {

  def identifyAndProtectEssentialInstances() {
    val instances = AWS.instancesWithAppTag(appTag)
    println(s"Got ${instances.size} EC2 instances")
    
    val instancesByName: Map[String, Instance] = (instances.map(i => i.name -> i) collect {
      case (Some(name), instance) => name -> instance
    }).toMap

    val clusterSummary = Elasticsearch.clusterSummaryFor(clusterName, instances)

    val namesOfNodesToProtect: Set[String] = clusterSummary.nodesThatShouldBeProtected.map(_.name)

    val instancesByProtection: Map[Boolean, Set[Instance]] =
      instancesByName.groupBy { case (name, instance) => namesOfNodesToProtect.contains(name) }.mapValues(_.values.toSet)

    for ((protect, instances) <- instancesByProtection) {
      println(s"protect=$protect instances=${instances.map(_.getInstanceId).mkString(",")}")
      AWS.setProtection(protect, instances)
    }
  }
}

object EC2Cluster extends Function2[String, String, EC2Cluster] {

  val splitter = Splitter.on(',').trimResults.withKeyValueSeparator(':')

  def from(multiText : String): Set[EC2Cluster] =
    splitter.split(multiText).toMap.map(EC2Cluster.tupled).toSet
}

object Lambda {

  def main(args: Array[String]) = {
    EC2Cluster.from(args(0)).foreach(_.identifyAndProtectEssentialInstances())
  }

  /*
   * Lambda's entry point
   */
  def handler(input: ScheduledEvent, context: Context): Unit = {
    EC2Cluster.from(System.getenv("ES_CLUSTER_APP_TAGS")).foreach(_.identifyAndProtectEssentialInstances())
  }

}

