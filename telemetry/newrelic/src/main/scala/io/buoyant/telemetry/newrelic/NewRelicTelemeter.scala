package io.buoyant.telemetry.newrelic

import com.fasterxml.jackson.annotation.JsonValue
import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.telemetry.Metric.{Counter, Gauge, None => MetricNone, Stat}
import io.buoyant.telemetry.{MetricsTree, Telemeter}
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.logging.Logger

class NewRelicTelemeter(
  metrics: MetricsTree,
  client: Service[Request, Response],
  licenseKey: String,
  host: String,
  timer: Timer
) extends Telemeter {
  import NewRelicTelemeter._

  private[this] lazy val log = Logger.get()

  private[this] val agent = Agent(host, NewRelicTelemeter.Version)
  private[this] val json = Parser.jsonObjectMapper(Nil)

  val stats = NullStatsReceiver
  def tracer = NullTracer

  private[this] val started = new AtomicBoolean(false)
  // only run at most once
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val task = timer.schedule(NewRelicTelemeter.Interval) {
      sendMetrics()
    }

    new Closable with CloseAwaitably {
      override def close(deadline: Time): Future[Unit] = closeAwaitably(task.close(deadline))
    }
  }

  private[this] def sendMetrics(): Unit = {
    val payload = MetricsPayload(agent, Seq(Component(Name, Guid, Interval.inSeconds, mkMetrics())))
    val req = Request(Method.Post, NewRelicUri)
    req.headerMap.add(LicenseKeyHeader, licenseKey)
    req.withOutputStream(json.writeValue(_, payload))
    // Fire-and-forget
    val _ = client(req).onFailure { e =>
      log.warning("Failed to send metrics to New Relic: %s", e)
    }
  }

  private[this] def mkMetrics(): Map[String, Metric] =
    for {
      (key, metric) <- MetricsTree.flatten(metrics).toMap
      newRelicMetric <- metric match {
        case counter: Counter => Option(counter.get).map { ScalarIntegerMetric }
        case gauge: Gauge => Option(gauge.get).map { ScalarDecimalMetric }
        case stat: Stat =>
          Option(stat.snapshottedSummary).map { summary =>
            DistributionMetric(summary.sum, summary.count, summary.min, summary.max)
          }
        case MetricNone => None
      }
    } yield key -> newRelicMetric

}

object NewRelicTelemeter {
  val NewRelicUri = "platform/v1/metrics"
  val LicenseKeyHeader = "X-License-Key"
  val Version = "1.0.0" // New Relic plugin version (distinct from Linkerd version)
  val Interval = 1.minute
  val Guid = "io.l5d.newrelic"
  val Name = "Linkerd"
}

case class MetricsPayload(agent: Agent, components: Seq[Component])
case class Agent(host: String, version: String)
case class Component(name: String, guid: String, duration: Long, metrics: Map[String, Metric])

sealed trait Metric
case class ScalarIntegerMetric(@JsonValue value: Long) extends Metric
case class ScalarDecimalMetric(@JsonValue value: Float) extends Metric
case class DistributionMetric(total: Long, count: Long, min: Long, max: Long) extends Metric
