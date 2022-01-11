package protector.elastic4s

import com.fasterxml.jackson.annotation.JsonProperty
import com.sksamuel.elastic4s.{ElasticRequest, Handler}

import java.time.Duration

object Detailed {

  case class Docs(count: Long) {
    val hasDocs = count > 0
  }

  case class Indices(docs: Docs)

  case class Jvm(@JsonProperty("uptime_in_millis") uptimeInMillis: Long) {
    val uptime: Duration = Duration.ofMillis(uptimeInMillis)
  }

  case class DetailedNodeStats(
    name: String,
    host: String,
    roles: List[String],
    indices: Indices,
    jvm: Jvm
  )

  case class DetailedNodesStatsResponse(
    @JsonProperty("cluster_name") clusterName: String,
    nodes: Map[String, DetailedNodeStats]
  )

  case class DetailedNodeStatsRequest()

  implicit object NodeStatsHandler extends Handler[DetailedNodeStatsRequest, DetailedNodesStatsResponse] {
    override def build(request: DetailedNodeStatsRequest): ElasticRequest = {
      ElasticRequest("GET", "/_nodes/stats/jvm,indices")
    }
  }

}