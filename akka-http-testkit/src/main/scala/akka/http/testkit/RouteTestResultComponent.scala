/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import java.util.concurrent.CountDownLatch

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import akka.stream.scaladsl2.{ FlowWithSource, FutureSink, FlowFrom, FlowMaterializer }
import akka.http.model.HttpEntity.ChunkStreamPart
import akka.http.server._
import akka.http.model._

trait RouteTestResultComponent {

  def failTest(msg: String): Nothing

  /**
   * A receptacle for the response or rejections created by a route.
   */
  class RouteTestResult(timeout: FiniteDuration)(implicit fm: FlowMaterializer) {
    private[this] var result: Option[Either[List[Rejection], HttpResponse]] = None
    private[this] val latch = new CountDownLatch(1)

    def handled: Boolean = synchronized { result.isDefined && result.get.isRight }

    def rejections: List[Rejection] = synchronized {
      result match {
        case Some(Left(rejections)) ⇒ rejections
        case Some(Right(response))  ⇒ failTest("Request was not rejected, response was " + response)
        case None                   ⇒ failNeitherCompletedNorRejected()
      }
    }

    def response: HttpResponse = rawResponse.copy(entity = entity)

    /** Returns a "fresh" entity with a "fresh" unconsumed byte- or chunk stream (if not strict) */
    def entity: ResponseEntity = entityRecreator()

    def chunks: immutable.Seq[ChunkStreamPart] =
      entity match {
        case HttpEntity.Chunked(_, chunks) ⇒ awaitAllElements[ChunkStreamPart](chunks)
        case _                             ⇒ Nil
      }

    def ~>[T](f: RouteTestResult ⇒ T): T = f(this)

    private def rawResponse: HttpResponse = synchronized {
      result match {
        case Some(Right(response))        ⇒ response
        case Some(Left(Nil))              ⇒ failTest("Request was rejected")
        case Some(Left(rejection :: Nil)) ⇒ failTest("Request was rejected with rejection " + rejection)
        case Some(Left(rejections))       ⇒ failTest("Request was rejected with rejections " + rejections)
        case None                         ⇒ failNeitherCompletedNorRejected()
      }
    }

    private[testkit] def handleResult(rr: RouteResult)(implicit ec: ExecutionContext): Unit =
      synchronized {
        if (result.isEmpty) {
          result = rr match {
            case RouteResult.Complete(response)   ⇒ Some(Right(response))
            case RouteResult.Rejected(rejections) ⇒ Some(Left(RejectionHandler.applyTransformations(rejections)))
            case RouteResult.Failure(error)       ⇒ sys.error("Route produced exception: " + error)
          }
          latch.countDown()
        } else failTest("Route completed/rejected more than once")
      }

    private[testkit] def awaitResult: this.type = {
      latch.await(timeout.toMillis, MILLISECONDS)
      this
    }

    private[this] lazy val entityRecreator: () ⇒ ResponseEntity =
      rawResponse.entity match {
        case s: HttpEntity.Strict ⇒ () ⇒ s

        case HttpEntity.Default(contentType, contentLength, data) ⇒
          { () ⇒ HttpEntity.Default(contentType, contentLength, data) }

        case HttpEntity.CloseDelimited(contentType, data) ⇒
          { () ⇒ HttpEntity.CloseDelimited(contentType, data) }

        case HttpEntity.Chunked(contentType, chunks) ⇒
          { () ⇒ HttpEntity.Chunked(contentType, chunks) }
      }

    private def failNeitherCompletedNorRejected(): Nothing =
      failTest("Request was neither completed nor rejected within " + timeout)

    private def awaitAllElements[T](data: FlowWithSource[_, T]): immutable.Seq[T] =
      Await.result(data.grouped(Int.MaxValue).runWithSink(FutureSink[immutable.Seq[T]]), timeout)
  }
}