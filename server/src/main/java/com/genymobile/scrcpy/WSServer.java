package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageReader;
import com.genymobile.scrcpy.device.DisplayInfo;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import android.media.MediaCodecInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;

/**
 * WebSocket server for ws-scrcpy.
 * Handles WebSocket connections and routes control messages to appropriate handlers.
 */
public class WSServer extends WebSocketServer {

    /**
     * Path to the PID file for process management.
     */
    public static final String PID_FILE_PATH = "/data/local/tmp/ws_scrcpy.pid";

    /**
     * Maps display IDs to their corresponding WebSocket connections.
     */
    private static final HashMap<Integer, WebSocketConnection> STREAM_BY_DISPLAY_ID = new HashMap<>();

    /**
     * Server options containing configuration parameters.
     */
    private final Options options;

    /**
     * Internal class to manage client socket information.
     */
    public static class SocketInfo {
        /**
         * Set of client IDs currently in use.
         */
        private static final HashSet<Short> INSTANCES_BY_ID = new HashSet<>();

        /**
         * Unique client identifier.
         */
        private final short clientId;

        /**
         * Reference to the WebSocket connection.
         */
        private WebSocketConnection connection;

        /**
         * Creates a new SocketInfo with an auto-assigned client ID.
         */
        public SocketInfo() {
            this.clientId = getNextClientId();
            INSTANCES_BY_ID.add(this.clientId);
        }

        /**
         * Gets the client ID.
         *
         * @return The client ID
         */
        public short getClientId() {
            return clientId;
        }

        /**
         * Gets the WebSocket connection.
         *
         * @return The WebSocket connection
         */
        public WebSocketConnection getConnection() {
            return connection;
        }

        /**
         * Sets the WebSocket connection.
         *
         * @param connection The WebSocket connection to set
         */
        public void setConnection(WebSocketConnection connection) {
            this.connection = connection;
        }

        /**
         * Releases this client ID from the pool.
         */
        public void release() {
            INSTANCES_BY_ID.remove(this.clientId);
        }

        /**
         * Gets the next available client ID.
         *
         * @return The next available client ID
         */
        private static synchronized short getNextClientId() {
            short id = 0;
            while (INSTANCES_BY_ID.contains(id)) {
                id++;
                if (id < 0) {
                    // Overflow protection
                    id = 0;
                    break;
                }
            }
            return id;
        }

        /**
         * Gets the count of active client instances.
         *
         * @return The number of active clients
         */
        public static int getActiveCount() {
            return INSTANCES_BY_ID.size();
        }
    }

    /**
     * Creates a new WebSocket server with the specified options.
     *
     * @param options Server configuration options
     */
    public WSServer(Options options) {
        super(new InetSocketAddress(
                options.getListenOnAllInterfaces() ? "0.0.0.0" : "127.0.0.1",
                options.getPortNumber()
        ));
        this.options = options;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        SocketInfo socketInfo = new SocketInfo();
        conn.setAttachment(socketInfo);

        Ln.i("WebSocket client connected: " + conn.getRemoteSocketAddress()
                + " (clientId=" + socketInfo.getClientId() + ")");

        // Send initial information to the client
        sendInitialInfo(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        SocketInfo socketInfo = conn.getAttachment();
        if (socketInfo != null) {
            Ln.i("WebSocket client disconnected: clientId=" + socketInfo.getClientId()
                    + ", code=" + code + ", reason=" + reason);

            // Clean up the connection
            leave(conn);
            socketInfo.release();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Ln.w("Received unexpected text message from client: " + message);
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer buffer) {
        SocketInfo socketInfo = conn.getAttachment();
        if (socketInfo == null) {
            Ln.w("Received message from unknown client");
            return;
        }

        try {
            processControlMessage(conn, socketInfo, buffer);
        } catch (IOException e) {
            Ln.e("Error processing control message", e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (ex instanceof BindException) {
            Ln.e("Failed to bind WebSocket server: " + ex.getMessage());
            Ln.e("Port " + options.getPortNumber() + " may already be in use");
        } else {
            String clientInfo = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
            Ln.e("WebSocket error for client " + clientInfo, ex);
        }
    }

    @Override
    public void onStart() {
        Ln.i("WebSocket server started on port " + getPort());
        setConnectionLostTimeout(30);

        // Write PID file
        writePidFile();
    }

    /**
     * Processes a binary control message from a client.
     *
     * @param conn       The WebSocket connection
     * @param socketInfo The socket information
     * @param buffer     The message buffer
     * @throws IOException If an I/O error occurs
     */
    private void processControlMessage(WebSocket conn, SocketInfo socketInfo, ByteBuffer buffer)
            throws IOException {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ControlMessageReader reader = new ControlMessageReader(bais);
        ControlMessage msg = reader.read();

        switch (msg.getType()) {
            case ControlMessage.TYPE_CHANGE_STREAM_PARAMETERS:
                handleChangeStreamParameters(conn, socketInfo, msg);
                break;
            case ControlMessage.TYPE_PUSH_FILE:
                handlePushFile(conn, socketInfo, msg);
                break;
            default:
                // Forward other messages to the connection's controller
                WebSocketConnection connection = socketInfo.getConnection();
                if (connection != null) {
                    connection.handleControlMessage(msg);
                } else {
                    Ln.w("No active connection for client " + socketInfo.getClientId()
                            + ", dropping message type " + msg.getType());
                }
                break;
        }
    }

    /**
     * Handles a change stream parameters message.
     *
     * @param conn       The WebSocket connection
     * @param socketInfo The socket information
     * @param msg        The control message
     */
    private void handleChangeStreamParameters(WebSocket conn, SocketInfo socketInfo, ControlMessage msg) {
        VideoSettings newSettings = msg.getVideoSettings();
        if (newSettings == null) {
            Ln.w("Received null video settings");
            return;
        }

        int displayId = newSettings.getDisplayId();
        Ln.d("Change stream parameters requested for display " + displayId);

        // Join or update stream for the specified display
        joinStreamForDisplayId(conn, newSettings, options, displayId, this);
    }

    /**
     * Handles a push file message.
     *
     * @param conn       The WebSocket connection
     * @param socketInfo The socket information
     * @param msg        The control message
     */
    private void handlePushFile(WebSocket conn, SocketInfo socketInfo, ControlMessage msg) {
        byte[] fileData = msg.getFileData();
        if (fileData == null || fileData.length == 0) {
            Ln.w("Received empty file data");
            return;
        }

        Ln.d("Received file push: " + fileData.length + " bytes");

        // File push handling - write to a temporary location
        try {
            File tempFile = new File("/data/local/tmp/ws_scrcpy_upload_" + System.currentTimeMillis());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(fileData);
            }
            Ln.i("File saved to: " + tempFile.getAbsolutePath());
        } catch (IOException e) {
            Ln.e("Failed to save pushed file", e);
        }
    }

    /**
     * Sends initial information to a newly connected client.
     *
     * @param conn The WebSocket connection
     */
    private void sendInitialInfo(WebSocket conn) {
        try {
            SocketInfo socketInfo = conn.getAttachment();
            if (socketInfo == null) {
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // Magic bytes: "scrcpy_initial"
            dos.write("scrcpy_initial".getBytes(StandardCharsets.UTF_8));

            // Device name (64 bytes, padded with zeros)
            String deviceName = android.os.Build.MODEL;
            byte[] nameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
            byte[] namePadded = new byte[64];
            System.arraycopy(nameBytes, 0, namePadded, 0, Math.min(nameBytes.length, 64));
            dos.write(namePadded);

            // Get display IDs
            int[] displayIds = ServiceManager.getDisplayManager().getDisplayIds();
            if (displayIds == null || displayIds.length == 0) {
                displayIds = new int[]{0}; // Default display
            }

            // Displays count
            dos.writeInt(displayIds.length);

            // For each display
            for (int displayId : displayIds) {
                DisplayInfo display = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
                if (display == null) {
                    // Skip if display info not available
                    continue;
                }

                // DisplayInfo: displayId, width, height, rotation, layerStack, flags (24 bytes)
                dos.writeInt(display.getDisplayId());
                dos.writeInt(display.getSize().getWidth());
                dos.writeInt(display.getSize().getHeight());
                dos.writeInt(display.getRotation());
                dos.writeInt(display.getLayerStack());
                dos.writeInt(display.getFlags());

                // Connection count for this display
                WebSocketConnection existingConn = STREAM_BY_DISPLAY_ID.get(display.getDisplayId());
                int connectionCount = existingConn != null ? existingConn.getClientCount() : 0;
                dos.writeInt(connectionCount);

                // Screen info bytes count (0 for now - no active stream yet)
                dos.writeInt(0);

                // Video settings bytes count (0 for now - no active stream yet)
                dos.writeInt(0);
            }

            // Encoders list
            MediaCodecInfo[] encoders = ScreenEncoder.listEncoders();
            dos.writeInt(encoders.length);
            for (MediaCodecInfo encoder : encoders) {
                String encoderName = encoder.getName();
                byte[] encoderNameBytes = encoderName.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(encoderNameBytes.length);
                dos.write(encoderNameBytes);
            }

            // Client ID
            dos.writeInt(socketInfo.getClientId());

            dos.flush();
            byte[] data = baos.toByteArray();
            conn.send(ByteBuffer.wrap(data));

            Ln.d("Sent initial info to client " + socketInfo.getClientId() + " (" + data.length + " bytes)");
        } catch (Exception e) {
            Ln.e("Failed to send initial info", e);
        }
    }

    /**
     * Sends initial information to all connected clients.
     */
    public void sendInitialInfoToAll() {
        for (WebSocket conn : getConnections()) {
            sendInitialInfo(conn);
        }
    }

    /**
     * Cleans up when a client leaves.
     *
     * @param conn The WebSocket connection
     */
    private void leave(WebSocket conn) {
        SocketInfo socketInfo = conn.getAttachment();
        if (socketInfo == null) {
            return;
        }

        WebSocketConnection connection = socketInfo.getConnection();
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                Ln.e("Error closing connection", e);
            }
            socketInfo.setConnection(null);
        }
    }

    /**
     * Gets the WebSocket connection for a specific display ID.
     *
     * @param displayId The display ID
     * @return The WebSocket connection, or null if not found
     */
    public static synchronized WebSocketConnection getConnectionForDisplay(int displayId) {
        return STREAM_BY_DISPLAY_ID.get(displayId);
    }

    /**
     * Releases the WebSocket connection for a specific display ID.
     *
     * @param displayId The display ID
     */
    public static synchronized void releaseConnectionForDisplay(int displayId) {
        WebSocketConnection connection = STREAM_BY_DISPLAY_ID.remove(displayId);
        if (connection != null) {
            Ln.d("Released connection for display " + displayId);
        }
    }

    /**
     * Joins or creates a stream for the specified display ID.
     *
     * @param conn          The WebSocket connection
     * @param videoSettings The video settings
     * @param options       The server options
     * @param displayId     The display ID
     * @param server        The WebSocket server instance
     */
    public static synchronized void joinStreamForDisplayId(
            WebSocket conn,
            VideoSettings videoSettings,
            Options options,
            int displayId,
            WSServer server) {

        SocketInfo socketInfo = conn.getAttachment();
        if (socketInfo == null) {
            Ln.w("Cannot join stream: no socket info");
            return;
        }

        WebSocketConnection existingConnection = STREAM_BY_DISPLAY_ID.get(displayId);

        if (existingConnection != null) {
            // Join existing stream
            Ln.d("Joining existing stream for display " + displayId);
            existingConnection.addClient(conn);
            socketInfo.setConnection(existingConnection);

            // Update video settings if changed
            existingConnection.setVideoSettings(videoSettings);
        } else {
            // Create new stream
            Ln.d("Creating new stream for display " + displayId);
            WebSocketConnection newConnection = new WebSocketConnection(options, videoSettings);
            newConnection.addClient(conn);
            socketInfo.setConnection(newConnection);
            STREAM_BY_DISPLAY_ID.put(displayId, newConnection);

            // Start the stream
            try {
                newConnection.start();
            } catch (Exception e) {
                Ln.e("Failed to start stream for display " + displayId, e);
                STREAM_BY_DISPLAY_ID.remove(displayId);
            }
        }
    }

    /**
     * Writes the current process PID to a file.
     */
    public static void writePidFile() {
        try {
            int pid = android.os.Process.myPid();
            File pidFile = new File(PID_FILE_PATH);
            try (FileWriter writer = new FileWriter(pidFile)) {
                writer.write(String.valueOf(pid));
            }
            Ln.d("PID file written: " + pid);
        } catch (IOException e) {
            Ln.w("Failed to write PID file", e);
        }
    }

    /**
     * Deletes the PID file.
     */
    public static void unlinkPidFile() {
        File pidFile = new File(PID_FILE_PATH);
        if (pidFile.exists()) {
            if (pidFile.delete()) {
                Ln.d("PID file deleted");
            } else {
                Ln.w("Failed to delete PID file");
            }
        }
    }

    /**
     * Gets the server options.
     *
     * @return The server options
     */
    public Options getOptions() {
        return options;
    }
}
