package org.broadinstitute.dsde.workbench.leonardo
package http
package service

import akka.http.scaladsl.model.StatusCodes
import cats.Parallel
import cats.effect.Async
import cats.effect.std.Queue
import cats.mtl.Ask
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.leonardo.SamResourceId.{
  PersistentDiskSamResourceId,
  WorkspaceResourceSamResourceId
}
import org.broadinstitute.dsde.workbench.leonardo.config.PersistentDiskConfig
import org.broadinstitute.dsde.workbench.leonardo.dao._
import org.broadinstitute.dsde.workbench.leonardo.db._
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage
import org.broadinstitute.dsde.workbench.leonardo.monitor.LeoPubsubMessage.DeleteDiskV2Message
import org.broadinstitute.dsde.workbench.model.{TraceId, UserInfo}

import scala.concurrent.ExecutionContext

class DiskV2ServiceInterp[F[_]: Parallel](config: PersistentDiskConfig,
                                          authProvider: LeoAuthProvider[F],
                                          wsmDao: WsmDao[F],
                                          samDAO: SamDAO[F],
                                          publisherQueue: Queue[F, LeoPubsubMessage]
)(implicit
  F: Async[F],
  dbReference: DbReference[F],
  ec: ExecutionContext
) extends DiskV2Service[F] {

  // backwards compatible with v1 getDisk route
  override def getDisk(userInfo: UserInfo, diskId: DiskId)(implicit
    as: Ask[F, AppContext]
  ): F[GetPersistentDiskV2Response] =
    for {
      ctx <- as.ask
      diskResp <- DiskServiceDbQueries
        .getGetPersistentDiskResponseV2(diskId, ctx.traceId)
        .transaction

      // check that workspaceId is not null
      workspaceId <- F.fromOption(diskResp.workspaceId, DiskWithoutWorkspaceException(diskId, ctx.traceId))

      hasWorkspacePermission <- authProvider.isUserWorkspaceReader(
        WorkspaceResourceSamResourceId(workspaceId),
        userInfo
      )

      _ <- F.raiseUnless(hasWorkspacePermission)(ForbiddenError(userInfo.userEmail))

      hasDiskPermission <- authProvider.hasPermission[PersistentDiskSamResourceId, PersistentDiskAction](
        diskResp.samResource,
        PersistentDiskAction.ReadPersistentDisk,
        userInfo
      )

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done auth call for get azure disk permission")))
      _ <- F
        .raiseError[Unit](
          DiskNotFoundByIdException(diskId, ctx.traceId)
        )
        .whenA(!hasDiskPermission)

    } yield diskResp

  override def deleteDisk(userInfo: UserInfo, diskId: DiskId)(implicit
    as: Ask[F, AppContext]
  ): F[Unit] =
    for {
      ctx <- as.ask
      diskOpt <- persistentDiskQuery.getActiveById(diskId).transaction

      disk <- diskOpt.fold(
        F.raiseError[PersistentDisk](DiskNotFoundByIdException(diskId, ctx.traceId))
      )(
        F.pure
      )

      // check that workspaceId is not null
      workspaceId <- F.fromOption(disk.workspaceId, DiskWithoutWorkspaceException(diskId, ctx.traceId))

      hasWorkspacePermission <- authProvider.isUserWorkspaceReader(
        WorkspaceResourceSamResourceId(workspaceId),
        userInfo
      )
      _ <- F.raiseUnless(hasWorkspacePermission)(ForbiddenError(userInfo.userEmail))

      // check read permission first
      listOfPermissions <- authProvider.getActions(disk.samResource, userInfo)
      hasReadPermission = listOfPermissions.toSet.contains(
        PersistentDiskAction.ReadPersistentDisk
      )
      _ <- F
        .raiseError[Unit](DiskNotFoundByIdException(diskId, ctx.traceId))
        .whenA(!hasReadPermission)

      // check if disk is deletable
      _ <- F
        .raiseUnless(disk.status.isDeletable)(
          DiskCannotBeDeletedException(diskId, disk.status, disk.cloudContext, ctx.traceId)
        )

      // check delete permission
      hasDeletePermission = listOfPermissions.toSet.contains(
        PersistentDiskAction.DeletePersistentDisk
      )
      _ <- F
        .raiseError[Unit](ForbiddenError(userInfo.userEmail))
        .whenA(!hasDeletePermission)

      _ <- ctx.span.traverse(s => F.delay(s.addAnnotation("Done auth call for delete azure disk permission")))

      // check that disk isn't attached to a runtime
      isAttached <- persistentDiskQuery.isDiskAttached(diskId).transaction
      _ <- F
        .raiseError[Unit](DiskCannotBeDeletedAttachedException(diskId, workspaceId, ctx.traceId))
        .whenA(isAttached)

      _ <- persistentDiskQuery.markPendingDeletion(disk.id, ctx.now).transaction

      _ <- publisherQueue.offer(
        DeleteDiskV2Message(
          disk.id,
          workspaceId,
          disk.cloudContext,
          disk.wsmResourceId,
          Some(ctx.traceId)
        )
      )
    } yield ()
}
case class DiskCannotBeDeletedAttachedException(id: DiskId, workspaceId: WorkspaceId, traceId: TraceId)
    extends LeoException(
      s"Persistent disk ${id.value} in workspace ${workspaceId.value} cannot be deleted. Disk is still attached to a runtime",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )

case class DiskWithoutWorkspaceException(id: DiskId, traceId: TraceId)
    extends LeoException(
      s"Persistent disk ${id.value} cannot be deleted. Disk record has no workspaceId",
      StatusCodes.Conflict,
      traceId = Some(traceId)
    )
