package protector

import com.sksamuel.elastic4s.ElasticNodeEndpoint

object HostAddresses {
  def forRestEndpointUri(uriStr: String): HostAddresses = {
    val uri = new java.net.URI(uriStr)
    val port = if (uri.getPort < 0) 9200 else uri.getPort
    HostAddresses(Seq(uri.getHost), port)
  }
}

case class HostAddresses(hostDnsNames: Seq[String], restApiPort: Int) {
  lazy val elasticNodeEndpoints: Seq[ElasticNodeEndpoint] =
    hostDnsNames.map(host => ElasticNodeEndpoint("http", host, restApiPort, None))
}

