package bite.vision.testapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import android.view.animation.LinearInterpolator
import bite.vision.render_template.EncodingThread
import java.io.File
import java.lang.StringBuilder
import java.util.*
import android.os.Environment
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat

import android.os.Build

class MainActivitykt : AppCompatActivity(), EncodingThread.OnFinish {

    lateinit var linearLayout: LinearLayout
    lateinit var txtHelloWorld: TextView
    lateinit var renderTemplate: TextView


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtHelloWorld = findViewById(R.id.text_hello_world)
        renderTemplate = findViewById(R.id.render_template)
        linearLayout = findViewById(R.id.gl_layout)


        val animator = ValueAnimator.ofFloat(0.03f, 0.57f)
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.RESTART
        animator.interpolator = LinearInterpolator()
        animator.duration = 2000L
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            val width = txtHelloWorld.width.toFloat()
            val translationX = width * progress
            txtHelloWorld.translationX = translationX
        }
        animator.start()

        renderTemplate.setOnClickListener {
            Toast.makeText(applicationContext, "Start encoding", Toast.LENGTH_SHORT).show()

            renderTemplate.isEnabled = false
            renderTemplate.isClickable = false
            var h = linearLayout.height.toString()
            h = h.substring(0, h.length - 2) + "00"

            var w = linearLayout.width.toString()
            w = w.substring(0, w.length - 2) + "00"

            val prefix: String = UUID.randomUUID().toString()

            val dir = File(Environment.getExternalStorageDirectory()
                .toString() + "/Download/files/")
            dir.mkdirs()

            val outputPath: String = File(dir,
                "vid_$prefix.mp4").toString()

            if (linearLayout.height != 0) {
                Thread(EncodingThread(w.toInt(),
                    h.toInt(),
                    200000,
                    linearLayout, outputPath,
                    this@MainActivitykt,this)).start()
            }

        }
        isStoragePermissionGranted()
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                true
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1)
                false
            }
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        }
    }

    override fun finishRecord(outputPath: String) {
        Toast.makeText(this,
            "Finish: saved video",
            Toast.LENGTH_SHORT).show()
        renderTemplate.isEnabled = true
        renderTemplate.isClickable = true
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(outputPath))
        intent.setDataAndType(Uri.parse(outputPath), "video/mp4")
        startActivity(intent)
    }
}