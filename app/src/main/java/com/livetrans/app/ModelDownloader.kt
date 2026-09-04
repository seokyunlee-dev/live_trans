package com.livetrans.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and unpacks a Vosk model zip on demand, caching it under [baseDir]. */
object ModelDownloader {

    fun ensureModel(baseDir: File, modelName: String, zipUrl: String, onProgress: (String) -> Unit): File {
        val modelDir = File(baseDir, modelName)
        if (modelDir.isDirectory && modelDir.list()?.isNotEmpty() == true) {
            return modelDir
        }

        onProgress("모델 다운로드 중... ($modelName)")
        val zipFile = File(baseDir, "$modelName.zip")
        val connection = URL(zipUrl).openConnection() as HttpURLConnection
        connection.connect()
        connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }

        onProgress("모델 압축 해제 중...")
        unzip(zipFile, baseDir)
        zipFile.delete()
        return modelDir
    }

    private fun unzip(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                check(outFile.canonicalPath.startsWith(canonicalDest + File.separator)) {
                    "zip entry escapes destination: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
