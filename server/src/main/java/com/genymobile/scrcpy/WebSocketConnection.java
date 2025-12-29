package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.WebSocketController;
import com.genymobile.scrcpy.util.Ln;

import org.java_websocket.WebSocket;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * WebSocket connection implementation for ws-scrcpy.
 * Manages multiple WebSocket clients sharing the same video stream.
 */
public class WebSocketConnection extends Connection implements Connection.ScreenInfoListener {

    /**
     * Set of connected WebSocket clients.
     */
    private final Set<WebSocket> clients = new HashSet<>();

    /**
     * Lock object for thread-safe client management.
     */
    private final Object clientsLock = new Object();

    /**
     * Flag indicating if the connection is running.
     */
    private volatile boolean running = false;

    /**
     * Screen encoder for video streaming.
     */
    private ScreenEncoder screenEncoder;

    /**
     * Encoder thread.
     */
    private Thread encoderThread;

    /**
     * WebSocket controller for handling input events.
     */
    private WebSocketController wsController;

    /**
     * Creates a new WebSocket connection.
     *
     * @param options       Connection options
     * @param videoSettings Initial video settings
     */
    public WebSocketConnection(Options options, VideoSettings videoSettings) {
        super(options, videoSettings);
        // Initialize the WebSocket controller for handling input events
        this.wsController = new WebSocketController(videoSettings.getDisplayId());
    }

    /**
     * Adds a WebSocket client to this connection.
     *
     * @param client The WebSocket client to add
     */
    public void addClient(WebSocket client) {
        synchronized (clientsLock) {
            clients.add(client);
            Ln.d("Client added. Total clients: " + clients.size());
        }
    }

    /**
     * Removes a WebSocket client from this connection.
     *
     * @param client The WebSocket client to remove
     * @return true if the client was removed, false if it wasn't present
     */
    public boolean removeClient(WebSocket client) {
        synchronized (clientsLock) {
            boolean removed = clients.remove(client);
            if (removed) {
                Ln.d("Client removed. Total clients: " + clients.size());
            }
            return removed;
        }
    }

    /**
     * Gets the number of connected clients.
     *
     * @return The number of connected clients
     */
    public int getClientCount() {
        synchronized (clientsLock) {
            return clients.size();
        }
    }

    @Override
    public void send(ByteBuffer data) {
        synchronized (clientsLock) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    try {
                        client.send(data.duplicate());
                    } catch (Exception e) {
                        Ln.w("Failed to send data to client: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Sends binary data to all connected clients.
     *
     * @param data The byte array to send
     */
    public void send(byte[] data) {
        send(ByteBuffer.wrap(data));
    }

    @Override
    public boolean hasConnections() {
        synchronized (clientsLock) {
            return !clients.isEmpty();
        }
    }

    @Override
    public void close() throws Exception {
        running = false;

        // Stop the encoder thread
        if (encoderThread != null && encoderThread.isAlive()) {
            encoderThread.interrupt();
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        synchronized (clientsLock) {
            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    try {
                        client.close();
                    } catch (Exception e) {
                        Ln.w("Error closing client: " + e.getMessage());
                    }
                }
            }
            clients.clear();
        }

        // Release display connection
        if (videoSettings != null) {
            WSServer.releaseConnectionForDisplay(videoSettings.getDisplayId());
        }

        Ln.d("WebSocketConnection closed");
    }

    /**
     * Starts the connection and begins streaming.
     *
     * @throws Exception If an error occurs during startup
     */
    public void start() throws Exception {
        if (running) {
            Ln.w("Connection already running");
            return;
        }

        running = true;
        Ln.i("WebSocketConnection started for display " + videoSettings.getDisplayId());

        // Create and start the screen encoder
        screenEncoder = new ScreenEncoder(videoSettings);
        screenEncoder.setConnection(this);

        // Set the WebSocketController as VirtualDisplayListener to receive position mapping
        screenEncoder.setVirtualDisplayListener(wsController);

        // Set screen info listener to receive video size updates (for sending to clients)
        setScreenInfoListener(this);

        encoderThread = new Thread(() -> {
            try {
                screenEncoder.streamScreen();
            } catch (Exception e) {
                Ln.e("Screen encoder error", e);
            } finally {
                running = false;
            }
        }, "ScreenEncoder-" + videoSettings.getDisplayId());
        encoderThread.start();
    }

    /**
     * Handles a control message from a client.
     *
     * @param msg The control message to handle
     */
    public void handleControlMessage(ControlMessage msg) {
        if (wsController != null) {
            boolean handled = wsController.handleMessage(msg);
            if (!handled) {
                Ln.w("Control message not handled: type=" + msg.getType());
            }
        } else {
            Ln.w("No controller available to handle message type: " + msg.getType());
        }
    }

    /**
     * Checks if the connection is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Called when screen info changes (video size determined).
     * Sends updated screen info to all connected clients.
     *
     * @param width    The video width
     * @param height   The video height
     * @param rotation The device rotation
     */
    @Override
    public void onScreenInfoChanged(int width, int height, int rotation) {
        Ln.d("Screen info changed: " + width + "x" + height + ", rotation=" + rotation);
        // The WebSocketController receives position mapping via VirtualDisplayListener
        // No need to manually set video size here
        sendScreenInfo(width, height, rotation);
    }

    /**
     * Sends screen info to all connected clients.
     *
     * @param width    The video width
     * @param height   The video height
     * @param rotation The device rotation
     */
    private void sendScreenInfo(int width, int height, int rotation) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

            // Magic bytes: "scrcpy_initial"
            dos.write("scrcpy_initial".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Device name (64 bytes, padded with zeros)
            String deviceName = android.os.Build.MODEL;
            byte[] nameBytes = deviceName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] namePadded = new byte[64];
            System.arraycopy(nameBytes, 0, namePadded, 0, Math.min(nameBytes.length, 64));
            dos.write(namePadded);

            // Displays count (just 1 for the current display)
            dos.writeInt(1);

            // DisplayInfo for current display
            int displayId = videoSettings.getDisplayId();
            com.genymobile.scrcpy.device.DisplayInfo displayInfo =
                com.genymobile.scrcpy.wrappers.ServiceManager.getDisplayManager().getDisplayInfo(displayId);

            if (displayInfo != null) {
                dos.writeInt(displayInfo.getDisplayId());
                dos.writeInt(displayInfo.getSize().getWidth());
                dos.writeInt(displayInfo.getSize().getHeight());
                dos.writeInt(displayInfo.getRotation());
                dos.writeInt(displayInfo.getLayerStack());
                dos.writeInt(displayInfo.getFlags());
            } else {
                // Fallback if display info not available
                dos.writeInt(displayId);
                dos.writeInt(width);
                dos.writeInt(height);
                dos.writeInt(rotation);
                dos.writeInt(0); // layerStack
                dos.writeInt(0); // flags
            }

            // Connection count
            dos.writeInt(getClientCount());

            // ScreenInfo (25 bytes): contentRect (16) + videoSize (8) + rotation (1)
            // contentRect: left, top, right, bottom
            dos.writeInt(25); // screenInfoBytesCount
            dos.writeInt(0);      // left
            dos.writeInt(0);      // top
            dos.writeInt(width);  // right
            dos.writeInt(height); // bottom
            dos.writeInt(width);  // videoSize.width
            dos.writeInt(height); // videoSize.height
            dos.writeByte(rotation); // deviceRotation

            // VideoSettings
            byte[] videoSettingsBytes = videoSettings.toByteArray();
            dos.writeInt(videoSettingsBytes.length);
            dos.write(videoSettingsBytes);

            // Encoders (0 for update message)
            dos.writeInt(0);

            // Client ID (use 0 for broadcast)
            dos.writeInt(0);

            dos.flush();
            byte[] data = baos.toByteArray();
            send(data);

            Ln.d("Sent screen info update to clients (" + data.length + " bytes)");
        } catch (Exception e) {
            Ln.e("Failed to send screen info", e);
        }
    }
}
