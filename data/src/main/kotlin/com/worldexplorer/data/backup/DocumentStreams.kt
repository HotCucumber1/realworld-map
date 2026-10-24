package com.worldexplorer.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Opens user-chosen documents (Storage Access Framework content:// URIs, or file paths in tests). */
interface DocumentStreams {
    fun openInput(location: String): InputStream
    fun openOutput(location: String): OutputStream
}

class ContentResolverDocumentStreams @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentStreams {
    override fun openInput(location: String): InputStream {
        val uri = Uri.parse(location)
        return if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open document")
        } else {
            File(uri.path ?: location).inputStream()
        }
    }

    override fun openOutput(location: String): OutputStream {
        val uri = Uri.parse(location)
        return if (uri.scheme == "content") {
            // "wt" truncates an existing document instead of appending to it.
            context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("cannot open document")
        } else {
            File(uri.path ?: location).outputStream()
        }
    }
}

/** Build information the data layer needs (provided by the app module). */
data class AppBuildInfo(val versionName: String)
