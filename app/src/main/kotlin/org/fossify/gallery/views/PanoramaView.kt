package org.fossify.gallery.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shows an equirectangular panorama from the inside of a sphere, drag to look around and pinch to zoom.
 * A panorama which covers only a part of the sphere is drawn on just that part.
 */
@SuppressLint("ViewConstructor")
class PanoramaView(context: Context, bitmap: Bitmap, private val area: PanoramaArea, private val onClick: () -> Unit) : GLSurfaceView(context) {
    /** The part of the full sphere covered by the image, all values are fractions from 0 to 1. */
    data class PanoramaArea(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f)

    companion object {
        private const val MAX_FOV = 100f
        private const val MIN_FOV = 20f
        private const val DEFAULT_FOV = 70f
        private const val MESH_COLUMNS = 72
        private const val MESH_ROWS = 36
        private const val FULL_CIRCLE = 360f
        private const val HALF_CIRCLE = 180f
        private const val COORDS_PER_VERTEX = 3
        private const val FLOAT_BYTES = 4
        private const val SHORT_BYTES = 2

        private const val VERTEX_SHADER = """
            uniform mat4 uMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    // degrees, the center of the covered area is where the view starts
    private val minYaw = (area.left - 0.5f) * FULL_CIRCLE
    private val maxYaw = (area.right - 0.5f) * FULL_CIRCLE
    private val maxPitch = (0.5f - area.top) * HALF_CIRCLE
    private val minPitch = (0.5f - area.bottom) * HALF_CIRCLE
    private val isFullCircle = area.right - area.left > 0.99f

    @Volatile
    private var yaw = (minYaw + maxYaw) / 2

    @Volatile
    private var pitch = (minPitch + maxPitch) / 2

    @Volatile
    private var fov = DEFAULT_FOV.coerceAtMost((maxPitch - minPitch).coerceAtLeast(MIN_FOV))

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val degreesPerPixel = fov / height.coerceAtLeast(1)
            // the image follows the finger
            var newYaw = yaw + distanceX * degreesPerPixel
            newYaw = if (isFullCircle) {
                ((newYaw + HALF_CIRCLE) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE - HALF_CIRCLE
            } else {
                newYaw.coerceIn(minYaw, maxYaw)
            }

            yaw = newYaw
            pitch = (pitch - distanceY * degreesPerPixel).coerceIn(minPitch, maxPitch)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onClick()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fov = (fov / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(PanoramaRenderer(bitmap))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    private inner class PanoramaRenderer(private var bitmap: Bitmap?) : Renderer {
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val matrix = FloatArray(16)
        private var program = 0
        private var textureId = 0
        private var aspectRatio = 1f
        private var indexCount = 0
        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var texCoordBuffer: FloatBuffer
        private lateinit var indexBuffer: ShortBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            program = createProgram()
            buildMesh()
            loadTexture()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspectRatio = width.toFloat() / height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0 || textureId == 0) {
                return
            }

            Matrix.perspectiveM(projectionMatrix, 0, fov, aspectRatio, 0.1f, 10f)
            Matrix.setIdentityM(viewMatrix, 0)
            Matrix.rotateM(viewMatrix, 0, -pitch, 1f, 0f, 0f)
            Matrix.rotateM(viewMatrix, 0, yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(matrix, 0, projectionMatrix, 0, viewMatrix, 0)

            GLES20.glUseProgram(program)
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun buildMesh() {
            val vertexCount = (MESH_COLUMNS + 1) * (MESH_ROWS + 1)
            val vertices = FloatArray(vertexCount * COORDS_PER_VERTEX)
            val texCoords = FloatArray(vertexCount * 2)
            var vertexIndex = 0
            var texIndex = 0
            for (row in 0..MESH_ROWS) {
                val v = row / MESH_ROWS.toFloat()
                val fullV = area.top + (area.bottom - area.top) * v
                val latitude = Math.toRadians(((0.5f - fullV) * HALF_CIRCLE).toDouble())
                for (column in 0..MESH_COLUMNS) {
                    val u = column / MESH_COLUMNS.toFloat()
                    val fullU = area.left + (area.right - area.left) * u
                    val longitude = Math.toRadians(((fullU - 0.5f) * FULL_CIRCLE).toDouble())
                    vertices[vertexIndex++] = (cos(latitude) * sin(longitude)).toFloat()
                    vertices[vertexIndex++] = sin(latitude).toFloat()
                    vertices[vertexIndex++] = (-cos(latitude) * cos(longitude)).toFloat()
                    texCoords[texIndex++] = u
                    texCoords[texIndex++] = v
                }
            }

            val indices = ShortArray(MESH_COLUMNS * MESH_ROWS * 6)
            var index = 0
            for (row in 0 until MESH_ROWS) {
                for (column in 0 until MESH_COLUMNS) {
                    val topLeft = row * (MESH_COLUMNS + 1) + column
                    val bottomLeft = topLeft + MESH_COLUMNS + 1
                    indices[index++] = topLeft.toShort()
                    indices[index++] = bottomLeft.toShort()
                    indices[index++] = (topLeft + 1).toShort()
                    indices[index++] = (topLeft + 1).toShort()
                    indices[index++] = bottomLeft.toShort()
                    indices[index++] = (bottomLeft + 1).toShort()
                }
            }

            indexCount = indices.size
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * FLOAT_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(vertices).position(0)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * FLOAT_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
            texCoordBuffer.put(texCoords).position(0)
            indexBuffer = ByteBuffer.allocateDirect(indices.size * SHORT_BYTES).order(ByteOrder.nativeOrder()).asShortBuffer()
            indexBuffer.put(indices).position(0)
        }

        private fun loadTexture() {
            val image = bitmap ?: return
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0)
        }

        private fun createProgram(): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val newProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(newProgram, vertexShader)
            GLES20.glAttachShader(newProgram, fragmentShader)
            GLES20.glLinkProgram(newProgram)
            return newProgram
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}
