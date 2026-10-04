package fr.ftnl.apcdeck.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.window.OnBackInvokedDispatcher
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** L'interface d'APC Deck (la même que sur PC), servie par le moteur local et affichée dans une WebView. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pendingCamera: PermissionRequest? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // on joue dessus : pas de mise en veille

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(0xFF141218.toInt())
            webViewClient = object : WebViewClient() {
                // Liens externes (nouveautés d'un plugin…) : navigateur du téléphone.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == "127.0.0.1") return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Caméra demandée par la page (scanner le QR code d'un PC).
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) return@runOnUiThread request.deny()
                        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            pendingCamera = request
                            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                        }
                    }
                }

                // <input type="file"> : sons de la Soundboard, plugins (.jar).
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    return try {
                        startActivityForResult(Intent.createChooser(intent, "Choisir un fichier"), REQUEST_FILES)
                        true
                    } catch (_: Exception) {
                        pendingFiles = null
                        false
                    }
                }
            }
        }
        // Bord à bord (Android 15) : la page ne passe pas sous les barres du système.
        web.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(web)
        if (Build.VERSION.SDK_INT >= 33) {
            // Retour arrière prédictif (Android 13+, imposé à partir d'Android 16) : onBackPressed n'est plus appelé.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        web.loadUrl((application as ApcApp).engine.web.uiUrl)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CAMERA) return
        val request = pendingCamera ?: return
        pendingCamera = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        else request.deny()
    }

    @Deprecated("API Activity : résultat du sélecteur de fichiers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILES) return super.onActivityResult(requestCode, resultCode, data)
        val callback = pendingFiles ?: return
        pendingFiles = null
        val uris = when {
            resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    @Deprecated("API Activity : retour arrière (avant Android 13)")
    override fun onBackPressed() = back()

    private fun back() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true) // le moteur continue en arrière-plan
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAMERA = 1
        const val REQUEST_FILES = 2
        const val REQUEST_NOTIFICATIONS = 3
    }
}
