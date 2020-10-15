package org.broadinstitute.dsde.workbench.leonardo.util

import cats.effect.{Async, Effect, Timer}
import cats.implicits._
import com.google.common.cache.CacheStats
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

import scala.concurrent.duration._

class CacheMetrics[F[_]] private (name: String, interval: FiniteDuration)(implicit F: Async[F],
                                                                          timer: Timer[F],
                                                                          metrics: OpenTelemetryMetrics[F],
                                                                          logger: Logger[F]) {
  def process(sizeF: () => F[Long], statsF: () => F[CacheStats]): Stream[F, Unit] =
    (Stream.sleep[F](interval) ++ Stream.eval(recordMetrics(sizeF, statsF))).repeat

  private def recordMetrics(sizeF: () => F[Long], statsF: () => F[CacheStats]): F[Unit] =
    for {
      size <- sizeF()
      _ <- metrics.gauge(s"cache/$name/size", size)
      _ <- logger.info(s"CacheMetrics: $name size: $size")
      stats <- statsF()
      _ <- logger.info(s"CacheMetrics: $name stats: ${stats.toString}")
      _ <- metrics.gauge(s"cache/$name/hitCount", stats.hitCount)
      _ <- metrics.gauge(s"cache/$name/missCount", stats.missCount)
      _ <- metrics.gauge(s"cache/$name/loadSuccessCount", stats.loadSuccessCount)
      _ <- metrics.gauge(s"cache/$name/loadExceptionCount", stats.loadExceptionCount)
      _ <- metrics.gauge(s"cache/$name/totalLoadTime", stats.totalLoadTime)
      _ <- metrics.gauge(s"cache/$name/evictionCount", stats.evictionCount)
    } yield ()
}
object CacheMetrics {
  def apply[F[_]: Timer: Effect: OpenTelemetryMetrics: Logger](name: String,
                                                               interval: FiniteDuration = 1 minute): CacheMetrics[F] =
    new CacheMetrics(name, interval)
}
