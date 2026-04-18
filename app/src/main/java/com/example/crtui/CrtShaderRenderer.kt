package com.example.crtui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CrtShaderRenderer(private val terminalView: TerminalView) : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        
        uniform vec3 uBgColor; 
        uniform vec3 uTextColor; 
        uniform float uGlowIntensity;

        void main() {
            vec4 textCol = texture2D(uTexture, vTexCoord);
            
            // Base background with subtle scanline modulation
            float scanline = sin(vTexCoord.y * 1200.0) * 0.03;
            vec3 baseColor = uBgColor - scanline;

            // Gaussian-style bloom sampling adjacent pixels
            float bloom = 0.0;
            float offset = 0.003;
            bloom += texture2D(uTexture, vTexCoord + vec2(offset, 0.0)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(-offset, 0.0)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(0.0, offset)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(0.0, -offset)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(offset, offset)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(-offset, -offset)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(offset, -offset)).a;
            bloom += texture2D(uTexture, vTexCoord + vec2(-offset, offset)).a;

            // Combine text, background, and the glowing halo
            vec3 finalColor = mix(baseColor, textCol.rgb, textCol.a);
            finalColor += uTextColor * (bloom * 0.15 * uGlowIntensity);
            
            gl_FragColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    private var programHandle = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var bgColorHandle = 0
    private var textColorHandle = 0
    private var glowHandle = 0
    private val textureId = IntArray(1)

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    // FIX: Restored the geometry coordinates defining the screen
    private val cubeCoords = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
    private val textureCoords = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)

    @Volatile var isDirty = true

    var currentBgColor = floatArrayOf(0.04f, 0.12f, 0.04f)
    var currentTextColor = floatArrayOf(0.2f, 1.0f, 0.0f)
    var glowIntensity = 1.0f

    private var terminalBitmap: Bitmap? = null
    private var internalCanvas: Canvas? = null

    init {
        vertexBuffer = ByteBuffer.allocateDirect(cubeCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(cubeCoords).position(0)
        texCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoordBuffer.put(textureCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        val vertexShaderHandle = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShaderHandle = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programHandle = GLES20.glCreateProgram()
        GLES20.glAttachShader(programHandle, vertexShaderHandle)
        GLES20.glAttachShader(programHandle, fragmentShaderHandle)
        GLES20.glLinkProgram(programHandle)

        positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(programHandle, "uTexture")
        bgColorHandle = GLES20.glGetUniformLocation(programHandle, "uBgColor")
        textColorHandle = GLES20.glGetUniformLocation(programHandle, "uTextColor")
        glowHandle = GLES20.glGetUniformLocation(programHandle, "uGlowIntensity")

        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        terminalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        internalCanvas = Canvas(terminalBitmap!!)
        isDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (isDirty) {
            val canvas = internalCanvas ?: return
            val bitmap = terminalBitmap ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            terminalView.renderContentForTexture(canvas)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            isDirty = false
        }

        GLES20.glUseProgram(programHandle)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniform3f(bgColorHandle, currentBgColor[0], currentBgColor[1], currentBgColor[2])
        GLES20.glUniform3f(textColorHandle, currentTextColor[0], currentTextColor[1], currentTextColor[2])
        GLES20.glUniform1f(glowHandle, glowIntensity)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shaderHandle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shaderHandle, shaderCode)
        GLES20.glCompileShader(shaderHandle)
        return shaderHandle
    }
}