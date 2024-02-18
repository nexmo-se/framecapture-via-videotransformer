package com.tokbox.sample.capturetransformer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentok.android.*
import com.opentok.android.PublisherKit.*
import com.opentok.android.Session.SessionListener
import com.opentok.android.SubscriberKit.SubscriberListener
import com.tokbox.sample.capturetransformer.OpenTokConfig
import com.tokbox.sample.capturetransformer.ServerConfig
import com.tokbox.sample.capturetransformer.network.APIService
import com.tokbox.sample.capturetransformer.network.GetSessionResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.EasyPermissions.PermissionCallbacks
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer


class MainActivity : AppCompatActivity(), PermissionCallbacks {
    private var retrofit: Retrofit? = null
    private var apiService: APIService? = null
    private var session: Session? = null
    private var publisher: Publisher? = null
    private var subscriber: Subscriber? = null
    private lateinit var publisherViewContainer: FrameLayout
    private lateinit var subscriberViewContainer: FrameLayout

    private lateinit var captureFrameTransformer: CaptureFrameTransformer


    private var buttonshowcap: Button? = null
    private var capFrame: Button? = null
    private var recent_cap: String? = null

    // Array of Video Transformers
    var videoTransformers: ArrayList<VideoTransformer> = ArrayList()


    private val publisherListener: PublisherListener = object : PublisherListener {
        override fun onStreamCreated(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamCreated: Publisher Stream Created. Own stream ${stream.streamId}")
            val captureTransformer = publisher!!.VideoTransformer("captureTransformer", captureFrameTransformer)
            videoTransformers.add(captureTransformer)
            publisher!!.setVideoTransformers(videoTransformers)
        }

        override fun onStreamDestroyed(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamDestroyed: Publisher Stream Destroyed. Own stream ${stream.streamId}")
        }

        override fun onError(publisherKit: PublisherKit, opentokError: OpentokError) {
            finishWithMessage("PublisherKit onError: ${opentokError.message}")
        }
    }
    private val sessionListener: SessionListener = object : SessionListener {
        override fun onConnected(session: Session) {
            Log.d(TAG, "onConnected: Connected to session: ${session.sessionId}")
            publisher = Publisher.Builder(this@MainActivity).resolution(Publisher.CameraCaptureResolution.HIGH_1080P).build()
            publisher?.setPublisherListener(publisherListener)
            publisher?.renderer?.setStyle(BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL)
            publisher?.cycleCamera() //use back camera
            publisherViewContainer.addView(publisher?.view)
            if (publisher?.view is GLSurfaceView) {
                (publisher?.view as GLSurfaceView).setZOrderOnTop(true)
            }
            session.publish(publisher)
        }

        override fun onDisconnected(session: Session) {
            Log.d(TAG, "onDisconnected: Disconnected from session: ${session.sessionId}")
        }

        override fun onStreamReceived(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamReceived: New Stream Received ${stream.streamId} in session: ${session.sessionId}")
            if (subscriber == null) {
                subscriber = Subscriber.Builder(this@MainActivity, stream).build().also {
                    it.renderer?.setStyle(
                        BaseVideoRenderer.STYLE_VIDEO_SCALE,
                        BaseVideoRenderer.STYLE_VIDEO_FILL
                    )

                    it.setSubscriberListener(subscriberListener)
                }

                session.subscribe(subscriber)
                subscriberViewContainer.addView(subscriber?.view)
            }
        }

        override fun onStreamDropped(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamDropped: Stream Dropped: ${stream.streamId} in session: ${session.sessionId}")
            if (subscriber != null) {
                subscriber = null
                subscriberViewContainer.removeAllViews()
            }
        }

        override fun onError(session: Session, opentokError: OpentokError) {
            finishWithMessage("Session error: ${opentokError.message}")
        }
    }
    var subscriberListener: SubscriberListener = object : SubscriberListener {
        override fun onConnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onConnected: Subscriber connected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onDisconnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onDisconnected: Subscriber disconnected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onError(subscriberKit: SubscriberKit, opentokError: OpentokError) {
            finishWithMessage("SubscriberKit onError: ${opentokError.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        publisherViewContainer = findViewById(R.id.publisher_container)
        subscriberViewContainer = findViewById(R.id.subscriber_container)
        requestPermissions()

        buttonshowcap = findViewById(R.id.button_viewcaps)
        capFrame = findViewById(R.id.button_captureframe)

        // Initialize the CaptureTransformer
        captureFrameTransformer = CaptureFrameTransformer(resources)
    }

    override fun onPause() {
        super.onPause()
        session?.onPause()
    }

    override fun onResume() {
        super.onResume()
        session?.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        Log.d(TAG, "onPermissionsGranted:$requestCode: $perms")
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        finishWithMessage("onPermissionsDenied: $requestCode: $perms")
    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_CODE)
    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.INTERNET, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (EasyPermissions.hasPermissions(this, *perms)) {
            if (ServerConfig.hasChatServerUrl()) {
                // Custom server URL exists - retrieve session config
                if (!ServerConfig.isValid) {
                    finishWithMessage("Invalid chat server url: ${ServerConfig.CHAT_SERVER_URL}")
                    return
                }
                initRetrofit()
                getSession()
            } else {
                // Use hardcoded session config
                if (!OpenTokConfig.isValid) {
                    finishWithMessage("Invalid OpenTokConfig. ${OpenTokConfig.description}")
                    return
                }
                initializeSession(
                    OpenTokConfig.API_KEY,
                    OpenTokConfig.SESSION_ID,
                    OpenTokConfig.TOKEN
                )
            }
        } else {
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.rationale_video_app),
                PERMISSIONS_REQUEST_CODE,
                *perms
            )
        }
    }

    /* Make a request for session data */
    private fun getSession() {
        Log.i(TAG, "getSession")

        apiService?.session?.enqueue(object : Callback<GetSessionResponse?> {
            override fun onResponse(call: Call<GetSessionResponse?>, response: Response<GetSessionResponse?>) {
                response.body()?.also {
                    initializeSession(it.apiKey, it.sessionId, it.token)
                }
            }

            override fun onFailure(call: Call<GetSessionResponse?>, t: Throwable) {
                throw RuntimeException(t.message)
            }
        })
    }

    private fun initializeSession(apiKey: String, sessionId: String, token: String) {
        Log.i(TAG, "apiKey: $apiKey")
        Log.i(TAG, "sessionId: $sessionId")
        Log.i(TAG, "token: $token")

        /*
        The context used depends on the specific use case, but usually, it is desired for the session to
        live outside of the Activity e.g: live between activities. For a production applications,
        it's convenient to use Application context instead of Activity context.
         */
        session = Session.Builder(this, apiKey, sessionId).build().also {
            it.setSessionListener(sessionListener)
            it.connect(token)
        }
    }

    private fun initRetrofit() {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(ServerConfig.CHAT_SERVER_URL)
            .addConverterFactory(MoshiConverterFactory.create())
            .client(client)
            .build().also {
                apiService = it.create(APIService::class.java)
            }
    }

    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PERMISSIONS_REQUEST_CODE = 124
    }

    class CaptureFrameTransformer(private val resources: Resources) : CustomVideoTransformer {

        // Get the image in bitmap format

        private lateinit var yuvImage: YuvImage

        fun getImage(): Bitmap {
            if (this::yuvImage.isInitialized){
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    Rect(
                        0, 0,
                        yuvImage.width, yuvImage.height
                    ), 100, out
                )
                val imageBytes = out.toByteArray()
                val bm = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                return bm
            }else{
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                return bitmap
            }

        }

        /** Convert YV12 (YYYYYYYY:UU:VV) to NV21 (YYYYYYYYY:VUVU)  */
        fun YV12toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
            var output = ByteArray(input.size)
            val size = width * height
            val quarter = size / 4
            val u0 = size + quarter
            System.arraycopy(input, 0, output, 0, size) // Y is same
            var v = size
            var u = u0
            var o = size
            while (v < u0) {
                output[o] = input[v] // For NV21, V first
                output[o + 1] = input[u] // For NV21, U second
                u++
                v++
                o += 2
            }
            return output
        }

        override fun onTransform(frame: BaseVideoRenderer.Frame) {
            val ib = ByteBuffer.allocate(frame.height * frame.width * 2)
            ib.put(frame.yplane)
            ib.put(frame.vplane)
            ib.put(frame.uplane)
            this.yuvImage = YuvImage(
                YV12toNV21(ib.array(),frame.width, frame.height ),
                ImageFormat.NV21, frame.width, frame.height, null
            )


        }
    }

    fun getImageFromTransformer(view: View){
        val bm: Bitmap = captureFrameTransformer.getImage()
        if(bm.height == 1)//Null bitmap, don't save
        {
            Log.i(TAG, "EMPTY Bitmap")
        }else{
            recent_cap = "DemoCap_"+System.currentTimeMillis().toString()+".jpg"
            savePhotoToExternalStorage(recent_cap!!, bm)
            Log.i(TAG, "THERE IS A Bitmap")
        }
    }

    private fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)


        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM+"/Screenshots");
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
        }
        return try {
            contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                Log.e("file path", File(uri.path.toString()).absolutePath)
                contentResolver.openOutputStream(uri).use { outputStream ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                        throw IOException("Couldn't save bitmap")
                    }
                }
            } ?: throw IOException("Couldn't create MediaStore entry")
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun viewCaps(view: View?) {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        intent.type = "image/*"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)

    }


}
