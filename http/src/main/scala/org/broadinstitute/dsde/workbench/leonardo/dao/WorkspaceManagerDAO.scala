package org.broadinstitute.dsde.workbench.leonardo.dao

import bio.terra.workspace.api.ControlledGcpResourceApi
import bio.terra.workspace.client._
import bio.terra.workspace.model.{
  ControlledResourceCommonFields,
  CreateControlledGcsBucketRequestBody,
  GcsBucketCreationParameters
}
import cats.effect.concurrent.Semaphore
import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import cats.mtl.Ask
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchEmail}

import java.util.UUID

trait WorkspaceManagerDAO[F[_]] {

  def createBucket(name: GcsBucketName, workspaceUUID: UUID, project: GoogleProject, userEmail: WorkbenchEmail)(
    implicit ev: Ask[F, TraceId]
  ): F[Unit]
}

object WorkspaceManagerDAO {

  case class WorkspaceManagerDAOConfig(baseUrl: String)

  class OpenApiWorkspaceManagerDAO[F[_]](config: WorkspaceManagerDAOConfig,
                                         samDAO: SamDAO[F],
                                         blocker: Blocker,
                                         blockerBound: Semaphore[F])(
    implicit F: Sync[F],
    cs: ContextShift[F]
  ) extends WorkspaceManagerDAO[F] {

    private def getApiClient(userEmail: WorkbenchEmail,
                             project: GoogleProject)(implicit ev: Ask[F, TraceId]): F[ApiClient] =
      samDAO.getCachedPetAccessToken(userEmail, project).flatMap {
        case Some(token) =>
          F.delay(new ApiClient().setBasePath(config.baseUrl).setAccessToken(token))
        case None => F.raiseError(new RuntimeException("Could not get token"))
      }

    private def getResourceApi(userEmail: WorkbenchEmail,
                               project: GoogleProject)(implicit ev: Ask[F, TraceId]): F[ControlledGcpResourceApi] =
      getApiClient(userEmail, project).map(c => new ControlledGcpResourceApi(c))

    // TODO: retryF?
    private def blockF[A](fa: F[A])(implicit ev: Ask[F, TraceId]): F[A] =
      blockerBound.withPermit(blocker.blockOn(fa))

    override def createBucket(name: GcsBucketName,
                              workspaceUUID: UUID,
                              project: GoogleProject,
                              userEmail: WorkbenchEmail)(
      implicit ev: Ask[F, TraceId]
    ): F[Unit] =
      for {
        c <- getResourceApi(userEmail, project)
        body = new CreateControlledGcsBucketRequestBody()
          .common(new ControlledResourceCommonFields())
          .gcsBucket(new GcsBucketCreationParameters())
        bucket <- blockF(F.delay(c.createBucket(body, workspaceUUID)))
      } yield ()

  }

}
