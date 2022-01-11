package protector.analysis

import NodeCriterion.All
import Cluster.Node

object RequiredNodesSummary {
  def forCluster(cluster: Cluster): RequiredNodesSummary = All.foldLeft(RequiredNodesSummary(cluster)) {
    case (clusterNeed: RequiredNodesSummary, criterion: NodeCriterion) =>
      val nodesProtectedByCriterion = criterion.nodesRequiredByThisCriterionGivenRequiredNodesSoFar(clusterNeed)

      clusterNeed.copy(
        alreadyProtectedNodesByCriterion = clusterNeed.alreadyProtectedNodesByCriterion + (criterion -> nodesProtectedByCriterion)
      )
  }
}

case class RequiredNodesSummary(
  cluster: Cluster,
  alreadyProtectedNodesByCriterion: Map[NodeCriterion, Set[Node]] = Map.empty
) {
  lazy val neededNodes: Set[Node] = alreadyProtectedNodesByCriterion.values.flatten.toSet

  lazy val nodesByNeed: Map[Boolean, Set[Node]] = cluster.nodes.groupBy(neededNodes)

  lazy val activatedCriteriaByNode: Map[Node, Set[NodeCriterion]] = cluster.nodes.map {
    node => node -> alreadyProtectedNodesByCriterion.keySet.filter(c=> alreadyProtectedNodesByCriterion(c)(node))
  }.toMap

}