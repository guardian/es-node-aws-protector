package protector.analysis

import com.google.common.net.InetAddresses
import com.sksamuel.elastic4s.{ElasticClient, Response}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.requests.cluster.{ClusterSettingsResponse, ClusterStateRequest, GetClusterSettingsRequest}
import protector.elastic4s.Detailed._
import protector.analysis
import protector.analysis.Cluster.{Node, NodeName, minMasterEligibleNodesToProtectFor}
import protector.logging.Logging

import java.net.InetAddress
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}

case class NodeShardAllocationExclusions(names: Set[NodeName], ips: Set[InetAddress]) {
  val hostAddresses: Set[String] = ips.flatMap(inetAddress => Option(inetAddress.getHostAddress))

  def contains(node: Node): Boolean = names.contains(node.name) || hostAddresses.contains(node.ip.getHostAddress)
}

object NodeShardAllocationExclusions {

  def apply(settingsResponse: ClusterSettingsResponse): NodeShardAllocationExclusions = {
    def valuesFor(exclusionType: String): Set[String] =
      settingsResponse.transient.get(s"cluster.routing.allocation.exclude.$exclusionType").toSet
        .flatMap[String](_.split(",").filter(_.nonEmpty))

    NodeShardAllocationExclusions(
      names = valuesFor("_name"),
      ips = valuesFor("_ip").map(InetAddresses.forString)
    )
  }
}

case class Cluster(
  nodes: Set[Node],
  masterName: Option[NodeName],
  nodeShardAllocationExclusions: NodeShardAllocationExclusions
) extends Logging {

  val nodesByName: Map[String, Node] = nodes.map(n => n.name -> n).toMap

  val nodeNames = nodesByName.keySet

  val masterEligibleNodes: Set[Node] = nodes.filter(_.isMasterEligible)

  val dataNodes: Set[Node] = nodes.filter(_.isData)

  val dataNodesNotExcludedFromShardAllocation: Set[Node] = dataNodes.filter(node => !nodeShardAllocationExclusions.contains(node))

  val master: Option[Node] = masterName.flatMap(nodesByName.get)

  val minMasterEligibleNodes: Int = minMasterEligibleNodesToProtectFor(masterEligibleNodes.size)
}

object Cluster extends Logging {

  def minMasterEligibleNodesToProtectFor(numMasterEligibleNodes: Int): Int = (numMasterEligibleNodes / 2) + 1

  type NodeName = String

  case class Node(
    name: NodeName,
    ip: InetAddress,
    isMasterEligible: Boolean,
    isData: Boolean,
    hasDocs: Boolean,
    uptime: Duration
  )

  def forClient(client: ElasticClient)(implicit ec: ExecutionContext): Future[Cluster] = {
    val nodeStatsResponseF = client.execute(DetailedNodeStatsRequest())
    val clusterStateResponseF = client.execute(ClusterStateRequest())
    val clusterSettingsResponseF = client.execute(GetClusterSettingsRequest())

    for {
      nodeStatsResponse <- nodeStatsResponseF
      clusterStateResponse <- clusterStateResponseF
      clusterSettingsResponse <- clusterSettingsResponseF
    } yield {
      logger.info(s"cluster-name=${nodeStatsResponse.result.clusterName}")
      val statsByNodeId: Map[String, DetailedNodeStats] = nodeStatsResponse.result.nodes
      val nodes = statsByNodeId.map { case (_, nodeStats) =>
        Cluster.Node(
          nodeStats.name,
          InetAddresses.forString(nodeStats.host),
          isMasterEligible = nodeStats.roles.contains("master"),
          isData = nodeStats.roles.contains("data"),
          hasDocs = nodeStats.indices.docs.hasDocs,
          uptime = nodeStats.jvm.uptime
        )
      }

      Cluster(
        nodes.toSet,
        masterName = statsByNodeId.get(clusterStateResponse.result.masterNode).map(_.name),
        NodeShardAllocationExclusions(clusterSettingsResponse.result)
      )
    }
  }
}
