package com.example.login.api

import android.os.Build
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.KeyStore
import javax.net.ssl.*

object ApiClient {

    fun getClient(baseUrl: String, hash: String? = null): Retrofit {

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)

        // Add hash header if provided
        if (hash != null) {
            okHttpBuilder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("hash", hash)
                    .build()
                chain.proceed(request)
            }
        }

        // 🔐 Enable TLS 1.2 for Android 4.4–5.0 (API 16–21)
        if (Build.VERSION.SDK_INT in 16..21) {
            enableTls12(okHttpBuilder)
        }

        val okHttpClient = okHttpBuilder.build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    // --------------------------------------------------
    // TLS 1.2 Enabler
    // --------------------------------------------------
    private fun enableTls12(builder: OkHttpClient.Builder) {
        try {
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)

            val trustManager = systemDefaultTrustManager()

            builder.sslSocketFactory(
                Tls12SocketFactory(sslContext.socketFactory),
                trustManager
            )

            val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .allEnabledCipherSuites()
                .build()

            builder.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as KeyStore?)
        return tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }
}
