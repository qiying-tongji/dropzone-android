package com.bitocean.dropzone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlin.math.sqrt

class PhotoGroupingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        if (kind != FACE && kind != PLACE) return Result.failure()
        val context = applicationContext
        if (!enabled(context, kind)) return Result.success()
        val epoch = inputData.getLong("epoch", -1)
        val deadline = android.os.SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(7)
        return try {
            Store(context).use { store ->
                val extras = MemoryExtras(store)
                val photos = PhotoLibrary(context, store)
                val scanned = extras.scanned(kind)
                val needsFaceCover = if (kind == FACE) extras.photosMissingFaceCovers() else emptySet()
                val grants = photos.manualGrants()
                val pending = store.all().asReversed().filter { it.kind == "photo" && (it.id !in scanned || it.id in needsFaceCover) && photos.accessible(it, grants) }
                if (pending.isEmpty()) return Result.success()
                if (kind == FACE) {
                    FaceEncoder(context).use { encoder ->
                        for ((index, item) in pending.withIndex()) {
                            if (!current(context, kind, epoch)) return Result.success()
                            if (android.os.SystemClock.elapsedRealtime() >= deadline) return Result.retry()
                            val faces = if (photos.accessible(item) && photos.readable(Uri.parse(item.uri))) encoder.encode(Uri.parse(item.uri)) else null
                            val coverOnly = item.id in scanned
                            if (faces != null) writePhoto(context, photos, store, extras, item, kind, epoch,
                                if (coverOnly) FACE_COVER else FACE) {
                                if (coverOnly) restoreFaceBounds(extras, item, faces)
                                else groupFaces(extras, item, faces)
                            }
                            setProgressAsync(workDataOf("done" to index + 1, "total" to pending.size))
                        }
                    }
                } else for ((index, item) in pending.withIndex()) {
                    if (!current(context, kind, epoch)) return Result.success()
                            if (android.os.SystemClock.elapsedRealtime() >= deadline) return Result.retry()
                    val coordinates = if (photos.accessible(item)) coordinates(context, item) else null
                    writePhoto(context, photos, store, extras, item, kind, epoch) {
                        if (coordinates != null) groupPlace(extras, item, coordinates)
                    }
                    setProgressAsync(workDataOf("done" to index + 1, "total" to pending.size))
                }
            }
            Result.success()
        } catch (error: Exception) {
            android.util.Log.w("DropZoneGrouping", "Photo grouping interrupted: ${error.javaClass.simpleName}")
            Result.failure()
        }
    }

    private fun current(context: Context, kind: String, epoch: Long) =
        !isStopped && enabled(context, kind) && settings(context).getLong("$kind-group-epoch", 0) == epoch

    private fun writePhoto(context: Context, photos: PhotoLibrary, store: Store, extras: MemoryExtras,
                           item: Memory, kind: String, epoch: Long, scan: String = kind, write: () -> Unit) {
        synchronized(Store.photoLock) {
            val latest = store.get(item.id) ?: return
            if (!current(context, kind, epoch) || latest.uri != item.uri || !photos.accessible(latest) ||
                !photos.readable(Uri.parse(latest.uri))) return
            if (extras.hasScan(scan, item.id)) return
            extras.transaction {
                write()
                extras.finishScan(scan, item.id)
            }
        }
    }

    private fun coordinates(context: Context, item: Memory): DoubleArray? {
        val original = item.photoKey.ifBlank { item.albumUri }.ifBlank { item.uri }
        return runCatching {
            val uri = MediaStore.setRequireOriginal(Uri.parse(original))
            context.contentResolver.openInputStream(uri)?.use { val location = FloatArray(2); if (ExifInterface(it).getLatLong(location)) DoubleArray(2) { index -> location[index].toDouble() } else null }
        }.getOrNull()?.takeIf {
            it[0] in -90.0..90.0 && it[1] in -180.0..180.0 && (it[0] != 0.0 || it[1] != 0.0)
        }
    }

    private fun groupPlace(extras: MemoryExtras, item: Memory, coordinates: DoubleArray) {
        val lat = round(coordinates[0] * 1000) / 1000
        val lon = round(coordinates[1] * 1000) / 1000
        val result = FloatArray(1)
        val closest = extras.groups(PLACE).mapNotNull { group ->
            if (group.latitude == null || group.longitude == null) return@mapNotNull null
            Location.distanceBetween(lat, lon, group.latitude, group.longitude, result)
            (group.id to result[0]).takeIf { it.second <= 200 }
        }.minByOrNull { it.second }?.first
        val group = closest ?: extras.createGroup(PLACE, "")
        extras.addMember(PLACE, item.id, 0, group, latitude=lat, longitude=lon)
    }

    private fun cosine(vector: FloatArray, bytes: ByteArray): Float {
        val stored = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (stored.remaining() != vector.size) return -1f
        var score = 0f
        for (value in vector) score += value * stored.get()
        return score
    }

    private fun restoreFaceBounds(extras: MemoryExtras, item: Memory, faces: List<DetectedFace>) {
        val unused = faces.toMutableList()
        for ((slot, oldVector) in extras.missingFaceSamples(item.id)) {
            val ranked = unused.map { it to cosine(it.vector, oldVector) }.sortedByDescending { it.second }
            val best = ranked.firstOrNull() ?: break
            if (best.second >= 0.90f && best.second - (ranked.getOrNull(1)?.second ?: -1f) >= 0.05f) {
                extras.saveFaceBounds(item.id, slot, best.first.bounds)
                unused.remove(best.first)
            }
        }
    }

    private fun groupFaces(extras: MemoryExtras, item: Memory, faces: List<DetectedFace>) {
        val samples = extras.faceSamples()
        val used = mutableSetOf<Long>()
        for ((slot, face) in faces.withIndex()) {
            val vector = face.vector
            val bestPerGroup = mutableMapOf<Long, Float>()
            for (sample in samples) {
                if (sample.item == item.id || sample.group in used) continue
                val score = cosine(vector, sample.vector)
                bestPerGroup[sample.group] = maxOf(score, bestPerGroup[sample.group] ?: -1f)
            }
            val ranked = bestPerGroup.entries.sortedByDescending { it.value }
            val best = ranked.firstOrNull()?.takeIf {
                it.value >= 0.50f && it.value - (ranked.getOrNull(1)?.value ?: -1f) >= 0.05f
            }
            val group = best?.key ?: extras.createGroup(FACE, "")
            used.add(group)
            val bytes = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(vector)
            extras.addMember(FACE, item.id, slot, group, bytes.array(), faceBounds = face.bounds)
        }
    }

    companion object {
        internal const val FACE = "face"
        private const val FACE_COVER = "face-cover"
        internal const val PLACE = "place"

        private fun settings(context: Context) = context.getSharedPreferences("settings", 0)
        internal fun optedIn(context: Context, kind: String) = settings(context).getBoolean("$kind-group-enabled", false)
        internal fun hasLocation(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        internal fun enabled(context: Context, kind: String) = optedIn(context, kind) && (kind != PLACE || hasLocation(context))
        internal fun key(kind: String) = "photo-group-$kind"

        internal fun enqueue(context: Context, kind: String) {
            if (!enabled(context, kind)) return
            val request = OneTimeWorkRequestBuilder<PhotoGroupingWorker>().setInputData(
                workDataOf("kind" to kind, "epoch" to settings(context).getLong("$kind-group-epoch", 0))
            ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(key(kind), ExistingWorkPolicy.KEEP, request)
        }

        internal fun setEnabled(context: Context, extras: MemoryExtras, kind: String, enabled: Boolean) {
            synchronized(Store.photoLock) {
                val settings = settings(context)
                settings.edit().putBoolean("$kind-group-enabled", enabled)
                    .putLong("$kind-group-epoch", settings.getLong("$kind-group-epoch", 0) + 1).apply()
                val manager = WorkManager.getInstance(context)
                manager.cancelUniqueWork(key(kind))
                if (!enabled) extras.clearGroups(kind)
            }
            if (enabled) enqueue(context, kind)
        }
    }
}

private data class DetectedFace(val vector: FloatArray, val bounds: String)

private class FaceEncoder(private val context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("face/sface-int8.onnx").use { it.readBytes() }, options)
    }
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).build())

    fun encode(uri: Uri): List<DetectedFace>? {
        val image = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val sample = (maxOf(info.size.width, info.size.height) + 1599) / 1600
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(image, 0)), 30, TimeUnit.SECONDS)
            return faces.sortedByDescending { it.boundingBox.width() }.take(24).mapNotNull { face ->
                if (face.boundingBox.width() < 60 || kotlin.math.abs(face.headEulerAngleY) > 35) null
                else align(image, face)?.let { cropped ->
                    try {
                        val box = face.boundingBox
                        val bounds = listOf(box.left.toFloat() / image.width, box.top.toFloat() / image.height,
                            box.right.toFloat() / image.width, box.bottom.toFloat() / image.height)
                            .joinToString(",") { it.coerceIn(0f, 1f).toString() }
                        embedding(cropped)?.let { DetectedFace(it, bounds) }
                    } finally { cropped.recycle() }
                }
            }
        } finally { image.recycle() }
    }

    private fun align(image: Bitmap, face: Face): Bitmap? {
        val points = listOf(FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT).map { face.getLandmark(it)?.position ?: return null }
        val eyes = points.take(2).sortedBy { it.x }
        val mouth = points.takeLast(2).sortedBy { it.x }
        val source = eyes + points[2] + mouth
        val target = listOf(PointF(38.2946f, 51.6963f), PointF(73.5318f, 51.5014f), PointF(56.0252f, 71.7366f),
            PointF(41.5493f, 92.3655f), PointF(70.7299f, 92.2041f))
        val sx = source.map { it.x }.average()
        val sy = source.map { it.y }.average()
        val tx = target.map { it.x }.average()
        val ty = target.map { it.y }.average()
        var scaleCos = 0.0
        var scaleSin = 0.0
        var denominator = 0.0
        for (index in source.indices) {
            val x = source[index].x - sx
            val y = source[index].y - sy
            val u = target[index].x - tx
            val v = target[index].y - ty
            scaleCos += x * u + y * v
            scaleSin += x * v - y * u
            denominator += x * x + y * y
        }
        if (denominator <= 0.0) return null
        val a = scaleCos / denominator
        val b = scaleSin / denominator
        val matrix = Matrix().apply {
            setValues(floatArrayOf(a.toFloat(), -b.toFloat(), (tx - a * sx + b * sy).toFloat(),
                b.toFloat(), a.toFloat(), (ty - b * sx - a * sy).toFloat(), 0f, 0f, 1f))
        }
        return Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun embedding(crop: Bitmap): FloatArray? {
        val pixels = IntArray(112 * 112)
        crop.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val input = FloatArray(pixels.size * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = ((pixel shr 16) and 255).toFloat()
            input[index + pixels.size] = ((pixel shr 8) and 255).toFloat()
            input[index + 2 * pixels.size] = (pixel and 255).toFloat()
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 112, 112)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                val output = (result[0].value as Array<*>).single() as FloatArray
                val norm = sqrt(output.sumOf { (it * it).toDouble() })
                if (norm <= 0 || !norm.isFinite()) return null
                return FloatArray(output.size) { (output[it] / norm).toFloat() }
            }
        }
    }

    override fun close() {
        detector.close()
        session.close()
    }
}
