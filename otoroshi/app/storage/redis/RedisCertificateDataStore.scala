package storage.redis

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.Cancellable
import env.Env
import models.Key
import otoroshi.utils.LetsEncryptHelper
import play.api.Logger
import play.api.libs.json.Format
import redis.RedisClientMasterSlaves
import security.IdGenerator
import ssl.{Cert, CertificateDataStore, DynamicSSLEngineProvider, PemHeaders}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class RedisCertificateDataStore(redisCli: RedisClientMasterSlaves, _env: Env)
    extends CertificateDataStore
    with RedisStore[Cert] {

  val logger = Logger("otoroshi-certificate-datastore")

  override def _redis(implicit env: Env): RedisClientMasterSlaves = redisCli
  override def fmt: Format[Cert]                                  = Cert._fmt
  override def key(id: String): Key                               = Key.Empty / _env.storageRoot / "certs" / id
  override def extractId(value: Cert): String                     = value.id

  val lastUpdatedKey = (Key.Empty / _env.storageRoot / "certs-last-updated").key

  val lastUpdatedRef = new AtomicReference[String]("0")
  val cancelRef      = new AtomicReference[Cancellable](null)
  val cancelRenewRef = new AtomicReference[Cancellable](null)
  val cancelCreateRef = new AtomicReference[Cancellable](null)

  def startSync(): Unit = {
    implicit val ec  = _env.otoroshiExecutionContext
    implicit val mat  = _env.otoroshiMaterializer
    implicit val env = _env
    importInitialCerts(logger)
    cancelRenewRef.set(_env.otoroshiActorSystem.scheduler.schedule(60.seconds, 1.hour + ((Math.random() * 10) + 1).minutes) {
      _env.datastores.certificatesDataStore.renewCertificates()
    })
    cancelCreateRef.set(_env.otoroshiActorSystem.scheduler.schedule(60.seconds, 2.minutes) {
      LetsEncryptHelper.createFromServices()
    })
    cancelRef.set(_env.otoroshiActorSystem.scheduler.schedule(2.seconds, 2.seconds) {
      for {
        certs <- findAll()
        last  <- redisCli.get(lastUpdatedKey).map(_.map(_.utf8String).getOrElse("0"))
      } yield {
        if (last != lastUpdatedRef.get()) {
          lastUpdatedRef.set(last)
          DynamicSSLEngineProvider.setCertificates(certs, env)
        }
      }
    })
  }

  def stopSync(): Unit = {
    Option(cancelCreateRef.get()).foreach(_.cancel())
    Option(cancelRenewRef.get()).foreach(_.cancel())
    Option(cancelRef.get()).foreach(_.cancel())
  }

  override def delete(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = super.delete(id).andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def delete(value: Cert)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    super.delete(value).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }

  override def deleteAll()(implicit ec: ExecutionContext, env: Env): Future[Long] = super.deleteAll().andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def set(value: Cert, pxMilliseconds: Option[Duration] = None)(implicit ec: ExecutionContext,
                                                                         env: Env): Future[Boolean] =
    super.set(value, pxMilliseconds).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }

  override def exists(id: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = super.exists(id).andThen {
    case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
  }

  override def exists(value: Cert)(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    super.exists(value).andThen {
      case _ => redisCli.set(lastUpdatedKey, System.currentTimeMillis().toString)
    }
}
