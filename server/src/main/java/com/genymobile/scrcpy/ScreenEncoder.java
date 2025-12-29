package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.device.DisplayInfo;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.VirtualDisplayListener;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen encoder for WebSocket mode.
 * Captures the screen and encodes it to H.264, sending frames via WebSocket.
 */
public class ScreenEncoder implements Connection.StreamInvalidateListener, Runnable {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final long NO_PTS = -1;

    private final AtomicBoolean streamIsInvalid = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);
    private Thread encoderThread;

    private long ptsOrigin;
    private Connection connection;
    private VideoSettings videoSettings;
    private MediaFormat format;
    private int timeout = -1;

    private IBinder display;
    private VirtualDisplay virtualDisplay;

    // Screen info for the current display
    private Size videoSize;
    private int displayId;

    // VirtualDisplayListener for notifying controller about display changes
    private VirtualDisplayListener vdListener;

    public ScreenEncoder(VideoSettings videoSettings) {
        this.videoSettings = videoSettings;
        this.displayId = videoSettings.getDisplayId();
        updateFormat();
    }

    public void setVirtualDisplayListener(VirtualDisplayListener listener) {
        this.vdListener = listener;
    }

    private void updateFormat() {
        format = createFormat(videoSettings);
        int maxFps = videoSettings.getMaxFps();
        if (maxFps > 0) {
            timeout = 1_000_000 / maxFps;
        } else {
            timeout = -1;
        }
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void onStreamInvalidate() {
        Ln.d("Invalidate stream");
        streamIsInvalid.set(true);
        updateFormat();
    }

    public boolean consumeStreamInvalidation() {
        return streamIsInvalid.getAndSet(false);
    }

    public boolean isAlive() {
        return encoderThread != null && encoderThread.isAlive();
    }

    public void streamScreen() throws IOException {
        // Note: Workarounds.apply() is already called in Server.scrcpyWebSocket()
        // Do not call it again here to avoid issues with duplicate initialization
        internalStreamScreen();
    }

    private void internalStreamScreen() throws IOException {
        updateFormat();
        connection.setStreamInvalidateListener(this);
        boolean alive;
        try {
            do {
                MediaCodec codec = createCodec(videoSettings.getEncoderName());
                if (display != null) {
                    destroyDisplay(display);
                    display = null;
                }
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }

                // Get display info
                DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
                if (displayInfo == null) {
                    throw new IOException("Display " + displayId + " not found");
                }

                Size displaySize = displayInfo.getSize();
                int rotation = displayInfo.getRotation();

                // Calculate video size based on bounds
                Size bounds = videoSettings.getBounds();
                if (bounds != null && bounds.getWidth() > 0 && bounds.getHeight() > 0) {
                    videoSize = computeVideoSize(displaySize, bounds);
                } else {
                    videoSize = displaySize;
                }

                // Ensure dimensions are multiples of 8
                int width = videoSize.getWidth() & ~7;
                int height = videoSize.getHeight() & ~7;
                videoSize = new Size(width, height);

                Rect contentRect = new Rect(0, 0, displaySize.getWidth(), displaySize.getHeight());
                Rect videoRect = new Rect(0, 0, videoSize.getWidth(), videoSize.getHeight());

                setSize(format, videoSize.getWidth(), videoSize.getHeight());
                configure(codec, format);
                Surface surface = codec.createInputSurface();

                int virtualDisplayId;
                try {
                    // Try DisplayManager API first (Android 10+)
                    virtualDisplay = ServiceManager.getDisplayManager()
                            .createVirtualDisplay("scrcpy", videoSize.getWidth(), videoSize.getHeight(), displayId, surface);
                    virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
                    Ln.d("Display: using DisplayManager API, virtualDisplayId=" + virtualDisplayId);
                } catch (Exception displayManagerException) {
                    try {
                        // Fall back to SurfaceControl API
                        display = createDisplay();
                        setDisplaySurface(display, surface, rotation, contentRect, videoRect, displayInfo.getLayerStack());
                        virtualDisplayId = displayId;
                        Ln.d("Display: using SurfaceControl API");
                    } catch (Exception surfaceControlException) {
                        Ln.e("Could not create display using DisplayManager", displayManagerException);
                        Ln.e("Could not create display using SurfaceControl", surfaceControlException);
                        throw new IOException("Could not create display");
                    }
                }

                // Notify VirtualDisplayListener (WebSocketController) about the new display
                if (vdListener != null) {
                    PositionMapper positionMapper;
                    int targetDisplayId;
                    if (virtualDisplay == null || displayId == 0) {
                        // Surface control or main display: send all events to the original display, relative to the device size
                        positionMapper = PositionMapper.create(videoSize, null, displaySize);
                        targetDisplayId = displayId; // Use original display ID (0) for touch injection
                    } else {
                        // The positions are relative to the virtual display, not the original display (so use videoSize!)
                        positionMapper = PositionMapper.create(videoSize, null, videoSize);
                        targetDisplayId = virtualDisplayId; // Use virtual display ID for secondary displays
                    }
                    vdListener.onNewVirtualDisplay(targetDisplayId, positionMapper);
                    Ln.d("Notified VirtualDisplayListener: targetDisplayId=" + targetDisplayId + ", videoSize=" + videoSize + ", displaySize=" + displaySize);
                }

                codec.start();

                // Notify screen info changed (for sending to clients)
                Ln.d("Notifying screen info: " + videoSize.getWidth() + "x" + videoSize.getHeight() + ", rotation=" + rotation);
                connection.notifyScreenInfoChanged(videoSize.getWidth(), videoSize.getHeight(), rotation);

                try {
                    alive = encode(codec);
                    codec.stop();
                } finally {
                    if (display != null) {
                        destroyDisplay(display);
                        display = null;
                    }
                    if (virtualDisplay != null) {
                        virtualDisplay.release();
                        virtualDisplay = null;
                    }
                    codec.release();
                    surface.release();
                }
            } while (alive);
        } finally {
            connection.setStreamInvalidateListener(null);
        }
    }

    private boolean encode(MediaCodec codec) throws IOException {
        boolean eof = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int frameCount = 0;

        Ln.d("Starting encode loop, timeout=" + timeout + ", sendFrameMeta=" + videoSettings.getSendFrameMeta());

        while (!consumeStreamInvalidation() && !eof && connection.hasConnections()) {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, timeout);
            eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try {
                if (consumeStreamInvalidation()) {
                    Ln.d("Stream invalidated, breaking");
                    break;
                }
                if (outputBufferId >= 0) {
                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    int size = codecBuffer.remaining();

                    if (frameCount < 5 || frameCount % 100 == 0) {
                        Ln.d("Sending frame " + frameCount + ", size=" + size + ", flags=" + bufferInfo.flags);
                    }

                    if (videoSettings.getSendFrameMeta()) {
                        writeFrameMeta(bufferInfo, codecBuffer.remaining());
                    }

                    connection.send(codecBuffer);
                    frameCount++;
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Ln.d("Output format changed");
                } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        }

        Ln.d("Encode loop ended, frameCount=" + frameCount + ", eof=" + eof + ", hasConnections=" + connection.hasConnections());
        return !eof && connection.hasConnections();
    }

    private void writeFrameMeta(MediaCodec.BufferInfo bufferInfo, int packetSize) {
        headerBuffer.clear();

        long pts;
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            pts = NO_PTS;
        } else {
            if (ptsOrigin == 0) {
                ptsOrigin = bufferInfo.presentationTimeUs;
            }
            pts = bufferInfo.presentationTimeUs - ptsOrigin;
        }

        headerBuffer.putLong(pts);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        connection.send(headerBuffer);
    }

    private Size computeVideoSize(Size displaySize, Size bounds) {
        int w = displaySize.getWidth();
        int h = displaySize.getHeight();
        int maxW = bounds.getWidth();
        int maxH = bounds.getHeight();

        if (w <= maxW && h <= maxH) {
            return displaySize;
        }

        float ratioW = (float) maxW / w;
        float ratioH = (float) maxH / h;
        float ratio = Math.min(ratioW, ratioH);

        return new Size((int) (w * ratio), (int) (h * ratio));
    }

    public static MediaCodecInfo[] listEncoders() {
        List<MediaCodecInfo> result = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo codecInfo : list.getCodecInfos()) {
            if (codecInfo.isEncoder() && Arrays.asList(codecInfo.getSupportedTypes()).contains(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                result.add(codecInfo);
            }
        }
        return result.toArray(new MediaCodecInfo[0]);
    }

    private static MediaCodec createCodec(String encoderName) throws IOException {
        if (encoderName != null && !encoderName.isEmpty()) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                Ln.w("Encoder '" + encoderName + "' not found, using default");
            }
        }
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        Ln.d("Using encoder: '" + codec.getName() + "'");
        return codec;
    }

    private static MediaFormat createFormat(VideoSettings videoSettings) {
        int bitRate = videoSettings.getBitRate();
        int maxFps = videoSettings.getMaxFps();
        int iFrameInterval = videoSettings.getIFrameInterval();

        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);

        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        return format;
    }

    private static IBinder createDisplay() throws Exception {
        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void configure(MediaCodec codec, MediaFormat format) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, int orientation,
                                          Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }

    @Override
    public void run() {
        synchronized (this) {
            if (encoderThread != null && encoderThread.isAlive()) {
                throw new IllegalStateException(getClass().getName() + " can only be started once.");
            }
            encoderThread = Thread.currentThread();
        }
        try {
            this.streamScreen();
        } catch (IOException e) {
            Ln.e("Failed to stream screen", e);
        }
    }

    public void start(Connection connection) {
        this.connection = connection;
        if (encoderThread != null && encoderThread.isAlive()) {
            throw new IllegalStateException(getClass().getName() + " can only be started once.");
        }
        new Thread(this, "ScreenEncoder").start();
    }
}
