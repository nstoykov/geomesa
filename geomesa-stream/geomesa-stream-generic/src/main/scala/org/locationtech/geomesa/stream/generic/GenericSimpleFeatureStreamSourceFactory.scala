package org.locationtech.geomesa.stream.generic

import java.util.concurrent.{ExecutorService, Executors, LinkedBlockingQueue, TimeUnit}

import com.google.common.collect.Queues
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.camel.CamelContext
import org.apache.camel.impl._
import org.apache.camel.scala.dsl.builder.RouteBuilder
import org.locationtech.geomesa.convert.Transformers.EvaluationContext
import org.locationtech.geomesa.convert.{SimpleFeatureConverter, SimpleFeatureConverters}
import org.locationtech.geomesa.stream.{SimpleFeatureStreamSource, SimpleFeatureStreamSourceFactory}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.slf4j.LoggerFactory

import scala.util.Try

class GenericSimpleFeatureStreamSourceFactory extends SimpleFeatureStreamSourceFactory {

  lazy val ctx: CamelContext = {
    val context = new DefaultCamelContext()
    context.start()
    context
  }

  override def canProcess(conf: Config): Boolean =
    if(conf.hasPath("type") && conf.getString("type").equals("generic")) true
    else false

  override def create(conf: Config): SimpleFeatureStreamSource = {
    val sourceRoute = conf.getString("source-route")
    val sft = SimpleFeatureTypes.createType(conf.getConfig("sft"))
    val threads = Try(conf.getInt("threads")).getOrElse(1)
    val converterConf = ConfigFactory.empty().withValue("converter", conf.getValue("converter"))
    val fac = () => SimpleFeatureConverters.build[String](sft, converterConf)
    new GenericSimpleFeatureStreamSource(ctx, sourceRoute, sft, threads, fac)
  }
}

class GenericSimpleFeatureStreamSource(val ctx: CamelContext,
                                       sourceRoute: String,
                                       val sft: SimpleFeatureType,
                                       threads: Int,
                                       parserFactory: () => SimpleFeatureConverter[String])
  extends SimpleFeatureStreamSource {

  private val logger = LoggerFactory.getLogger(classOf[GenericSimpleFeatureStreamSource])
  var inQ: LinkedBlockingQueue[String] = null
  var outQ: LinkedBlockingQueue[SimpleFeature] = null
  var parsers: Seq[SimpleFeatureConverter[String]] = null
  var es: ExecutorService = null

  override def init(): Unit = {
    super.init()
    inQ = Queues.newLinkedBlockingQueue[String]()
    outQ = Queues.newLinkedBlockingQueue[SimpleFeature]()
    val route = getProcessingRoute(inQ)
    ctx.addRoutes(route)
    parsers = List.fill(threads)(parserFactory())
    es = Executors.newCachedThreadPool()
    parsers.foreach { p => es.submit(getQueueProcessor(p))}
  }

  def getProcessingRoute(inQ: LinkedBlockingQueue[String]): RouteBuilder = new RouteBuilder {
    from(sourceRoute).process { e => inQ.put(e.getIn.getBody.asInstanceOf[String]) }
  }

  override def next: SimpleFeature = outQ.poll(500, TimeUnit.MILLISECONDS)

  def getQueueProcessor(p: SimpleFeatureConverter[String]) = {
    new Runnable {
      override def run(): Unit = {
        implicit val ec = new EvaluationContext(null, null)
        var running = true
        while (running) {
          try {
            val s = inQ.poll(500, TimeUnit.MILLISECONDS)
            if (s != null) {
              try {
                p.processSingleInput(s).foreach { res => outQ.put(res) }
              } catch {
                case t: Throwable =>
                  logger.debug(s"Could not process: '$s'")
              }
            }
          } catch {
            case t: InterruptedException =>
              running = false

            case t: Throwable =>
              logger.debug("Failed", t)
          }
        }
      }
    }
  }

}
