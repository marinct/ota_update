package sk.fourq.otaupdate

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.*
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

import javax.security.cert.CertificateException


class FileDownloader(
        private val context: Context,
        private val url: String,
        private val fileName: String
) {
    /* private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
             .connectTimeout(60, TimeUnit.SECONDS)
             .readTimeout(60, TimeUnit.SECONDS)
             .build()*/
    private val okHttpClient: OkHttpClient = getUnsafeOkHttpClient()

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> {
                            return arrayOf()
                        }
                    }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
            val builder = OkHttpClient.Builder()
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { hostname, session -> true }
            builder.connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
        } catch (e: Exception) {
            print(e.message + "")
            OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
        }
    }

    private val errorMessage = "File couldn't be downloaded"
    private val bufferLengthBytes: Int = 1024 * 4

    fun download(): Observable<Int> {
        return Observable.create<Int> { emitter ->

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // To Download File for Android 10 and above
                val content = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        content
                )
                uri?.apply {
                    val responseBody = getResponseBody(url)
                    if (responseBody != null
                    ) {
                        responseBody.byteStream().use { inputStream ->
                            context.contentResolver.openOutputStream(uri)?.use { fileOutStream ->
                                writeOutStream(
                                        inStream = inputStream,
                                        outStream = fileOutStream,
                                        contentLength = responseBody.contentLength(),
                                        emitter = emitter
                                )
                            }

                            emitter.onComplete()
                        }
                    } else {
                        emitter.onError(Throwable(errorMessage))
                    }
                }
            } else { // For Android versions below than 10
                val directory = File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS).absolutePath
                ).apply {
                    if (!exists()) {
                        mkdir()
                    }
                }

                val file = File(directory, fileName)
                val responseBody = getResponseBody(url)

                if (responseBody != null) {
                    responseBody.byteStream().use { inputStream ->
                        file.outputStream().use { fileOutStream ->
                            writeOutStream(
                                    inStream = inputStream,
                                    outStream = fileOutStream,
                                    contentLength = responseBody.contentLength(),
                                    emitter = emitter
                            )
                        }
                        emitter.onComplete()
                    }

                } else {
                    emitter.onError(Throwable(errorMessage))
                }
            }
        }
    }

    private fun getResponseBody(url: String): ResponseBody? {
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()

        return if (response.code() >= HttpURLConnection.HTTP_OK &&
                response.code() < HttpURLConnection.HTTP_MULT_CHOICE &&
                response.body() != null
        )
            response.body()
        else
            null
    }

    private fun writeOutStream(
            inStream: InputStream,
            outStream: OutputStream,
            contentLength: Long,
            emitter: ObservableEmitter<Int>) {
        var bytesCopied = 0
        val buffer = ByteArray(bufferLengthBytes)
        var bytes = inStream.read(buffer)
        while (bytes >= 0) {
            outStream.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = inStream.read(buffer)
            emitter.onNext(
                    ((bytesCopied * 100) / contentLength).toInt()
            )
        }
        outStream.flush()
        outStream.close()

    }
}
