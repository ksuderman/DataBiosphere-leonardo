package org.broadinstitute.dsde.workbench.leonardo.api
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.leonardo.model.ClusterRequest
import org.scalatest.{FlatSpec, Matchers}
import org.broadinstitute.dsde.workbench.leonardo.model.LeonardoJsonSupport._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.Materializer
import spray.json._
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.leonardo.config.{DataprocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.leonardo.dao.{GoogleDataprocDAO, MockGoogleDataprocDAO}
import org.broadinstitute.dsde.workbench.leonardo.service.LeonardoService

import scala.concurrent.ExecutionContext


class LeoRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  class TestLeoRoutes(leonardoService: LeonardoService)
                     (override implicit val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
    extends LeoRoutes(leonardoService, SwaggerConfig())(system, materializer, executionContext)

  val mockGoogleDataprocDAO = new MockGoogleDataprocDAO
  val leonardoService = new LeonardoService(mockGoogleDataprocDAO)  //<-- this will be turned into a mockservice
  val leoRoutes = new TestLeoRoutes(leonardoService)

  "LeoRoutes" should "200 on ping" in {
    Get("/api/ping") ~> leoRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "200 when creating a cluster" in {

    val newCluster = ClusterRequest("test-bucket-path", "test-service-account", Map[String,String]())
    val googleProject = "test-project"
    val clusterName = "test-cluster"

    Put(s"/api/cluster/$googleProject/$clusterName", newCluster.toJson) ~> leoRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
}
