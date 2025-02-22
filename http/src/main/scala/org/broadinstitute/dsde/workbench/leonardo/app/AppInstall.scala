package org.broadinstitute.dsde.workbench.leonardo.app

import cats.mtl.Ask
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.ServiceAccountName
import org.broadinstitute.dsde.workbench.leonardo.dao.StorageContainerResponse
import org.broadinstitute.dsde.workbench.leonardo.util.AKSInterpreterConfig
import org.broadinstitute.dsde.workbench.leonardo.{
  App,
  AppContext,
  AppType,
  LandingZoneResources,
  ManagedIdentityName,
  WorkspaceId
}
import org.broadinstitute.dsp.Values
import org.http4s.Uri
import org.http4s.headers.Authorization

/**
 * Defines how to install a Kubernetes App.
 */
trait AppInstall[F[_]] {

  /** List of WSM-controlled databases the app requires. */
  def databases: List[Database]

  /** Builds helm values to be passed to the app. */
  def buildHelmOverrideValues(params: BuildHelmOverrideValuesParams)(implicit ev: Ask[F, AppContext]): F[Values]

  /** Checks status of the app. */
  def checkStatus(baseUri: Uri, authHeader: Authorization)(implicit ev: Ask[F, AppContext]): F[Boolean]
}

object AppInstall {

  /** Maps AppType to AppInstall. */
  def appTypeToAppInstall[F[_]](wdsAppInstall: WdsAppInstall[F],
                                cromwellAppInstall: CromwellAppInstall[F],
                                workflowsAppInstall: WorkflowsAppInstall[F],
                                hailBatchAppInstall: HailBatchAppInstall[F],
                                cromwellRunnerAppInstall: CromwellRunnerAppInstall[F]
  ): AppType => AppInstall[F] = _ match {
    case AppType.Wds               => wdsAppInstall
    case AppType.Cromwell          => cromwellAppInstall
    case AppType.WorkflowsApp      => workflowsAppInstall
    case AppType.HailBatch         => hailBatchAppInstall
    case AppType.CromwellRunnerApp => cromwellRunnerAppInstall
    case e                         => throw new IllegalArgumentException(s"Unexpected app type: ${e}")
  }
}

sealed trait Database
object Database {

  /** A database to be created as part of app creation. */
  final case class CreateDatabase(prefix: String, allowAccessForAllWorkspaceUsers: Boolean = false) extends Database

  /** A database that should _not_ be created as part of app creation, but referenced in k8s namespace creation. */
  final case class ReferenceDatabase(name: String) extends Database
}

final case class BuildHelmOverrideValuesParams(app: App,
                                               workspaceId: WorkspaceId,
                                               cloudContext: AzureCloudContext,
                                               landingZoneResources: LandingZoneResources,
                                               storageContainer: Option[StorageContainerResponse],
                                               relayPath: Uri,
                                               ksaName: ServiceAccountName,
                                               managedIdentityName: ManagedIdentityName,
                                               databaseNames: List[String],
                                               config: AKSInterpreterConfig
)
