/*
 *
 *  * Copyright 2017-2020 Lenses.io Ltd
 *
 */

package io.lenses.connect.secrets.providers

import io.lenses.connect.secrets.config.{VaultProviderConfig, VaultSettings}
import io.lenses.connect.secrets.connect._
import com.bettercloud.vault.Vault
import io.lenses.connect.secrets.async.AsyncFunctionLoop
import io.lenses.connect.secrets.io.FileWriterOnce
import io.lenses.connect.secrets.utils.EncodingAndId
import org.apache.kafka.common.config.ConfigData
import org.apache.kafka.common.config.provider.ConfigProvider
import org.apache.kafka.connect.errors.ConnectException

import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class VaultSecretProvider() extends ConfigProvider with VaultHelper {

  private var settings: VaultSettings = _
  private var vaultClient: Option[Vault] = None
  private var tokenRenewal: Option[AsyncFunctionLoop] = None
  private val cache =
    mutable.Map.empty[String, (Option[OffsetDateTime], ConfigData)]
  def getClient: Option[Vault] = vaultClient

  // configure the vault client
  override def configure(configs: util.Map[String, _]): Unit = {
    settings = VaultSettings(VaultProviderConfig(configs))
    vaultClient = Some(createClient(settings))
    val renewalLoop =
      new AsyncFunctionLoop(settings.tokenRenewal, "Vault Token Renewal")(
        renewToken()
      )
    tokenRenewal = Some(renewalLoop)
    renewalLoop.start()
  }

  def tokenRenewalSuccess: Long = tokenRenewal.map(_.successRate).getOrElse(-1)
  def tokenRenewalFailure: Long = tokenRenewal.map(_.failureRate).getOrElse(-1)

  private def renewToken(): Unit = {
    vaultClient.foreach { client => client.auth().renewSelf() }
  }

  // lookup secrets at a path
  override def get(path: String): ConfigData = {
    val (expiry, data) = cache.get(path) match {
      case Some((expiresAt, data)) =>
        // we have all the keys and are before the expiry
        val now = OffsetDateTime.now()

        if (expiresAt.getOrElse(now.plusSeconds(1)).isAfter(now)) {
          logger.info("Fetching secrets from cache")
          (expiresAt, data)
        } else {
          // missing some or expired so reload
          getSecretsAndExpiry(getSecrets(path))
        }

      case None =>
        getSecretsAndExpiry(getSecrets(path))
    }

    expiry.foreach(exp =>
      logger.info(s"Min expiry for TTL set to [${exp.toString}]")
    )
    cache += (path -> (expiry, data))
    data
  }

  // get secret keys at a path
  override def get(path: String, keys: util.Set[String]): ConfigData = {

    val (expiry, data) = cache.get(path) match {
      case Some((expiresAt, data)) =>
        // we have all the keys and are before the expiry
        val now = OffsetDateTime.now()

        if (keys.asScala.subsetOf(data.data().asScala.keySet) && expiresAt
              .getOrElse(now.plusSeconds(1))
              .isAfter(now)) {
          logger.info("Fetching secrets from cache")
          (
            expiresAt,
            new ConfigData(
              data
                .data()
                .asScala
                .view
                .filter {
                  case (k, _) => keys.contains(k)
                }
                .toMap
                .asJava,
              data.ttl()
            )
          )
        } else {
          // missing some or expired so reload
          getSecretsAndExpiry(getSecrets(path).view.filter {
            case (k, _) => keys.contains(k)
          }.toMap)
        }

      case None =>
        getSecretsAndExpiry(getSecrets(path).view.filter {
          case (k, _) => keys.contains(k)
        }.toMap)
    }

    expiry.foreach(exp =>
      logger.info(s"Min expiry for TTL set to [${exp.toString}]")
    )
    cache += (path -> (expiry, data))
    data
  }

  override def close(): Unit = {
    tokenRenewal.foreach(_.close())
  }

  // get the secrets and ttl under a path
  def getSecrets(
      path: String
  ): Map[String, (String, Option[OffsetDateTime])] = {
    val now = OffsetDateTime.now()

    logger.info(s"Looking up value at [$path]")
    val fileWriter = new FileWriterOnce(Paths.get(settings.fileDir, path))
    Try(vaultClient.get.logical().read(path)) match {
      case Success(response) =>
        if (response.getRestResponse.getStatus != 200) {
          throw new ConnectException(
            s"No secrets found at path [$path]. Vault response: ${new String(response.getRestResponse.getBody)}"
          )
        }

        val ttl = Option(vaultClient.get.logical().read(path).getLeaseDuration) match {
          case Some(duration) => Some(now.plusSeconds(duration))
          case None           => None
        }

        if (response.getData.isEmpty) {
          throw new ConnectException(
            s"No secrets found at path [$path]"
          )
        }

        response.getData.asScala.map {
          case (k, v) =>
            val encodingAndId = EncodingAndId.from(k)
            val decoded =
              decodeKey(
                encoding = encodingAndId.encoding,
                key = k,
                value = v,
                writeFileFn = { content =>
                  fileWriter.write(k.toLowerCase, content, k).toString
                }
              )

            (k, (decoded, ttl))
        }.toMap

      case Failure(exception) =>
        throw new ConnectException(
          s"Failed to fetch secrets from path [$path]",
          exception
        )
    }
  }
}
