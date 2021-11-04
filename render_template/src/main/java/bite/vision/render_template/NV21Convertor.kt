package bite.vision.render_template

import android.media.MediaCodecInfo
import java.nio.ByteBuffer

class NV21Convertor {
    var sliceHeigth = 0
    private var mHeight = 0
    var stride = 0
    private var mWidth = 0
    private var mSize = 0
    var planar = false
    var uVPanesReversed = false
        private set
    var yPadding = 0
    private var mBuffer: ByteArray? = null
    var mCopy: ByteBuffer? = null
    fun setSize(width: Int, height: Int) {
        mHeight = height
        mWidth = width
        sliceHeigth = height
        stride = width
        mSize = mWidth * mHeight
    }

    val bufferSize: Int
        get() = 3 * mSize / 2

    fun setEncoderColorFormat(colorFormat: Int) {
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar -> planar =
                false
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> planar =
                true
        }
    }

    fun setColorPanesReversed(b: Boolean) {
        uVPanesReversed = b
    }

    fun convert(data: ByteArray, buffer: ByteBuffer) {
        val result = convert(data)
        val min = if (buffer.capacity() < data.size) buffer.capacity() else data.size
        buffer.put(result, 0, min)
    }

    fun convert(data: ByteArray): ByteArray {

        // A buffer large enough for every case
        if (mBuffer == null || mBuffer!!.size != 3 * sliceHeigth * stride / 2 + yPadding) {
            mBuffer = ByteArray(3 * sliceHeigth * stride / 2 + yPadding)
        }
        if (!planar) {
            if (sliceHeigth == mHeight && stride == mWidth) {
                // Swaps U and V
                if (!uVPanesReversed) {
                    var i = mSize
                    while (i < mSize + mSize / 2) {
                        mBuffer!![0] = data[i + 1]
                        data[i + 1] = data[i]
                        data[i] = mBuffer!![0]
                        i += 2
                    }
                }
                if (yPadding > 0) {
                    System.arraycopy(data, 0, mBuffer, 0, mSize)
                    System.arraycopy(data, mSize, mBuffer, mSize + yPadding, mSize / 2)
                    return mBuffer!!
                }
                return data
            }
        } else {
            if (sliceHeigth == mHeight && stride == mWidth) {
                // De-interleave U and V
                if (!uVPanesReversed) {
                    var i = 0
                    while (i < mSize / 4) {
                        mBuffer!![i] = data[mSize + 2 * i + 1]
                        mBuffer!![mSize / 4 + i] = data[mSize + 2 * i]
                        i += 1
                    }
                } else {
                    var i = 0
                    while (i < mSize / 4) {
                        mBuffer!![i] = data[mSize + 2 * i]
                        mBuffer!![mSize / 4 + i] = data[mSize + 2 * i + 1]
                        i += 1
                    }
                }
                if (yPadding == 0) {
                    System.arraycopy(mBuffer, 0, data, mSize, mSize / 2)
                } else {
                    System.arraycopy(data, 0, mBuffer, 0, mSize)
                    System.arraycopy(mBuffer, 0, mBuffer, mSize + yPadding, mSize / 2)
                    return mBuffer!!
                }
                return data
            }
        }
        return data
    }
}
