package protector

import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.autoscaling.{AutoScalingClient, AutoScalingClientBuilder}
import software.amazon.awssdk.services.cloudwatch.{CloudWatchClient, CloudWatchClientBuilder}
import software.amazon.awssdk.services.ec2.{Ec2AsyncClient, Ec2AsyncClientBuilder, Ec2Client, Ec2ClientBuilder}

object AWS {
  val region: Region = EU_WEST_1

  def credentialsForDevAndProd(devProfile: String, prodCreds: AwsCredentialsProvider): AwsCredentialsProviderChain =
    AwsCredentialsProviderChain.of(prodCreds, ProfileCredentialsProvider.builder().profileName(devProfile).build())

  lazy val credentials: AwsCredentialsProvider =
    credentialsForDevAndProd("ophan", EnvironmentVariableCredentialsProvider.create())

  private val sdkHttpClient: SdkHttpClient = UrlConnectionHttpClient.builder().build()

  def buildSync[T, B <: AwsClientBuilder[B, T] with SdkSyncClientBuilder[B, T]](builder: B): T =
    builder.httpClient(sdkHttpClient).credentialsProvider(credentials).region(region).build()

  lazy val EC2Sync = buildSync[Ec2Client, Ec2ClientBuilder](Ec2Client.builder())
  lazy val ASGSync = buildSync[AutoScalingClient, AutoScalingClientBuilder](AutoScalingClient.builder())
  lazy val CloudWatchSync = buildSync[CloudWatchClient, CloudWatchClientBuilder](CloudWatchClient.builder())

}
