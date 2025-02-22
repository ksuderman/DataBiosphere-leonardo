package org.broadinstitute.dsde.workbench.leonardo.dao

import bio.terra.common.tracing.JerseyTracingFilter
import bio.terra.workspace.api.{ControlledAzureResourceApi, ResourceApi}
import bio.terra.workspace.client.ApiClient
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._
import io.opencensus.trace.Tracing
import org.broadinstitute.dsde.workbench.leonardo.AppContext
import org.broadinstitute.dsde.workbench.leonardo.util.WithSpanFilter
import org.glassfish.jersey.client.ClientConfig
import org.http4s.Uri

/**
 * Represents a way to get a client for interacting with workspace manager controlled resources.
 * Additional WSM clients can be added here if needed.
 *
 * Based on `org/broadinstitute/dsde/rawls/dataaccess/workspacemanager/WorkspaceManagerApiClientProvider.scala`
 *
 */
trait WsmApiClientProvider[F[_]] {
  def getControlledAzureResourceApi(token: String)(implicit ev: Ask[F, AppContext]): F[ControlledAzureResourceApi]
  def getResourceApi(token: String)(implicit ev: Ask[F, AppContext]): F[ResourceApi]
}

class HttpWsmClientProvider[F[_]](baseWorkspaceManagerUrl: Uri)(implicit F: Async[F]) extends WsmApiClientProvider[F] {
  private def getApiClient(token: String)(implicit ev: Ask[F, AppContext]): F[ApiClient] =
    for {
      ctx <- ev.ask
      client = new ApiClient() {
        override def performAdditionalClientConfiguration(clientConfig: ClientConfig): Unit = {
          super.performAdditionalClientConfiguration(clientConfig)
          ctx.span.foreach { span =>
            clientConfig.register(new WithSpanFilter(span))
            clientConfig.register(new JerseyTracingFilter(Tracing.getTracer))
          }
        }
      }
      _ = client.setBasePath(baseWorkspaceManagerUrl.renderString)
      _ = client.setAccessToken(token)
    } yield client

  override def getControlledAzureResourceApi(token: String)(implicit
    ev: Ask[F, AppContext]
  ): F[ControlledAzureResourceApi] =
    getApiClient(token).map(apiClient => new ControlledAzureResourceApi(apiClient))

  override def getResourceApi(token: String)(implicit ev: Ask[F, AppContext]): F[ResourceApi] =
    getApiClient(token).map(apiClient => new ResourceApi(apiClient))
}
