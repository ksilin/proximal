package com.example

import java.util
import java.util.Properties

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.reactive.Observable
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FreeSpec, MustMatchers}
import sttp.client.asynchttpclient.monix.AsyncHttpClientMonixBackend
import sttp.model.Uri
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, DescribeClusterResult, NewTopic}
import org.apache.kafka.common.KafkaFuture
import io.circe._
import io.circe.generic.auto._

import scala.collection.mutable
import scala.jdk.javaapi.CollectionConverters._
import scala.concurrent.Future
import concurrent.Await
import concurrent.duration._
import scala.util.Random

class RestProxyTest extends FreeSpec
  with MustMatchers
  with LazyLogging
  with FutureConverter
  with ScalaFutures with BeforeAndAfterAll with Timed {

  import sttp.client._
  import sttp.client.circe._
  import monix.execution.Scheduler.Implicits.global
  import monix.eval._
  import monix.reactive._


  implicit val sttpBackend = AsyncHttpClientMonixBackend().runSyncUnsafe()

  val restProxyUri: Uri = uri"http://localhost:8082"
  val bootstrapServers = "localhost:9091"
  val testTopicName = "restTest"

  val adminProps = new Properties()

  adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
  val adminClient: AdminClient = AdminClient.create(adminProps)



  override def beforeAll() {
    val getTopicNames: Future[util.Set[String]] = toScalaFuture(adminClient.listTopics().names())
    val createTopicOfNotExists = getTopicNames flatMap { topicNames: util.Set[String] =>
      val newTopics: Set[NewTopic] = (Set(testTopicName) -- asScala(topicNames)) map {
        new NewTopic(_, 1, 1)
      }
      val createTopicResults: mutable.Map[String, KafkaFuture[Void]] = asScala(adminClient.createTopics(asJava(newTopics)).values())
      Future.sequence(createTopicResults.values.map(f => toScalaFuture(f)))
    }
    Await.result(createTopicOfNotExists, 10.seconds)
  }

  "rest proxy topic list" in {

    val topicsUri: Uri = restProxyUri.path("topics")

    // Accept-Encoding: gzp, deflate already added
    val request = basicRequest.get(topicsUri)

    val resT: Task[Response[Either[String, String]]] = request.send()
    val res: Response[Either[String, String]] = resT.runSyncUnsafe()
    res.body fold(e => logger.error(s"failed: $e"), r => logger.info(s"success: $r"))
    //println("headers: ")
    //res.headers foreach println
  }

  "produce" - {

    val testTopicProduceUri = restProxyUri.path("topics", testTopicName)
    val reqBase = basicRequest.post(testTopicProduceUri).response(asJson[ProduceResponse])

    "produce single json message" in {

      val msg = Message("key", "value")
      val records = Records(List(msg))

      val req = reqBase.body(records)

      // 250ms
      val produceResponse = timed("sending request, receiving and decoding response") {
        req.send().runSyncUnsafe()
      }
      produceResponse.body fold(e => logger.error(s"failed: $e"), r => ())
    }

    "produce json message batch" in {
      val batchSize = 10000
      val msgs = (1 to batchSize).toList map { _: Int => Message(Random.alphanumeric.take(5).mkString, Random.alphanumeric.take(100).mkString) }
      val records = Records(msgs)

      val req = reqBase.body(records)

      // 260 ms for 100 records
      // 340 for 1000 records
      // 580 for 10000
      val produceResponse = timed(s"produce $batchSize messages, receiving and decoding responses") {
        val produce = req.send()
        produce.runSyncUnsafe()
      }
      produceResponse.body fold(e => logger.error(s"failed: $e"), r =>())
      println("headers: ")
      produceResponse.headers foreach println
    }

    "produce parallel json message batches - ordered" in {

      val msgCount = 100000
      val batchSize = 100
      val parallelism = 8

      val msgs = (1 to msgCount).toList map { _: Int => Message(Random.alphanumeric.take(5).mkString, Random.alphanumeric.take(100).mkString) }
      val batches: Iterable[Records] = msgs.sliding(batchSize, batchSize).to(Iterable).map(Records(_))

      // time for create and dispatch requests, receiving and decoding responses - 5317.291518ms - 100K msg, batchSize 100, par 8
      // time for create and dispatch requests, receiving and decoding responses - 20486.01536ms - 1M msg, batchSize 1000, par 16
      val produceResponses: List[Response[Either[ResponseError[Error], ProduceResponse]]] = timed(s"produce $msgCount messages, receiving and decoding responses") {

        val sent: Iterable[Task[Response[Either[ResponseError[Error], ProduceResponse]]]] = batches map { b => reqBase.body(b).send() }
        val par: Iterable[Task[Iterable[Response[Either[ResponseError[Error], ProduceResponse]]]]] = sent.sliding(parallelism, parallelism).to(Iterable).map(b => Task.gather(b))
        val aggregate: Task[List[Response[Either[ResponseError[Error], ProduceResponse]]]] = Task.sequence(par).map(_.flatten.toList)
        aggregate.runSyncUnsafe()
      }

      produceResponses map { res =>
        res.body fold(e => logger.error(s"failed: $e"), r => ())
      }
    }

    "produce parallel json message batches - unordered" in {

      val msgCount = 100000
      val batchSize = 100
      val parallelism = 8

      val msgs = (1 to msgCount).toList map { _: Int => Message(Random.alphanumeric.take(5).mkString, Random.alphanumeric.take(100).mkString) }
      val batches: Iterable[Records] = msgs.sliding(batchSize, batchSize).to(Iterable).map(Records(_))

      // time for create and dispatch requests, receiving and decoding responses 3988.615484ms / 4669
      val produceResponses: List[Response[Either[ResponseError[Error], ProduceResponse]]] = timed(s"produce $msgCount messages, receiving and decoding responses") {

        //val sent: Iterable[Task[Response[Either[ResponseError[Error], ProduceResponse]]]] = batches map { b => reqBase.body(b).send() }
        val p: Observable[Response[Either[ResponseError[Error], ProduceResponse]]] = Observable.fromIterable(batches).mapParallelUnordered(parallelism) { b => reqBase.body(b).send()
        }
        p.toListL.runSyncUnsafe()
      }

      produceResponses map { res =>
        res.body fold(e => logger.error(s"failed: $e"), r => ())
      }
    }

    // need to store schema beforehand
    "produce single avro message" in {

    }
  }


}
