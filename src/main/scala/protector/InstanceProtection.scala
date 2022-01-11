package protector

import com.jakewharton.fliptables.FlipTable
import com.madgag.scala.collection.decorators._
import protector.EC2Instances._
import protector.analysis.Cluster.Node
import protector.analysis.{Cluster, NodeCriterion, RequiredNodesSummary}
import software.amazon.awssdk.services.ec2.model._


case class InstanceProtection(requiredNodesSummary: RequiredNodesSummary, instances: Seq[Instance]) {

  val instancesByName: Map[String, Instance] = (instances.map(i => i.name -> i) collect {
    case (Some(name), instance) => name -> instance
  }).toMap

  val instancesWithoutName: Seq[Instance] = instances.filter(_.name.isEmpty)

  def instanceForNode(node: Cluster.Node): Option[Instance] = instancesByName.get(node.name)

  val instancesByProtection: Map[Boolean, Seq[Instance]] =
    requiredNodesSummary.nodesByNeed.mapV(_.toSeq.flatMap(instanceForNode))

  val cluster: Cluster = requiredNodesSummary.cluster

  val summaryTableOrdering: Ordering[Node] =
    Ordering.by { n: Node => (!cluster.master.contains(n), !n.isMasterEligible, n.name) }

  lazy val summaryTable: String = {
    val headerRow = Seq(
      "Instance",
      "Name & IP",
      "Type",
      "Uptime (days)",
      "Protected"
    ) ++ NodeCriterion.All.map(_.lineSeparatedName)
    val nodeRows = cluster.nodes.toSeq.sorted(summaryTableOrdering).map {
      node => {
        val criteriaProtectingNode = requiredNodesSummary.activatedCriteriaByNode(node)
        val instanceOpt = instancesByName.get(node.name)
        Seq(
          instanceOpt.map(_.instanceId).getOrElse("UNKNOWN\nCAN NOT\nPROTECT").mkString,
          Seq(node.name,node.ip.getHostAddress).mkString("\n"),
          (Option(if (cluster.master.contains(node)) "M" else "m").filter(_ => node.isMasterEligible) ++
            Option("d").filter(_ => node.isData)).mkString,
          node.uptime.toDays.toString,
          Option("YES").filter(_ => criteriaProtectingNode.nonEmpty).mkString
        ) ++ NodeCriterion.All.map {
          criterion =>
            Option("*").filter(_ => criteriaProtectingNode(criterion)).mkString
        }
      }
    }

    val clusterTable =
      s"Cluster contains ${cluster.nodes.size} nodes (${cluster.masterEligibleNodes.size} master-eligible, ${cluster.dataNodes.size} data):\n" +
        FlipTable.of(headerRow.toArray, nodeRows.map(_.toArray).toArray)

    val namedInstancesNotInCluster = instancesByName.view.filterKeys(!cluster.nodeNames(_))
    val tableOfNamedInstancesNotInCluster = Option(namedInstancesNotInCluster).filter(_.nonEmpty).map { _ =>
      s"EC2 contains ${namedInstancesNotInCluster.size} named instances tagged for the ES cluster but *not* reported in ES cluster:\n" +
        FlipTable.of(Array("Instance", "Name"), namedInstancesNotInCluster.toSeq.sortBy(_._1).map {
          case (name, instance) => Array(instance.instanceId,name)
        }.toArray)
    }

    val listOfUnnamedInstances = Option(instancesWithoutName).filter(_.nonEmpty).map { _ =>
      s"EC2 contains ${instancesWithoutName.size} **unnamed** instances tagged for the ES cluster: ${instancesWithoutName.map(i => s"'${i.instanceId}'").mkString}"
    }

    (Seq(clusterTable) ++ tableOfNamedInstancesNotInCluster ++ listOfUnnamedInstances).mkString("\n")
  }
}
