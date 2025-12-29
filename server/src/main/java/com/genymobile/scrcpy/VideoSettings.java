package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Size;

import android.graphics.Rect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Video settings for WebSocket dynamic configuration.
 * Supports binary serialization for network transmission.
 */
public final class VideoSettings {

    // Default values
    private static final int DEFAULT_BIT_RATE = 8_000_000;
    private static final int DEFAULT_MAX_FPS = 60;
    private static final int DEFAULT_I_FRAME_INTERVAL = 10;

    private int bitRate;
    private int maxFps;
    private int iFrameInterval;
    private Size bounds;
    private Rect crop;
    private boolean sendFrameMeta;
    private int lockedVideoOrientation;
    private int displayId;
    private String codecOptions;
    private String encoderName;

    /**
     * Creates VideoSettings with default values.
     */
    public VideoSettings() {
        this.bitRate = DEFAULT_BIT_RATE;
        this.maxFps = DEFAULT_MAX_FPS;
        this.iFrameInterval = DEFAULT_I_FRAME_INTERVAL;
        this.bounds = null;
        this.crop = null;
        this.sendFrameMeta = true;
        this.lockedVideoOrientation = -1;
        this.displayId = 0;
        this.codecOptions = null;
        this.encoderName = null;
    }

    /**
     * Creates VideoSettings with specified values.
     */
    public VideoSettings(int bitRate, int maxFps, int iFrameInterval, Size bounds, Rect crop,
                         boolean sendFrameMeta, int lockedVideoOrientation, int displayId,
                         String codecOptions, String encoderName) {
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.iFrameInterval = iFrameInterval;
        this.bounds = bounds;
        this.crop = crop;
        this.sendFrameMeta = sendFrameMeta;
        this.lockedVideoOrientation = lockedVideoOrientation;
        this.displayId = displayId;
        this.codecOptions = codecOptions;
        this.encoderName = encoderName;
    }

    /**
     * Creates VideoSettings with raw values (for parsing from network).
     */
    public VideoSettings(int bitRate, int maxFps, int iFrameInterval,
                         int boundsWidth, int boundsHeight,
                         int cropLeft, int cropTop, int cropRight, int cropBottom,
                         boolean sendFrameMeta, int lockedVideoOrientation, int displayId,
                         String codecOptions, String encoderName) {
        this.bitRate = bitRate > 0 ? bitRate : DEFAULT_BIT_RATE;
        this.maxFps = maxFps > 0 ? maxFps : DEFAULT_MAX_FPS;
        this.iFrameInterval = iFrameInterval > 0 ? iFrameInterval : DEFAULT_I_FRAME_INTERVAL;
        this.bounds = (boundsWidth > 0 && boundsHeight > 0) ? new Size(boundsWidth, boundsHeight) : null;
        this.crop = (cropRight > cropLeft && cropBottom > cropTop) ? new Rect(cropLeft, cropTop, cropRight, cropBottom) : null;
        this.sendFrameMeta = sendFrameMeta;
        this.lockedVideoOrientation = lockedVideoOrientation;
        this.displayId = displayId;
        this.codecOptions = codecOptions;
        this.encoderName = encoderName;
    }

    // Getters
    public int getBitRate() {
        return bitRate;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public int getIFrameInterval() {
        return iFrameInterval;
    }

    public Size getBounds() {
        return bounds;
    }

    public Rect getCrop() {
        return crop;
    }

    public boolean getSendFrameMeta() {
        return sendFrameMeta;
    }

    public int getLockedVideoOrientation() {
        return lockedVideoOrientation;
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getCodecOptions() {
        return codecOptions;
    }

    public String getEncoderName() {
        return encoderName;
    }

    // Setters
    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = maxFps;
    }

    public void setIFrameInterval(int iFrameInterval) {
        this.iFrameInterval = iFrameInterval;
    }

    /**
     * Sets bounds with alignment to 16-pixel boundary.
     * Width and height are aligned down to multiples of 16.
     */
    public void setBounds(Size bounds) {
        if (bounds != null) {
            int alignedWidth = bounds.getWidth() & ~15;
            int alignedHeight = bounds.getHeight() & ~15;
            this.bounds = new Size(alignedWidth, alignedHeight);
        } else {
            this.bounds = null;
        }
    }

    /**
     * Sets bounds directly without alignment (for internal use).
     */
    public void setBoundsRaw(Size bounds) {
        this.bounds = bounds;
    }

    public void setCrop(Rect crop) {
        this.crop = crop;
    }

    public void setSendFrameMeta(boolean sendFrameMeta) {
        this.sendFrameMeta = sendFrameMeta;
    }

    public void setLockedVideoOrientation(int lockedVideoOrientation) {
        this.lockedVideoOrientation = lockedVideoOrientation;
    }

    public void setDisplayId(int displayId) {
        this.displayId = displayId;
    }

    public void setCodecOptions(String codecOptions) {
        this.codecOptions = codecOptions;
    }

    public void setEncoderName(String encoderName) {
        this.encoderName = encoderName;
    }

    /**
     * Serializes this VideoSettings to a byte array.
     *
     * Binary format (35 bytes base + variable length):
     * - 4 bytes: bitRate (int)
     * - 4 bytes: maxFps (int)
     * - 1 byte: iFrameInterval
     * - 2 bytes: width (short)
     * - 2 bytes: height (short)
     * - 8 bytes: crop (4 x short: left, top, right, bottom)
     * - 1 byte: sendFrameMeta (boolean)
     * - 1 byte: lockedVideoOrientation
     * - 4 bytes: displayId (int)
     * - 4 bytes: codecOptionsLength (int)
     * - N bytes: codecOptions string (UTF-8)
     * - 4 bytes: encoderNameLength (int)
     * - N bytes: encoderName string (UTF-8)
     */
    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        try {
            // bitRate (4 bytes)
            dos.writeInt(bitRate);

            // maxFps (4 bytes)
            dos.writeInt(maxFps);

            // iFrameInterval (1 byte)
            dos.writeByte(iFrameInterval);

            // bounds: width and height (2 + 2 bytes)
            if (bounds != null) {
                dos.writeShort(bounds.getWidth());
                dos.writeShort(bounds.getHeight());
            } else {
                dos.writeShort(0);
                dos.writeShort(0);
            }

            // crop: left, top, right, bottom (4 x 2 bytes = 8 bytes)
            if (crop != null) {
                dos.writeShort(crop.left);
                dos.writeShort(crop.top);
                dos.writeShort(crop.right);
                dos.writeShort(crop.bottom);
            } else {
                dos.writeShort(0);
                dos.writeShort(0);
                dos.writeShort(0);
                dos.writeShort(0);
            }

            // sendFrameMeta (1 byte)
            dos.writeByte(sendFrameMeta ? 1 : 0);

            // lockedVideoOrientation (1 byte)
            dos.writeByte(lockedVideoOrientation);

            // displayId (4 bytes)
            dos.writeInt(displayId);

            // codecOptions (4 bytes length + N bytes string)
            writeString(dos, codecOptions);

            // encoderName (4 bytes length + N bytes string)
            writeString(dos, encoderName);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize VideoSettings", e);
        }
    }

    /**
     * Deserializes a VideoSettings from a byte array.
     */
    public static VideoSettings fromByteArray(byte[] data) {
        VideoSettings settings = new VideoSettings();
        settings.mergeFromByteArray(data);
        return settings;
    }

    /**
     * Merges settings from a byte array into this instance.
     * Only non-default values from the byte array will override current values.
     */
    public void mergeFromByteArray(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        try {
            // bitRate (4 bytes)
            int newBitRate = dis.readInt();
            if (newBitRate > 0) {
                this.bitRate = newBitRate;
            }

            // maxFps (4 bytes)
            int newMaxFps = dis.readInt();
            if (newMaxFps > 0) {
                this.maxFps = newMaxFps;
            }

            // iFrameInterval (1 byte)
            int newIFrameInterval = dis.readByte() & 0xFF;
            if (newIFrameInterval > 0) {
                this.iFrameInterval = newIFrameInterval;
            }

            // bounds: width and height (2 + 2 bytes)
            int width = dis.readShort() & 0xFFFF;
            int height = dis.readShort() & 0xFFFF;
            if (width > 0 && height > 0) {
                this.bounds = new Size(width, height);
            }

            // crop: left, top, right, bottom (4 x 2 bytes = 8 bytes)
            int left = dis.readShort() & 0xFFFF;
            int top = dis.readShort() & 0xFFFF;
            int right = dis.readShort() & 0xFFFF;
            int bottom = dis.readShort() & 0xFFFF;
            if (right > left && bottom > top) {
                this.crop = new Rect(left, top, right, bottom);
            }

            // sendFrameMeta (1 byte)
            this.sendFrameMeta = dis.readByte() != 0;

            // lockedVideoOrientation (1 byte)
            this.lockedVideoOrientation = dis.readByte();

            // displayId (4 bytes)
            this.displayId = dis.readInt();

            // codecOptions (4 bytes length + N bytes string)
            String newCodecOptions = readString(dis);
            if (newCodecOptions != null && !newCodecOptions.isEmpty()) {
                this.codecOptions = newCodecOptions;
            }

            // encoderName (4 bytes length + N bytes string)
            String newEncoderName = readString(dis);
            if (newEncoderName != null && !newEncoderName.isEmpty()) {
                this.encoderName = newEncoderName;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize VideoSettings", e);
        }
    }

    /**
     * Merges another VideoSettings into this instance.
     * Non-null and non-default values from other will override current values.
     */
    public void merge(VideoSettings other) {
        if (other == null) {
            return;
        }

        if (other.bitRate > 0) {
            this.bitRate = other.bitRate;
        }
        if (other.maxFps > 0) {
            this.maxFps = other.maxFps;
        }
        if (other.iFrameInterval > 0) {
            this.iFrameInterval = other.iFrameInterval;
        }
        if (other.bounds != null) {
            this.bounds = other.bounds;
        }
        if (other.crop != null) {
            this.crop = other.crop;
        }
        this.sendFrameMeta = other.sendFrameMeta;
        this.lockedVideoOrientation = other.lockedVideoOrientation;
        this.displayId = other.displayId;
        if (other.codecOptions != null && !other.codecOptions.isEmpty()) {
            this.codecOptions = other.codecOptions;
        }
        if (other.encoderName != null && !other.encoderName.isEmpty()) {
            this.encoderName = other.encoderName;
        }
    }

    private static void writeString(DataOutputStream dos, String str) throws IOException {
        if (str == null || str.isEmpty()) {
            dos.writeInt(0);
        } else {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }
    }

    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length <= 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VideoSettings that = (VideoSettings) o;
        return bitRate == that.bitRate
                && maxFps == that.maxFps
                && iFrameInterval == that.iFrameInterval
                && sendFrameMeta == that.sendFrameMeta
                && lockedVideoOrientation == that.lockedVideoOrientation
                && displayId == that.displayId
                && Objects.equals(bounds, that.bounds)
                && Objects.equals(crop, that.crop)
                && Objects.equals(codecOptions, that.codecOptions)
                && Objects.equals(encoderName, that.encoderName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bitRate, maxFps, iFrameInterval, bounds, crop,
                sendFrameMeta, lockedVideoOrientation, displayId, codecOptions, encoderName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VideoSettings{");
        sb.append("bitRate=").append(bitRate);
        sb.append(", maxFps=").append(maxFps);
        sb.append(", iFrameInterval=").append(iFrameInterval);
        if (bounds != null) {
            sb.append(", bounds=").append(bounds);
        }
        if (crop != null) {
            sb.append(", crop=").append(crop.toShortString());
        }
        sb.append(", sendFrameMeta=").append(sendFrameMeta);
        sb.append(", lockedVideoOrientation=").append(lockedVideoOrientation);
        sb.append(", displayId=").append(displayId);
        if (codecOptions != null) {
            sb.append(", codecOptions='").append(codecOptions).append('\'');
        }
        if (encoderName != null) {
            sb.append(", encoderName='").append(encoderName).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
