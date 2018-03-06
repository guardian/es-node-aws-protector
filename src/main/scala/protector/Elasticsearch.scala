package protector

import java.net.InetSocketAddress

import com.amazonaws.services.ec2.model.Instance
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.node.DiscoveryNode
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress

import scala.collection.convert.ImplicitConversions._

case class NodeSummary(
  node: DiscoveryNode,
  nodeInfo: NodeInfo,
  nodeStats: NodeStats) {

  val name = node.name()

  val hasDocs = nodeStats.getIndices.getDocs.getCount > 0

  val minMasterNodes = Option(nodeInfo.getSettings.get("discovery.zen.minimum_master_nodes")).map(_.toInt).getOrElse(1)
}

case class ClusterSummary(
  state: ClusterState,
  nodeInfoById: Map[String, NodeInfo],
  nodeStatsById: Map[String, NodeStats]) {

  private def pretty(nodes: Traversable[DiscoveryNode]): String =
    s"[${nodes.toSeq.map(_.name).sorted.mkString(",")}]"

  val discoveryNodes = state.getNodes

  val electedMasterNode: DiscoveryNode = discoveryNodes.getMasterNode

  val nonElectedMasterEligibleNodes: Set[DiscoveryNode] =
    discoveryNodes.masterNodes().valuesIt().toSet - electedMasterNode

  println(s"nonElectedMasterEligibleNodes=${pretty(nonElectedMasterEligibleNodes)}")


  val namesOfExcludedNodes: Set[String] =
    Option[String](state.getMetaData.settings().get("cluster.routing.allocation.exclude._name")).toSet.flatMap {
      str: String => str.split(",").toSet
    }

  println(s"namesOfExcludedNodes=${namesOfExcludedNodes.toSeq.sorted.mkString(",")}")

  val nodeSummaries: Set[NodeSummary] = for {
    node: DiscoveryNode <- discoveryNodes.nodes.valuesIt().toSet
    nodeInfo <- nodeInfoById.get(node.getId)
    nodeStats <- nodeStatsById.get(node.getId)
  } yield NodeSummary(node, nodeInfo, nodeStats)

  val nodesThatMightHaveData: Set[DiscoveryNode] =
    nodeSummaries.filter(_.node.isDataNode).filter(n => n.hasDocs || !namesOfExcludedNodes.contains(n.name)).map(_.node)

  val minMasterEligibleNodes = nodeSummaries.map(_.minMasterNodes).max

  println(s"minMasterEligibleNodes=$minMasterEligibleNodes")


  val nonElectedMasterEligibleNodesRequiredForQuorum: Set[DiscoveryNode] =
    nonElectedMasterEligibleNodes.toSeq.sortBy(_.name).take(minMasterEligibleNodes - 1).toSet

  println(s"nonElectedMasterEligibleNodesRequiredForQuorum=${pretty(nonElectedMasterEligibleNodesRequiredForQuorum)}")


  val nodesThatShouldBeProtected: Set[DiscoveryNode] =
    nonElectedMasterEligibleNodesRequiredForQuorum + electedMasterNode ++ nodesThatMightHaveData

}

object ClusterSummary {
  def forClient(client: TransportClient): ClusterSummary = {
    val clusterAdminClient = client.admin().cluster()

    println(s"Want state...")
    val state: ClusterState = clusterAdminClient.prepareState().get().getState
    println(s"state=${state.getClusterName}")
    val nodeInfoById: Map[String, NodeInfo] = clusterAdminClient.prepareNodesInfo().get().getNodesMap.toMap
    val nodeStatsById: Map[String, NodeStats] = clusterAdminClient.prepareNodesStats().get().getNodesMap.toMap

    ClusterSummary(state, nodeInfoById, nodeStatsById)
  }
}

object Elasticsearch {
  def clientFor(clusterName: String,instances: Traversable[Instance]) = {
    val elasticSearchHosts = instances.map(_.getPublicDnsName).filter(_.nonEmpty).toList
    val hosts = elasticSearchHosts.map(host => new InetSocketTransportAddress(new InetSocketAddress(host, 9300)))

    val settings = Settings.builder()
      .put("cluster.name", clusterName).build()

    val clientBeforeAddedTransportAddresses = TransportClient.builder().settings(settings).build()
    println(s"clientBeforeAddedTransportAddresses = $clientBeforeAddedTransportAddresses")
    val client = clientBeforeAddedTransportAddresses.addTransportAddresses(hosts: _*)
    println(s"Got client $client")
    client
  }

  def clusterSummaryFor(clusterName: String, instances: Traversable[Instance]): ClusterSummary = {
    val client = clientFor(clusterName, instances)
    val clusterSummary = ClusterSummary.forClient(client)
    client.close()
    clusterSummary
  }
}