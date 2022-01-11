package protector.analysis

import protector.analysis.Cluster.Node

object NodeCriterion {

  object NodesThatHaveDocuments extends NodeCriterion(_.cluster.nodes.filter(_.hasDocs))

  object DataNodesIncludedInShardAllocation extends NodeCriterion(_.cluster.dataNodesNotExcludedFromShardAllocation)

  object ElectedMasterNode extends NodeCriterion(_.cluster.master.toSet)

  val NodePreference = Ordering.by { node: Node =>  (
    node.uptime, // prefer to protect newer boxes, makes deploys easier
    node.name // to break equality of other factors and so ensure stable ordering
  )}

  object MasterEligibleNodesRequiredForQuorum extends NodeCriterion(ps => {
    val (alreadyProtectedMasterEligibleNodes, remainingMasterEligibleNodes) =
      ps.cluster.masterEligibleNodes.toSeq.partition(ps.neededNodes)

    val remainingMasterEligibleNodesGivenAStableOrdering = remainingMasterEligibleNodes.sorted(NodePreference)

    (alreadyProtectedMasterEligibleNodes ++ remainingMasterEligibleNodesGivenAStableOrdering).take(ps.cluster.minMasterEligibleNodes).toSet
  })

  val All = Seq(
    NodesThatHaveDocuments,
    DataNodesIncludedInShardAllocation,
    ElectedMasterNode,
    MasterEligibleNodesRequiredForQuorum
  )

}

case class NodeCriterion(nodesRequiredByThisCriterionGivenRequiredNodesSoFar: RequiredNodesSummary => Set[Node]) {
  val name = getClass.getSimpleName.stripSuffix("$")

  val lineSeparatedName = name.map(c => if (c.isUpper) s"\n$c" else c).mkString.stripPrefix("\n")

}
