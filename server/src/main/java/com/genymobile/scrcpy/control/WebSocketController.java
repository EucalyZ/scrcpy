package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Point;
import com.genymobile.scrcpy.device.Position;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.VirtualDisplayListener;
import com.genymobile.scrcpy.wrappers.InputManager;

import android.os.Build;
import android.os.SystemClock;
import android.util.Pair;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Controller for WebSocket mode.
 * Handles control messages directly without requiring a ControlChannel.
 * Implements VirtualDisplayListener to receive position mapping from ScreenEncoder.
 */
public class WebSocketController implements VirtualDisplayListener {

    private static final class DisplayData {
        private final int virtualDisplayId;
        private final PositionMapper positionMapper;

        private DisplayData(int virtualDisplayId, PositionMapper positionMapper) {
            this.virtualDisplayId = virtualDisplayId;
            this.positionMapper = positionMapper;
        }
    }

    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int POINTER_ID_MOUSE = -1;

    private final int displayId;
    private final boolean supportsInputEvents;

    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private final AtomicReference<DisplayData> displayData = new AtomicReference<>();

    private long lastTouchDown;
    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];

    public WebSocketController(int displayId) {
        this.displayId = displayId;
        this.supportsInputEvents = Device.supportsInputEvents(displayId);
        if (!supportsInputEvents) {
            Ln.w("Input events are not supported for secondary displays before Android 10");
        }
        initPointers();
    }

    private void initPointers() {
        for (int i = 0; i < PointersState.MAX_POINTERS; ++i) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.orientation = 0;
            coords.size = 0;

            pointerProperties[i] = props;
            pointerCoords[i] = coords;
        }
    }

    @Override
    public void onNewVirtualDisplay(int virtualDisplayId, PositionMapper positionMapper) {
        DisplayData data = new DisplayData(virtualDisplayId, positionMapper);
        DisplayData old = this.displayData.getAndSet(data);
        if (old == null) {
            Ln.d("WebSocketController received first virtual display: " + virtualDisplayId);
        } else {
            Ln.d("WebSocketController updated virtual display: " + virtualDisplayId);
        }
    }

    /**
     * Gets the display ID to use for actions (key events, etc.)
     */
    private int getActionDisplayId() {
        if (displayId != Device.DISPLAY_ID_NONE) {
            return displayId;
        }
        DisplayData data = displayData.get();
        if (data != null) {
            return data.virtualDisplayId;
        }
        return 0;
    }

    /**
     * Handles a control message.
     *
     * @param msg The control message to handle
     * @return true if the message was handled successfully
     */
    public boolean handleMessage(ControlMessage msg) {
        Ln.d("WebSocketController handling message type: " + msg.getType());
        switch (msg.getType()) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                if (supportsInputEvents) {
                    return injectKeycode(msg.getAction(), msg.getKeycode(), msg.getRepeat(), msg.getMetaState());
                }
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                if (supportsInputEvents) {
                    return injectText(msg.getText()) > 0;
                }
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                if (supportsInputEvents) {
                    Position pos = msg.getPosition();
                    Ln.d("Touch event: action=" + msg.getAction() + ", pos=" + pos.getPoint()
                         + ", screenSize=" + pos.getScreenSize() + ", displayData=" + (displayData.get() != null));
                    return injectTouch(msg.getAction(), msg.getPointerId(), msg.getPosition(),
                                       msg.getPressure(), msg.getActionButton(), msg.getButtons());
                } else {
                    Ln.w("supportsInputEvents is false, ignoring touch");
                }
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                if (supportsInputEvents) {
                    return injectScroll(msg.getPosition(), msg.getHScroll(), msg.getVScroll(), msg.getButtons());
                }
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                if (supportsInputEvents) {
                    return pressBackOrTurnScreenOn(msg.getAction());
                }
                break;
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
                Device.expandNotificationPanel();
                return true;
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
                Device.expandSettingsPanel();
                return true;
            case ControlMessage.TYPE_COLLAPSE_PANELS:
                Device.collapsePanels();
                return true;
            case ControlMessage.TYPE_ROTATE_DEVICE:
                Device.rotateDevice(getActionDisplayId());
                return true;
            case ControlMessage.TYPE_SET_DISPLAY_POWER:
                if (supportsInputEvents) {
                    return Device.setDisplayPower(getActionDisplayId(), msg.getOn());
                }
                break;
            default:
                Ln.d("Unhandled message type in WebSocketController: " + msg.getType());
                return false;
        }
        return false;
    }

    private boolean injectKeycode(int action, int keycode, int repeat, int metaState) {
        return injectKeyEvent(action, keycode, repeat, metaState, Device.INJECT_MODE_ASYNC);
    }

    private boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int injectMode) {
        return Device.injectKeyEvent(action, keyCode, repeat, metaState, getActionDisplayId(), injectMode);
    }

    private boolean injectChar(char c) {
        String decomposed = KeyComposition.decompose(c);
        char[] chars = decomposed != null ? decomposed.toCharArray() : new char[]{c};
        KeyEvent[] events = charMap.getEvents(chars);
        if (events == null) {
            return false;
        }

        int actionDisplayId = getActionDisplayId();
        for (KeyEvent event : events) {
            if (!Device.injectEvent(event, actionDisplayId, Device.INJECT_MODE_ASYNC)) {
                return false;
            }
        }
        return true;
    }

    private int injectText(String text) {
        int successCount = 0;
        for (char c : text.toCharArray()) {
            if (!injectChar(c)) {
                Ln.w("Could not inject char u+" + String.format("%04x", (int) c));
                continue;
            }
            successCount++;
        }
        return successCount;
    }

    private Pair<Point, Integer> getEventPointAndDisplayId(Position position) {
        // it hides the field on purpose, to read it with atomic access
        @SuppressWarnings("checkstyle:HiddenField")
        DisplayData displayData = this.displayData.get();
        // In scrcpy, displayData should never be null (a touch event can only be generated from the client when a video frame is present).
        // However, it is possible to send events without video playback when using scrcpy-server alone (except for virtual displays).
        assert displayData != null || displayId != Device.DISPLAY_ID_NONE : "Cannot receive a positional event without a display";

        Point point;
        int targetDisplayId;
        if (displayData != null) {
            Size eventSize = position.getScreenSize();
            Size currentSize = displayData.positionMapper.getVideoSize();
            Ln.d("getEventPointAndDisplayId: eventSize=" + eventSize + ", currentSize=" + currentSize);
            point = displayData.positionMapper.map(position);
            if (point == null) {
                Ln.w("PositionMapper.map returned null: eventSize=" + eventSize + ", currentSize=" + currentSize);
                return null;
            }
            targetDisplayId = displayData.virtualDisplayId;
            Ln.d("Mapped point: " + position.getPoint() + " -> " + point + ", targetDisplayId=" + targetDisplayId);
        } else {
            // No display, use the raw coordinates
            point = position.getPoint();
            targetDisplayId = displayId;
            Ln.d("No displayData, using raw point: " + point + ", targetDisplayId=" + targetDisplayId);
        }

        return Pair.create(point, targetDisplayId);
    }

    private boolean injectTouch(int action, long pointerId, Position position, float pressure, int actionButton, int buttons) {
        long now = SystemClock.uptimeMillis();

        Pair<Point, Integer> pair = getEventPointAndDisplayId(position);
        if (pair == null) {
            Ln.w("injectTouch: getEventPointAndDisplayId returned null");
            return false;
        }

        Point point = pair.first;
        int targetDisplayId = pair.second;
        Ln.d("injectTouch: action=" + action + ", point=" + point + ", targetDisplayId=" + targetDisplayId);

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Ln.w("Too many pointers for touch event");
            return false;
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);

        int source;
        boolean activeSecondaryButtons = ((actionButton | buttons) & ~MotionEvent.BUTTON_PRIMARY) != 0;
        if (pointerId == POINTER_ID_MOUSE && (action == MotionEvent.ACTION_HOVER_MOVE || activeSecondaryButtons)) {
            // real mouse event, or event incompatible with a finger
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            source = InputDevice.SOURCE_MOUSE;
            pointer.setUp(buttons == 0);
        } else {
            // POINTER_ID_GENERIC_FINGER, POINTER_ID_VIRTUAL_FINGER or real touch from device
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_FINGER;
            source = InputDevice.SOURCE_TOUCHSCREEN;
            // Buttons must not be set for touch events
            buttons = 0;
            pointer.setUp(action == MotionEvent.ACTION_UP);
        }

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }

        /* If the input device is a mouse (on API >= 23):
         *   - the first button pressed must first generate ACTION_DOWN;
         *   - all button pressed (including the first one) must generate ACTION_BUTTON_PRESS;
         *   - all button released (including the last one) must generate ACTION_BUTTON_RELEASE;
         *   - the last button released must in addition generate ACTION_UP.
         *
         * Otherwise, Chrome does not work properly: <https://github.com/Genymobile/scrcpy/issues/3635>
         */
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0 && source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (actionButton == buttons) {
                    // First button pressed: ACTION_DOWN
                    MotionEvent downEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_DOWN, pointerCount, pointerProperties,
                            pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                    if (!Device.injectEvent(downEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                        return false;
                    }
                }

                // Any button pressed: ACTION_BUTTON_PRESS
                MotionEvent pressEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_PRESS, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                if (!InputManager.setActionButton(pressEvent, actionButton)) {
                    return false;
                }
                if (!Device.injectEvent(pressEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                    return false;
                }

                return true;
            }

            if (action == MotionEvent.ACTION_UP) {
                // Any button released: ACTION_BUTTON_RELEASE
                MotionEvent releaseEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_RELEASE, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                if (!InputManager.setActionButton(releaseEvent, actionButton)) {
                    return false;
                }
                if (!Device.injectEvent(releaseEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                    return false;
                }

                if (buttons == 0) {
                    // Last button released: ACTION_UP
                    MotionEvent upEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_UP, pointerCount, pointerProperties,
                            pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                    if (!Device.injectEvent(upEvent, targetDisplayId, Device.INJECT_MODE_ASYNC)) {
                        return false;
                    }
                }

                return true;
            }
        }

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, source, 0);
        boolean result = Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);
        Ln.d("Device.injectEvent result=" + result + ", action=" + action + ", targetDisplayId=" + targetDisplayId);
        return result;
    }

    private boolean injectScroll(Position position, float hScroll, float vScroll, int buttons) {
        long now = SystemClock.uptimeMillis();

        Pair<Point, Integer> pair = getEventPointAndDisplayId(position);
        if (pair == null) {
            return false;
        }

        Point point = pair.first;
        int targetDisplayId = pair.second;

        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;

        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = point.getX();
        coords.y = point.getY();
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1, pointerProperties, pointerCoords, 0, buttons, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0);
        return Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);
    }

    private boolean pressBackOrTurnScreenOn(int action) {
        int actionDisplayId = getActionDisplayId();
        if (actionDisplayId == Device.DISPLAY_ID_NONE || Device.isScreenOn(actionDisplayId)) {
            return injectKeyEvent(action, KeyEvent.KEYCODE_BACK, 0, 0, Device.INJECT_MODE_ASYNC);
        }

        // Screen is off
        // Only press POWER on ACTION_DOWN
        if (action != KeyEvent.ACTION_DOWN) {
            // do nothing,
            return true;
        }

        return pressReleaseKeycode(KeyEvent.KEYCODE_POWER, Device.INJECT_MODE_ASYNC);
    }

    private boolean pressReleaseKeycode(int keyCode, int injectMode) {
        return Device.pressReleaseKeycode(keyCode, getActionDisplayId(), injectMode);
    }
}
