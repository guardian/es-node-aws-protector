name := "es-node-aws-protector"

organization := "com.gu"

version := "1.0"

scalaVersion := "2.12.4"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-target:jvm-1.8",
  "-Ywarn-dead-code"
)

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.0",
  "com.amazonaws" % "aws-lambda-java-events" % "2.0.2",
  "com.amazonaws" % "aws-java-sdk-autoscaling" % "1.11.288",
  "com.amazonaws" % "aws-java-sdk-ec2" % "1.11.288",
  "org.elasticsearch" % "elasticsearch" % "2.4.6",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test"

)

enablePlugins(RiffRaffArtifact)

assemblyJarName := s"${name.value}.jar"
riffRaffPackageType := assembly.value
riffRaffUploadArtifactBucket := Option("riffraff-artifact")
riffRaffUploadManifestBucket := Option("riffraff-builds")
riffRaffArtifactResources += (file("cfn.yaml"), s"${name.value}-cfn/cfn.yaml")

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}