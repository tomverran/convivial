package io.tvc.convivial

import cats.MonadError
import cats.data.Validated._
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.apply._
import io.tvc.convivial.session.IdCreator
import io.tvc.convivial.storage.{Postgres, Redis}
import io.tvc.convivial.twitter.TwitterClient
import org.http4s.Uri
import org.http4s.client.oauth1.Consumer

import scala.concurrent.duration._
import scala.util.Try

case class Config(
  port: Int,
  twitter: TwitterClient.Config,
  session: IdCreator.Config,
  postgres: Postgres.Config,
  redis: Redis.Config
)

object Config {

  private def str(name: String): ValidatedNel[String, String] =
    Validated.fromOption(sys.env.get(name), NonEmptyList.of(s"$name missing"))

  private def int(name: String): ValidatedNel[String, Int] =
    str(name).andThen { s =>
      fromEither(Try(s.toInt).toEither.left.map(_ => NonEmptyList.of(s"$name: not an int")))
    }

  private def uri(name: String): ValidatedNel[String, Uri] =
    str(name).andThen(Uri.fromString(_).fold(e => invalidNel(s"$name: ${e.message}"), validNel))

  def load[F[_]](implicit F: MonadError[F, Throwable]): F[Config] =
    F.fromEither(
      (
        int("PORT").orElse(valid(8080)),
        (
          (
            str("TWITTER_CONSUMER_KEY"),
            str("TWITTER_CONSUMER_SECRET"),
          ).mapN(Consumer.apply),
          uri("TWITTER_CALLBACK")
        ).mapN(TwitterClient.Config.apply),
        str("SESSION_SECRET").map(IdCreator.Config.apply),
        (
          str("DATABASE_JDBC_URL").orElse(str("JDBC_DATABASE_URL")),
          str("DATABASE_USERNAME").orElse(str("JDBC_DATABASE_USERNAME")),
          str("DATABASE_PASSWORD").orElse(str("JDBC_DATABASE_PASSWORD"))
        ).mapN(Postgres.Config),
        (
          str("REDIS_URL"),
          Validated.validNel(1.hour)
        ).mapN(Redis.Config)
        ).mapN(Config.apply)
       .leftMap(es => new Exception(s"Failed to load config:\n${es.toList.mkString("\n")}"))
       .toEither
    )
}
