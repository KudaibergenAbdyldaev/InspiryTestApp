package bite.vision.render_template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.media.MediaCodecInfo.CodecCapabilities
import android.opengl.GLES20
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.lang.RuntimeException
import java.util.*
import androidx.core.content.ContextCompat.startActivity

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat


/**
 * Encoding Thread
 */
enum class GENERATE_TYPE {
    INPUT_BUFFER,  // generate frame to a byte[]
    INPUT_SURFACE // generate frame into a surface with OpenGL ES 2.0
}

var mGenerateType = GENERATE_TYPE.INPUT_BUFFER
var bufferWithBitmap = true // use a bitmap instead of generated data

class EncodingThread(
    width: Int, height: Int, bitrate: Int,
    var layout: LinearLayout, var outputPath: String, var onFinish: OnFinish,
) :
    Runnable {


    interface OnFinish {
        fun finishRecord(outputPath: String)
    }

    private val TAG = "EncodingThread"
    private val DEBUG = true

    /**
     * MediaCodec Stuffs
     */
    private var mEncoder: MediaCodec? = null

    // parameters for the encoder
    private val MIME_TYPE = "video/avc" // H.264 Advanced Video Coding
    private val IFRAME_INTERVAL = 10 // 10 seconds between I-frames
    private val FRAME_RATE = 20 //fps
    private val mWidth: Int
    private val mHeight: Int
    private val mBitRate: Int
    private var colorFormat = 0

    /**
     * MediaMuxer
     */
    private var mMuxer: MediaMuxer? = null
    private var inputSurface: InputSurface? = null

    /**
     * Test Data
     */
    private val imageData: ByteArray? = null
    override fun run() {
        Log.d(TAG, "================================================")
        Log.d(TAG, "start encoding")
        /**
         * Prepare Encoder
         */
        prepareEncoder()
        /**
         * Prepare MediaMuxer
         */
        prepareMuxer()
        /**
         * Generate video and save to sdcard
         */
        doGenerateSaveVideo()
        /**
         * Release Encoder
         */
        releaseEncoder()
        /**
         * Release Muxer
         */
        releaseMuxer()
        /**
         * Finish Thread
         */
        Log.d(TAG, "finish encoding")
        Log.d(TAG, "================================================")

        Handler(Looper.getMainLooper()).post {

            onFinish.finishRecord(outputPath)
        }


    }

    private fun prepareMuxer() {


        // Create a MediaMuxer. We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = try {
            MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (ioe: IOException) {
            throw RuntimeException("MediaMuxer creation failed", ioe)
        }
    }

    private fun releaseMuxer() {
        if (mMuxer != null) {
            try {
                mMuxer!!.stop()
                mMuxer!!.release()
                mMuxer = null
            } catch (e: Exception) {
                Log.e(TAG, "You started a Muxer but haven't fed any data into it")
                e.printStackTrace()
            }
        }
    }

    private var convertor: NV21Convertor? = null
    private fun prepareEncoder() {
        try {
            val codecInfo = selectCodec(MIME_TYPE)
            if (codecInfo == null) {
                // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                if (DEBUG) Log.e(TAG, "Unable to find an appropriate codec for $MIME_TYPE")
                return
            }
            if (DEBUG) Log.d(TAG, "found codec: " + codecInfo.name)
            colorFormat = if (mGenerateType == GENERATE_TYPE.INPUT_BUFFER) {
                selectColorFormat(codecInfo, MIME_TYPE)
            } else {
                CodecCapabilities.COLOR_FormatSurface
            }
            if (DEBUG) Log.d(TAG, "found colorFormat: $colorFormat")
            // We avoid the device-specific limitations on width and height by using values that
            // are multiples of 16, which all tested devices seem to be able to handle.
            val format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight)
            // Set some properties.  Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            Log.d(TAG, "format: $format")
            // Create a MediaCodec for the desired codec, then configure it as an encoder with
            // our desired properties.
            mEncoder = MediaCodec.createByCodecName(codecInfo.name)
            mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (mGenerateType == GENERATE_TYPE.INPUT_SURFACE) {
                inputSurface = InputSurface(mEncoder!!.createInputSurface())
            }
            convertor = NV21Convertor()
            convertor!!.setSize(mWidth, mHeight)
            convertor!!.sliceHeigth = mHeight
            convertor!!.stride = mWidth
            convertor!!.yPadding = 0
            convertor!!.setEncoderColorFormat(colorFormat)
            mEncoder!!.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseEncoder() {
        if (DEBUG) Log.d(TAG, "releasing codec")
        if (inputSurface != null) {
            inputSurface!!.release()
        }
        if (mEncoder != null) {
            mEncoder!!.stop()
            mEncoder!!.release()
        }
    }

    private fun doGenerateSaveVideo() {
        if (DEBUG) Log.d(TAG, "---------- doGenerateSaveVideo ------------")
        /**
         * Init parameters
         */
        val TIMEOUT_USEC = 10000
        val encoderInputBuffers = mEncoder!!.inputBuffers
        var encoderOutputBuffers = mEncoder!!.outputBuffers
        val info = MediaCodec.BufferInfo()
        var generateIndex = 0
        val NUM_FRAMES = 120 // number of frame required to generate

        // The size of a frame of video data, in the formats we handle, is stride*sliceHeight
        // for Y, and (stride/2)*(sliceHeight/2) for each of the Cb and Cr channels.  Application
        // of algebra and assuming that stride==width and sliceHeight==height yields:
        val frameData = ByteArray(mWidth * mHeight * 3 / 2)
        /**
         * Populate imageData byte[] with an image
         */
        /**
         * Loop through 5 seconds and generate + save file
         */
        var inputDone = false
        var encoderDone = false
        while (!encoderDone) {
            if (DEBUG) Log.d(TAG, "---- Loop ----")
            /**
             * Generate
             */
            if (!inputDone) {
                var inputBufIndex = 0
                if (mGenerateType == GENERATE_TYPE.INPUT_BUFFER) {
                    inputBufIndex = mEncoder!!.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                    if (DEBUG) Log.d(TAG, "inputBufIndex=$inputBufIndex")
                }
                if (inputBufIndex >= 0) {
                    val ptsUsec = computePresentationTime(generateIndex)
                    if (generateIndex == NUM_FRAMES) {
                        inputDone = true
                        // Send an empty frame with the end-of-stream flag set.  If we set EOS
                        // on a frame with data, that frame data will be ignored, and the
                        // output will be short one frame.
                        if (mGenerateType == GENERATE_TYPE.INPUT_BUFFER) {
                            mEncoder!!.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            mEncoder!!.signalEndOfInputStream()
                        }
                        if (DEBUG) Log.d(TAG, "sent input EOS (with zero-length frame)")
                    } else {
                        if (mGenerateType == GENERATE_TYPE.INPUT_BUFFER) {
                            val inputBuf = encoderInputBuffers[inputBufIndex]

                            // the buffer should be sized to hold one full frame
                            inputBuf.clear()
                            if (!bufferWithBitmap) {
                                /**
                                 * use auto generated frame
                                 */
                                generateFrame(generateIndex, colorFormat, frameData)
                                inputBuf.put(frameData)
                            } else {
                                /**
                                 * use a bitmap in resource
                                 */
                                val bitmap = Bitmap.createBitmap(layout.width,
                                    layout.height,
                                    Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bitmap)
                                layout.draw(canvas)
                                getNV21(mWidth, mHeight, bitmap)?.let {
                                    convertor!!.convert(it,
                                        inputBuf)
                                }
                            }
                            mEncoder!!.queueInputBuffer(inputBufIndex,
                                0,
                                frameData.size,
                                ptsUsec,
                                0)
                        } else {
                            inputSurface!!.makeCurrent()
                            generateSurfaceFrame(generateIndex)
                            inputSurface!!.setPresentationTime(computePresentationTime(generateIndex) * 1000)
                            if (DEBUG) Log.d(TAG, "inputSurface swapBuffers")
                            inputSurface!!.swapBuffers()
                        }
                        if (DEBUG) Log.d(TAG, "submitted frame $generateIndex to enc")
                    }
                    generateIndex++
                }
            }

            // Check for output from the encoder.  If there's no output yet, we either need to
            // provide more input, or we need to wait for the encoder to work its magic.  We
            // can't actually tell which is the case, so if we can't get an output buffer right
            // away we loop around and see if it wants more input.
            //
            // Once we get EOS from the encoder, we don't need to do this anymore.
            if (!encoderDone) {
                val encoderStatus = mEncoder!!.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (DEBUG) Log.d(TAG, "no output from encoder available")
                    //                        break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = mEncoder!!.outputBuffers
                    if (DEBUG) Log.d(TAG, "encoder output buffers changed")
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // not expected for an encoder
                    val newFormat = mEncoder!!.outputFormat
                    if (DEBUG) Log.d(TAG, "encoder output format changed: $newFormat")

                    // now that we have the Magic Goodies, start the muxer
                    mMuxer!!.addTrack(newFormat)
                    mMuxer!!.start()
                } else if (encoderStatus < 0) {
                    Log.e(TAG,
                        "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                } else { // encoderStatus >= 0
                    val encodedData = encoderOutputBuffers[encoderStatus]
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer $encoderStatus was null")
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData!!.position(info.offset)
                    encodedData.limit(info.offset + info.size)
                    /**
                     * Save to mp4
                     */
                    val data = ByteArray(info.size)
                    encodedData[data]
                    encodedData.position(info.offset)
                    try {
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        mMuxer!!.writeSampleData(0, encodedData, info)
                        Log.d(TAG, "sent " + info.size + " bytes to muxer")
                    } catch (ioe: Exception) {
                        Log.w(TAG, "failed writing debug data to file")
                        throw RuntimeException(ioe)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec config info.  Only expected on first packet.  One way to
                        // handle this is to manually stuff the data into the MediaFormat
                        // and pass that to configure().  We do that here to exercise the API.
                    } else {
                        encoderDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (DEBUG) Log.d(TAG, "passed " + info.size + " bytes to decoder"
                                + if (encoderDone) " (EOS)" else "")
                    }
                    mEncoder!!.releaseOutputBuffer(encoderStatus, false)
                } // end else encoderStatus
            }
        } // end while
        if (DEBUG) Log.d(TAG, "---------- end - doGenerateSaveVideo ------------")
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private fun computePresentationTime(frameIndex: Int): Long {
        return (132 + frameIndex * 1000000 / FRAME_RATE).toLong()
    }

    /**
     * Generates a frame of data using GL commands.
     */
    private fun generateSurfaceFrame(frameIndex: Int) {
        var frameIndex = frameIndex
        frameIndex %= 8
        val startX: Int
        val startY: Int
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (mWidth / 4)
            startY = mHeight / 2
        } else {
            startX = (7 - frameIndex) * (mWidth / 4)
            startY = 0
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(startX, startY, mWidth / 4, mHeight / 2)
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Generates data for frame N into the supplied buffer.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     * 0 1 2 3
     * 7 6 5 4
    </pre> *
     * We draw one of the eight rectangles and leave the rest set to the zero-fill color.
     */
    private fun generateFrame(frameIndex: Int, colorFormat: Int, frameData: ByteArray) {
        var frameIndex = frameIndex
        val HALF_WIDTH = mWidth / 2
        val semiPlanar = isSemiPlanarYUV(colorFormat)
        // Set to zero.  In YUV this is a dull green.
        Arrays.fill(frameData, 0.toByte())
        /**
         * Use a image instead of dull green
         */
        if (imageData != null) {
//                frameData = imageData.clone();
        }
        val startX: Int
        val startY: Int
        var countX: Int
        var countY: Int
        frameIndex %= 8
        //frameIndex = (frameIndex / 8) % 8;    // use this instead for debug -- easier to see
        if (frameIndex < 4) {
            startX = frameIndex * (mWidth / 4)
            startY = 0
        } else {
            startX = (7 - frameIndex) * (mWidth / 4)
            startY = mHeight / 2
        }
        for (y in startY + mHeight / 2 - 1 downTo startY) {
            for (x in startX + mWidth / 4 - 1 downTo startX) {
                if (semiPlanar) {
                    // full-size Y, followed by UV pairs at half resolution
                    // e.g. Nexus 4 OMX.qcom.video.encoder.avc COLOR_FormatYUV420SemiPlanar
                    // e.g. Galaxy Nexus OMX.TI.DUCATI1.VIDEO.H264E
                    //        OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
                    frameData[y * mWidth + x] = TEST_Y.toByte()
                    if (x and 0x01 == 0 && y and 0x01 == 0) {
                        frameData[mWidth * mHeight + y * HALF_WIDTH + x] = TEST_U.toByte()
                        frameData[mWidth * mHeight + y * HALF_WIDTH + x + 1] =
                            TEST_V.toByte()
                    }
                } else {
                    // full-size Y, followed by quarter-size U and quarter-size V
                    // e.g. Nexus 10 OMX.Exynos.AVC.Encoder COLOR_FormatYUV420Planar
                    // e.g. Nexus 7 OMX.Nvidia.h264.encoder COLOR_FormatYUV420Planar
                    frameData[y * mWidth + x] = TEST_Y.toByte()
                    if (x and 0x01 == 0 && y and 0x01 == 0) {
                        frameData[mWidth * mHeight + y / 2 * HALF_WIDTH + x / 2] =
                            TEST_U.toByte()
                        frameData[mWidth * mHeight + HALF_WIDTH * (mHeight / 2) + y / 2 * HALF_WIDTH + x / 2] =
                            TEST_V.toByte()
                    }
                }
            }
        }
    } //end generateFrame

    companion object {
        private const val TEST_Y = 120 // YUV values for colored rect
        private const val TEST_U = 160
        private const val TEST_V = 200
        private const val TEST_R0 = 0 // RGB equivalent of {0,0,0}
        private const val TEST_G0 = 136
        private const val TEST_B0 = 0
        private const val TEST_R1 = 236 // RGB equivalent of {120,160,200}
        private const val TEST_G1 = 50
        private const val TEST_B1 = 186
    }

    init {
        if (DEBUG) Log.d(TAG, "Constructor")
        mWidth = width
        mHeight = height
        mBitRate = bitrate
    }
}

/**
 * Helper functions
 */

/**
 * Helper functions
 */
/**
 * Returns the first codec capable of encoding the specified MIME type, or null if no
 * match was found.
 */
private fun selectCodec(mimeType: String): MediaCodecInfo? {
    val numCodecs = MediaCodecList.getCodecCount()
    for (i in 0 until numCodecs) {
        val codecInfo = MediaCodecList.getCodecInfoAt(i)
        if (!codecInfo.isEncoder) {
            continue
        }
        val types = codecInfo.supportedTypes
        for (j in types.indices) {
            if (types[j].equals(mimeType, ignoreCase = true)) {
                return codecInfo
            }
        }
    }
    return null
}

/**
 * Returns a color format that is supported by the codec and by this test code.  If no
 * match is found, this throws a test failure -- the set of formats known to the test
 * should be expanded for new platforms.
 */
private fun selectColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
    for (i in capabilities.colorFormats.indices) {
        val colorFormat = capabilities.colorFormats[i]
        if (isRecognizedFormat(colorFormat)) {
            return colorFormat
        }
    }
    return 0 // not reached
}

/**
 * Returns true if this is a color format that this test code understands (i.e. we know how
 * to read and generate frames in this format).
 */
private fun isRecognizedFormat(colorFormat: Int): Boolean {
    return when (colorFormat) {
        CodecCapabilities.COLOR_FormatYUV420Planar, CodecCapabilities.COLOR_FormatYUV420PackedPlanar, CodecCapabilities.COLOR_FormatYUV420SemiPlanar, CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar -> true
        else -> false
    }
}

/**
 * Returns true if the specified color format is semi-planar YUV.  Throws an exception
 * if the color format is not recognized (e.g. not YUV).
 */
private fun isSemiPlanarYUV(colorFormat: Int): Boolean {
    return when (colorFormat) {
        CodecCapabilities.COLOR_FormatYUV420Planar, CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> false
        CodecCapabilities.COLOR_FormatYUV420SemiPlanar, CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar -> true
        else -> throw RuntimeException("unknown format $colorFormat")
    }
}

/**
 * Conversion tool
 */
// untested function
fun getNV21(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray? {
    val argb = IntArray(inputWidth * inputHeight)
    scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
    val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
    encodeYUV420SP(yuv, argb, inputWidth, inputHeight)
    scaled.recycle()
    return yuv
}

fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
    val frameSize = width * height
    var yIndex = 0
    var uvIndex = frameSize
    var a: Int
    var R: Int
    var G: Int
    var B: Int
    var Y: Int
    var U: Int
    var V: Int
    var index = 0
    for (j in 0 until height) {
        for (i in 0 until width) {
            a = argb[index] and -0x1000000 shr 24 // a is not used obviously
            R = argb[index] and 0xff0000 shr 16
            G = argb[index] and 0xff00 shr 8
            B = argb[index] and 0xff shr 0

            // well known RGB to YUV algorithm
            Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
            U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
            V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

            // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
            //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
            //    pixel AND every other scanline.
            yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
            if (j % 2 == 0 && index % 2 == 0) {
                yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
            }
            index++
        }
    }
}