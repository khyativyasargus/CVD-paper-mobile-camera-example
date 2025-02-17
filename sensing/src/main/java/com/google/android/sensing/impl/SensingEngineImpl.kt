/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.sensing.impl

import android.content.Context
import android.content.Intent
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.google.android.sensing.SensingEngine
import com.google.android.sensing.ServerConfiguration
import com.google.android.sensing.capture.CaptureFragment
import com.google.android.sensing.capture.CaptureUtil
import com.google.android.sensing.capture.SensorCaptureResult
import com.google.android.sensing.db.Database
import com.google.android.sensing.db.ResourceNotFoundException
import com.google.android.sensing.model.CaptureInfo
import com.google.android.sensing.model.CaptureType
import com.google.android.sensing.model.RequestStatus
import com.google.android.sensing.model.ResourceInfo
import com.google.android.sensing.model.SensorType
import com.google.android.sensing.model.UploadRequest
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * @param database Interface to interact with room database.
 * @param context [Context] to access fragmentManager, to launch fragments, to access files and
 * resources in the application context.
 * @param serverConfiguration
 */
@ExperimentalCamera2Interop
internal class SensingEngineImpl(
  private val database: Database,
  private val context: Context,
  private val serverConfiguration: ServerConfiguration,
) : SensingEngine {

  override suspend fun onCaptureCompleteCallback(captureInfo: CaptureInfo) = flow {
    if (captureInfo.recapture == true) {
      try {
        val inRecordCaptureInfo = getCaptureInfo(captureInfo.captureId!!)
        deleteDataInCapture(inRecordCaptureInfo.captureId!!)
        moveDataFromCacheToFiles(inRecordCaptureInfo.captureFolder)
      } catch (_: ResourceNotFoundException) {}
    } // else we don't need to worry about it because the capture module stored data in the
    // internal filesDir
    database.addCaptureInfo(captureInfo)
    emit(SensorCaptureResult.CaptureInfoCreated(captureInfo.captureId!!))
    CaptureUtil.sensorsInvolved(captureInfo.captureType).forEach {
      val resourceFolderRelativePath = getResourceFolderRelativePath(it, captureInfo)
      val uploadRelativeUrl = "/$resourceFolderRelativePath.zip"
      val resourceInfo =
        ResourceInfo(
          resourceInfoId = UUID.randomUUID().toString(),
          captureId = captureInfo.captureId!!,
          participantId = captureInfo.participantId,
          captureTitle = captureInfo.captureSettings.captureTitle,
          fileType = resourceInfoFileType(it, captureInfo),
          resourceFolderRelativePath = resourceFolderRelativePath,
          uploadURL = serverConfiguration.getBucketUrl() + uploadRelativeUrl,
          status = RequestStatus.PENDING
        )
      database.addResourceInfo(resourceInfo)
      emit(SensorCaptureResult.ResourceMetaInfoCreated(resourceInfo.resourceInfoId))

      /** [CaptureFragment] stores files in app's internal storage directory */
      val resourceFolder = File(context.filesDir, resourceFolderRelativePath)
      val outputZipFile = resourceFolder.absolutePath + ".zip"

      /** Zipping logic from: https://stackoverflow.com/a/63828765 */
      val zipOutputStream = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile)))
      zipOutputStream.use { zos ->
        resourceFolder.walkTopDown().forEach { file ->
          val zipFileName =
            file.absolutePath.removePrefix(resourceFolder.absolutePath).removePrefix("/")
          val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")
          zos.putNextEntry(entry)
          if (file.isFile) {
            file.inputStream().use { fis -> fis.copyTo(zos) }
          }
        }
      }
      val uploadRequest =
        UploadRequest(
          requestUuid = UUID.randomUUID(),
          resourceInfoId = resourceInfo.resourceInfoId,
          zipFile = outputZipFile,
          fileSize = File(outputZipFile).length(),
          fileOffset = 0L,
          bucketName = serverConfiguration.bucketName,
          uploadRelativeURL = uploadRelativeUrl,
          isMultiPart = serverConfiguration.networkConfiguration.isMultiPart,
          nextPart = 1,
          uploadId = null,
          status = RequestStatus.PENDING,
          lastUpdatedTime = Date.from(Instant.now())
        )
      database.addUploadRequest(uploadRequest)
      emit(SensorCaptureResult.UploadRequestCreated(uploadRequest.requestUuid.toString()))
    }
    emit(SensorCaptureResult.ResourcesStored(captureInfo.captureId!!))
  }

  private suspend fun moveDataFromCacheToFiles(captureFolder: String) {
    withContext(Dispatchers.IO) {
      val sourceFile = File(context.cacheDir, captureFolder)
      val destFile = File(context.filesDir, captureFolder)
      destFile.mkdirs()
      return@withContext sourceFile.renameTo(destFile)
    }
  }

  override suspend fun captureSensorData(pendingIntent: Intent) {
    TODO("Not yet implemented")
  }

  override suspend fun listResourceInfoForParticipant(participantId: String): List<ResourceInfo> {
    return database.listResourceInfoForParticipant(participantId)
  }

  override suspend fun listResourceInfoInCapture(captureId: String): List<ResourceInfo> {
    return database.listResourceInfoInCapture(captureId)
  }

  override suspend fun getResourceInfo(resourceInfoId: String): ResourceInfo? {
    return try {
      database.getResourceInfo(resourceInfoId)
    } catch (e: ResourceNotFoundException) {
      null
    }
  }

  override suspend fun updateResourceInfo(resourceInfo: ResourceInfo) {
    database.updateResourceInfo(resourceInfo)
  }

  override suspend fun updateUploadRequest(uploadRequest: UploadRequest) {
    return database.updateUploadRequest(uploadRequest)
  }

  override suspend fun listUploadRequest(status: RequestStatus): List<UploadRequest> {
    return database.listUploadRequests(status)
  }

  override suspend fun getCaptureInfo(captureId: String): CaptureInfo {
    return database.getCaptureInfo(captureId)
  }

  override suspend fun deleteDataInCapture(captureId: String): Boolean {
    val captureInfo =
      try {
        getCaptureInfo(captureId)
      } catch (e: ResourceNotFoundException) {
        null
      } ?: return true

    // Step 1: Delete db records
    database.deleteRecordsInCapture(captureId)
    // Step 2: delete the captureFolder
    val captureFile = File(context.filesDir, captureInfo.captureFolder)
    val parentFile = captureFile.parentFile
    val deleted: Boolean
    withContext(Dispatchers.IO) {
      deleted = captureFile.deleteRecursively()
      // delete Participant's folder if there are no data
      if (parentFile?.list()?.isEmpty() == true) {
        parentFile.delete()
      }
    }
    return deleted
  }

  override suspend fun deleteSensorData(uploadURL: String) {
    TODO("Not yet implemented")
  }

  override suspend fun deleteSensorMetaData(uploadURL: String) {
    TODO("Not yet implemented")
  }

  companion object {
    /** File format for any sensor is taken from [captureSettings.fileTypeMap]. */
    private fun resourceInfoFileType(sensorType: SensorType, captureInfo: CaptureInfo): String {
      return when (sensorType) {
        SensorType.CAMERA -> captureInfo.captureSettings.fileTypeMap[sensorType]!!
      }
    }

    /** Returns relative folder for a specific sensor type. */
    fun getResourceFolderRelativePath(sensorType: SensorType, captureInfo: CaptureInfo): String {
      return when (captureInfo.captureType) {
        CaptureType.IMAGE -> "${captureInfo.captureFolder}/${sensorType.name}"
        CaptureType.VIDEO_PPG -> "${captureInfo.captureFolder}/${sensorType.name}"
      }
    }
  }
}
