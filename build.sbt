name := "es-node-aws-protector"

organization := "com.gu"

description:= "Fetching the latest GeoIP database and putting it in S3 for Ophan"

version := "1.0"

scalaVersion := "2.13.8"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8"
)

val elastic4sVersion = "7.16.0"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.1",
  "com.amazonaws" % "aws-lambda-java-events" % "3.11.0",

  "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
  "com.sksamuel.elastic4s" %% "elastic4s-client-sniffed" % elastic4sVersion,

  "net.logstash.logback" % "logstash-logback-encoder" % "7.0.1",
  "org.slf4j" % "log4j-over-slf4j" % "1.7.32", //  log4j-over-slf4j provides `org.apache.log4j.MDC`, which is dynamically loaded by the Lambda runtime
  "ch.qos.logback" % "logback-classic" % "1.2.10",
  "com.lihaoyi" %% "upickle" % "1.5.0",
  "com.google.guava" % "guava" % "31.1-jre",

  // "org.elasticsearch" % "elasticsearch" % "2.4.6",
  "com.madgag" %% "scala-collection-plus" % "0.11",
  "com.jakewharton.fliptables" % "fliptables" % "1.1.0",
  "org.scalatest" %% "scalatest" % "3.2.10" % Test
) ++ Seq("ec2", "autoscaling", "cloudwatch", "url-connection-client").map(artifact => "software.amazon.awssdk" % artifact % "2.17.105")

enablePlugins(RiffRaffArtifact, BuildInfoPlugin)

assemblyJarName := s"${name.value}.jar"
riffRaffPackageType := assembly.value
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffArtifactResources += (file("cfn.yaml"), s"cloudformation/cfn.yaml")

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _ => MergeStrategy.first
}


buildInfoPackage := "protector"
buildInfoKeys := {
  lazy val buildInfo = com.gu.riffraff.artifact.BuildInfo(baseDirectory.value)
  Seq[BuildInfoKey](
    "buildNumber" -> buildInfo.buildIdentifier,
    "gitCommitId" -> buildInfo.revision,
    "buildTime" -> System.currentTimeMillis
  )
}
