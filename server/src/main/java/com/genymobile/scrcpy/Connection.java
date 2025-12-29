package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.video.SurfaceCapture;

import java.nio.ByteBuffer;

/**
 * Abstract base class for WebSocket connections.
 * Provides common functionality for managing video settings, controller, and stream invalidation.
 *
 * This class is designed to work with scrcpy v3.3.4 architecture where Controller
 * requires ControlChannel for initialization. In WebSocket mode, we handle controller
 * initialization differently in subclasses.
 */
public abstract class Connection {

    /**
     * Listener interface for stream invalidation events.
     * Called when the video stream needs to be reset (e.g., due to rotation or settings change).
     */
    public interface StreamInvalidateListener {
        void onStreamInvalidate();
    }

    /**
     * Listener interface for screen info changes.
     * Called when the video size is determined after encoding starts.
     */
    public interface ScreenInfoListener {
        void onScreenInfoChanged(int width, int height, int rotation);
    }

    /**
     * Device name field length in bytes for protocol compatibility.
     */
    protected static final int DEVICE_NAME_FIELD_LENGTH = 64;

    /**
     * Connection options containing display settings, codec preferences, etc.
     */
    protected final Options options;

    /**
     * Video settings for encoding configuration.
     */
    protected VideoSettings videoSettings;

    /**
     * Controller for handling input events.
     * Note: In v3.3.4, Controller requires ControlChannel for initialization.
     * For WebSocket mode, this is initialized differently in subclasses.
     */
    protected Controller controller;

    /**
     * Listener for stream invalidation events.
     */
    protected StreamInvalidateListener streamInvalidateListener;

    /**
     * Listener for screen info changes.
     */
    protected ScreenInfoListener screenInfoListener;

    /**
     * Creates a new Connection with the specified options and video settings.
     *
     * @param options       Connection options
     * @param videoSettings Video encoding settings
     */
    public Connection(Options options, VideoSettings videoSettings) {
        this.options = options;
        this.videoSettings = videoSettings;
        // Note: Controller is not initialized here because v3.3.4 Controller requires
        // ControlChannel which is not available in WebSocket mode.
        // Subclasses should handle controller initialization appropriately.
        this.controller = null;
    }

    /**
     * Sends data to connected clients.
     *
     * @param data The data buffer to send
     */
    public abstract void send(ByteBuffer data);

    /**
     * Checks if there are any active connections.
     *
     * @return true if there are active connections, false otherwise
     */
    public abstract boolean hasConnections();

    /**
     * Closes the connection and releases resources.
     *
     * @throws Exception if an error occurs during close
     */
    public abstract void close() throws Exception;

    /**
     * Updates video settings if they have changed.
     *
     * @param newVideoSettings The new video settings to apply
     * @return true if settings were changed, false if they were the same
     */
    public boolean setVideoSettings(VideoSettings newVideoSettings) {
        if (videoSettings == null) {
            videoSettings = newVideoSettings;
            notifyStreamInvalidate();
            return true;
        }

        if (!videoSettings.equals(newVideoSettings)) {
            videoSettings.merge(newVideoSettings);
            notifyStreamInvalidate();
            return true;
        }
        return false;
    }

    /**
     * Gets the current video settings.
     *
     * @return The current video settings
     */
    public VideoSettings getVideoSettings() {
        return videoSettings;
    }

    /**
     * Gets the connection options.
     *
     * @return The connection options
     */
    public Options getOptions() {
        return options;
    }

    /**
     * Gets the controller instance.
     *
     * @return The controller, or null if not initialized
     */
    public Controller getController() {
        return controller;
    }

    /**
     * Sets the stream invalidation listener.
     *
     * @param listener The listener to set
     */
    public void setStreamInvalidateListener(StreamInvalidateListener listener) {
        this.streamInvalidateListener = listener;
    }

    /**
     * Sets the screen info listener.
     *
     * @param listener The listener to set
     */
    public void setScreenInfoListener(ScreenInfoListener listener) {
        this.screenInfoListener = listener;
    }

    /**
     * Notifies the screen info listener of a screen info change.
     *
     * @param width    The video width
     * @param height   The video height
     * @param rotation The device rotation
     */
    public void notifyScreenInfoChanged(int width, int height, int rotation) {
        if (screenInfoListener != null) {
            screenInfoListener.onScreenInfoChanged(width, height, rotation);
        }
    }

    /**
     * Called when device rotation changes.
     * Notifies the stream invalidation listener to reset the video stream.
     *
     * @param rotation The new rotation value (0, 1, 2, or 3 representing 0, 90, 180, 270 degrees)
     */
    public void onRotationChanged(int rotation) {
        notifyStreamInvalidate();
    }

    /**
     * Notifies the stream invalidation listener if one is registered.
     */
    protected void notifyStreamInvalidate() {
        if (streamInvalidateListener != null) {
            streamInvalidateListener.onStreamInvalidate();
        }
    }
}
