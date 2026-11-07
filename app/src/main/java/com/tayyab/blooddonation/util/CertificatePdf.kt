package com.tayyab.blooddonation.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.core.content.FileProvider
import com.tayyab.blooddonation.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Phase 2: a one-page donation certificate, rendered with the platform's
 * [PdfDocument] + [Canvas]. No new library. The file goes into the app's own
 * cache and is shared through the already-declared FileProvider, so no storage
 * permission and no public MediaStore entry are needed.
 *
 * All wording is passed in already translated (see [CertificateText]); this
 * object only draws lines and handles the file, so nothing here is a secret or
 * a phone number.
 */
object CertificatePdf {

    private const val TAG = "CertificatePdf"
    private const val PAGE_WIDTH = 595       // A4 at 72 dpi
    private const val PAGE_HEIGHT = 842

    fun create(context: Context, lines: List<String>): File? {
        val dir = File(context.cacheDir, "certificates")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create the certificate cache directory")
            return null
        }
        val file = File(dir, "donation_certificate_${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        return try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            )
            draw(page.canvas, lines)
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            file
        } catch (e: IOException) {
            Log.e(TAG, "Could not write the certificate", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Could not finish the PDF page", e)
            null
        } finally {
            document.close()
        }
    }

    private fun draw(canvas: Canvas, lines: List<String>) {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(176, 32, 42)
        }
        canvas.drawRect(36f, 36f, PAGE_WIDTH - 36f, PAGE_HEIGHT - 36f, border)

        var y = 150f
        for ((index, line) in lines.withIndex()) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = if (index == 0) 28f else 18f
                isFakeBoldText = index == 0
                color = if (index == 0) Color.rgb(140, 20, 30) else Color.DKGRAY
            }
            canvas.drawText(line, 64f, y, text)
            y += if (index == 0) 70f else 46f
        }
    }

    /** Shares the PDF with a URI the receiving app may read exactly once. */
    fun share(context: Context, file: File) {
        val uri = try {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider refused the certificate path", e)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send, context.getString(R.string.certificate_share_chooser)
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No app can share a PDF", e)
        }
    }
}
