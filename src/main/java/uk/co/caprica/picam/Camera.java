/*
 * This file is part of picam.
 *
 * picam is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * picam is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with picam.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2016-2019 Caprica Software Limited.
 */

package uk.co.caprica.picam;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_T;
import uk.co.caprica.picam.bindings.internal.MMAL_COMPONENT_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PARAMETER_CAMERA_CONFIG_T;
import uk.co.caprica.picam.bindings.internal.MMAL_POOL_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PORT_T;
import uk.co.caprica.picam.bindings.internal.MMAL_VIDEO_FORMAT_T;
import uk.co.caprica.picam.handlers.PictureCaptureHandler;
import uk.co.caprica.picam.handlers.VideoCaptureHandler;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static uk.co.caprica.picam.AlignUtils.alignUp;
import static uk.co.caprica.picam.CameraParameterUtils.setAutomaticWhiteBalanceGains;
import static uk.co.caprica.picam.CameraParameterUtils.setAutomaticWhiteBalanceMode;
import static uk.co.caprica.picam.CameraParameterUtils.setBrightness;
import static uk.co.caprica.picam.CameraParameterUtils.setColourEffect;
import static uk.co.caprica.picam.CameraParameterUtils.setContrast;
import static uk.co.caprica.picam.CameraParameterUtils.setCrop;
import static uk.co.caprica.picam.CameraParameterUtils.setDynamicRangeCompressionStrength;
import static uk.co.caprica.picam.CameraParameterUtils.setExposureCompensation;
import static uk.co.caprica.picam.CameraParameterUtils.setExposureMeteringMode;
import static uk.co.caprica.picam.CameraParameterUtils.setExposureMode;
import static uk.co.caprica.picam.CameraParameterUtils.setFpsRange;
import static uk.co.caprica.picam.CameraParameterUtils.setImageEffect;
import static uk.co.caprica.picam.CameraParameterUtils.setIso;
import static uk.co.caprica.picam.CameraParameterUtils.setMirror;
import static uk.co.caprica.picam.CameraParameterUtils.setRotation;
import static uk.co.caprica.picam.CameraParameterUtils.setSaturation;
import static uk.co.caprica.picam.CameraParameterUtils.setSharpness;
import static uk.co.caprica.picam.CameraParameterUtils.setShutterSpeed;
import static uk.co.caprica.picam.CameraParameterUtils.setStereoscopicMode;
import static uk.co.caprica.picam.CameraParameterUtils.setVideoStabilisation;
import static uk.co.caprica.picam.MmalParameterUtils.mmal_port_parameter_set_boolean;
import static uk.co.caprica.picam.MmalParameterUtils.mmal_port_parameter_set_int32;
import static uk.co.caprica.picam.MmalParameterUtils.mmal_port_parameter_set_uint32;
import static uk.co.caprica.picam.MmalUtils.connectPorts;
import static uk.co.caprica.picam.MmalUtils.createComponent;
import static uk.co.caprica.picam.MmalUtils.destroyComponent;
import static uk.co.caprica.picam.MmalUtils.disableComponent;
import static uk.co.caprica.picam.MmalUtils.disablePort;
import static uk.co.caprica.picam.MmalUtils.enableComponent;
import static uk.co.caprica.picam.MmalUtils.getPort;
import static uk.co.caprica.picam.bindings.LibMmal.*;
import static uk.co.caprica.picam.bindings.LibMmalUtil.mmal_port_pool_create;
import static uk.co.caprica.picam.bindings.LibMmalUtil.mmal_port_pool_destroy;
import static uk.co.caprica.picam.bindings.MmalParameters.MMAL_PARAMETER_CAMERA_CUSTOM_SENSOR_CONFIG;
import static uk.co.caprica.picam.bindings.MmalParameters.MMAL_PARAMETER_CAMERA_NUM;
import static uk.co.caprica.picam.bindings.MmalParameters.MMAL_PARAMETER_CAPTURE;
import static uk.co.caprica.picam.bindings.MmalParameters.MMAL_PARAMETER_JPEG_Q_FACTOR;
import static uk.co.caprica.picam.bindings.internal.MMAL_PARAMETER_CAMERA_CONFIG_TIMESTAMP_MODE_T.MMAL_PARAM_TIMESTAMP_MODE_RESET_STC;
import static uk.co.caprica.picam.bindings.internal.MMAL_STATUS_T.MMAL_SUCCESS;
import static uk.co.caprica.picam.enums.Encoding.*;

/**
 * A camera component.
 * <p>
 * Note that a delay (set via {@link CameraConfiguration}) may be necessary to give the sensor time to "settle",
 * otherwise the picture may be compromised (e.g. bad colouration).
 */
public final class Camera implements AutoCloseable {

//    private static final String MMAL_COMPONENT_DEFAULT_IMAGE_ENCODER = "vc.ril.image_encode";
    private static final String MMAL_COMPONENT_DEFAULT_IMAGE_ENCODER = "vc.ril.image_encode";

    private static final String MMAL_COMPONENT_DEFAULT_VIDEO_RENDERER = "vc.ril.video_render";

    private static final String MMAL_COMPONENT_DEFAULT_CAMERA = "vc.ril.camera";

    private static final int MMAL_CAMERA_PREVIEW_PORT = 0;
    private static final int MMAL_CAMERA_VIDEO_PORT = 1;
    private static final int MMAL_CAMERA_CAPTURE_PORT = 2;

    private static final int STILLS_FRAME_RATE_NUM = 5;
    private static final int STILLS_FRAME_RATE_DEN = 1;

    // Frames rates of 0 implies variable, but denominator needs to be 1 to prevent div by 0
    private static final int PREVIEW_FRAME_RATE_NUM = 0;
    private static final int PREVIEW_FRAME_RATE_DEN = 1;

    private static final int VIDEO_OUTPUT_BUFFERS_NUM = 3;

    private static final int ALIGN_WIDTH = 32;
    private static final int ALIGN_HEIGHT = 16;

    private final Logger logger = LoggerFactory.getLogger(Camera.class);

    private final CallbackThreadInitializer callbackThreadInitializer = new CallbackThreadInitializer(true, false, "MMALCallback");

    private final CameraControlCallback cameraControlCallback = new CameraControlCallback();

    private final CameraConfiguration configuration;

    private final Semaphore checkSemaphore = new Semaphore(1);

    private MMAL_COMPONENT_T encoderComponent;

    private MMAL_PORT_T encoderInputPort;

    private MMAL_PORT_T encoderOutputPort;

    private MMAL_POOL_T picturePool;

    private MMAL_COMPONENT_T cameraComponent;

    private MMAL_PORT_T cameraVideoPort;

    private MMAL_PORT_T cameraCapturePort;

    private Pointer cameraEncoderConnection;

    private EncoderBufferCallback encoderBufferCallback;

    private EncoderVideoBufferCallback encoderVideoBufferCallback;

    public Camera(CameraConfiguration configuration) {
        logger.debug("Camera(configuration={})", configuration);

        this.configuration = configuration;

        createEncoder();
        createCamera();
        connectCameraToEncoder();
        createPicturePool();
        if (configuration.useVideoMode()) {
            createEncoderVideoBufferCallback();
        } else {
            createEncoderBufferCallback();
        }
        enableEncoderOutput();
        sendBuffersToEncoder();
    }

    /**
     * Take a picture.
     * <p>
     * The camera instance is <em>not</em> thread-safe, client applications <em>must</em> ensure that only one thread at
     * a time accesses the camera.
     * <p>
     * If {@link CaptureFailedException} is thrown, the application should close this camera instance and not use it
     * again. If this happens, creating a new camera instance and resuming captures should work.
     *
     * @param pictureCaptureHandler handler used to store the captured image
     * @throws CaptureFailedException if the capture failed for some reason
     */
    public void takePicture(PictureCaptureHandler pictureCaptureHandler) throws CaptureFailedException {
        logger.info(">>> Begin Take Picture >>>");

        if (!checkSemaphore.tryAcquire()) {
            logger.error("Attempt to take picture while camera is already busy processing a capture");
            throw new CaptureFailedException("Camera is already processing a capture");
        }

        try {
            processCapture(pictureCaptureHandler);
        }
        finally {
            checkSemaphore.release();
        }

        logger.info("<<< End Take Picture <<<");
    }

    @Override
    public void close() throws Exception {
        logger.debug("close()");

        if (encoderBufferCallback != null) {
            callbackThreadInitializer.detach(encoderBufferCallback);
            encoderBufferCallback = null;
        }

        if (encoderVideoBufferCallback != null) {
            callbackThreadInitializer.detach(encoderVideoBufferCallback);
            encoderVideoBufferCallback = null;
        }

        disableEncoderOutputPort();

        disableComponent(encoderComponent);
        disableComponent(cameraComponent);

        mmal_port_pool_destroy(encoderOutputPort, picturePool);

        destroyComponent(encoderComponent);
        destroyComponent(cameraComponent);
    }

    private void createEncoder() {
        logger.debug("createEncoder()");

        encoderComponent = createComponent(MMAL_COMPONENT_DEFAULT_IMAGE_ENCODER);

        encoderInputPort = getPort(encoderComponent.input.getPointer(0));
        logger.trace("encoderInputPort={}", encoderInputPort);

        encoderOutputPort = getPort(encoderComponent.output.getPointer(0));
        logger.trace("encoderOutputPort={}", encoderOutputPort);

        mmal_format_copy(encoderOutputPort.format, encoderInputPort.format);

        encoderOutputPort.format.encoding = configuration.encoding().value();
        encoderOutputPort.buffer_size = Math.max(encoderOutputPort.buffer_size_recommended, encoderOutputPort.buffer_size_min);
        encoderOutputPort.buffer_num = Math.max(encoderOutputPort.buffer_num_recommended, encoderOutputPort.buffer_num_min);
        encoderOutputPort.write();

        logger.trace("encoderOutputPort={}", encoderOutputPort);

        if (mmal_port_format_commit(encoderOutputPort) != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to commit encoder output port format");
        }

        if (configuration.quality() != null) {
            mmal_port_parameter_set_uint32(encoderOutputPort, MMAL_PARAMETER_JPEG_Q_FACTOR, configuration.quality());
        }

        enableComponent(encoderComponent);
    }

    private void createPicturePool() {
        logger.debug("createPicturePool()");

        picturePool = mmal_port_pool_create(encoderOutputPort, encoderOutputPort.buffer_num, encoderOutputPort.buffer_size);

        logger.trace("picturePool={}", picturePool);

        if (picturePool == null) {
            throw new RuntimeException("Failed to create encoder picture pool");
        }
    }

    private void createCamera() {
        logger.info("createCamera(), WxH={}x{}", configuration.width(), configuration.height());

        cameraComponent = createComponent(MMAL_COMPONENT_DEFAULT_CAMERA);

        setStereoscopicMode(cameraComponent, configuration.stereoscopicMode(), configuration.decimate(), configuration.swapEyes());

        mmal_port_parameter_set_int32(cameraComponent.control, MMAL_PARAMETER_CAMERA_NUM, 0);
        mmal_port_parameter_set_uint32(cameraComponent.control, MMAL_PARAMETER_CAMERA_CUSTOM_SENSOR_CONFIG, 0);

        Pointer[] pOutputs = cameraComponent.output.getPointerArray(0, cameraComponent.output_num);
        cameraCapturePort = new MMAL_PORT_T(pOutputs[MMAL_CAMERA_CAPTURE_PORT]);
        cameraCapturePort.read();
        cameraVideoPort = new MMAL_PORT_T(pOutputs[MMAL_CAMERA_VIDEO_PORT]);
        cameraVideoPort.read();
        logger.trace("cameraCapturePort={}", cameraCapturePort);
        logger.trace("cameraVideoPort={}", cameraVideoPort);

        mmal_port_enable(cameraComponent.control, cameraControlCallback);

        applyCameraControlConfiguration();
        applyCameraConfiguration();
        applyCameraCapturePortFormat();

        enableComponent(cameraComponent);
    }

    private void applyCameraControlConfiguration() {
        logger.debug("applyCameraControlConfiguration()");

        MMAL_PARAMETER_CAMERA_CONFIG_T config = new MMAL_PARAMETER_CAMERA_CONFIG_T();
        config.max_stills_w = configuration.width();
        config.max_stills_h = configuration.height();
        config.stills_yuv422 = 0;
        config.one_shot_stills = configuration.useVideoMode() ? 0 : 1;
        // Preview configuration must be set to something reasonable, even though preview is not used
        config.max_preview_video_w = configuration.width();
        config.max_preview_video_h = configuration.height();
        config.num_preview_video_frames = 3;
        config.stills_capture_circular_buffer_height = 0;
        config.fast_preview_resume = 0;
        config.use_stc_timestamp = MMAL_PARAM_TIMESTAMP_MODE_RESET_STC;

        logger.trace("config={}", config);

        int result = MmalParameterUtils.mmal_port_parameter_set(cameraComponent.control, config);
        logger.debug("result={}", result);

        if (result != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to set camera control port configuration");
        }
    }

    private void applyCameraConfiguration() {
        logger.debug("applyCameraConfiguration()");

        setBrightness(cameraComponent, configuration.brightness());
        setContrast(cameraComponent, configuration.contrast());
        setSaturation(cameraComponent, configuration.saturation());
        setSharpness(cameraComponent, configuration.sharpness());
        setVideoStabilisation(cameraComponent, configuration.videoStabilisation());
        setShutterSpeed(cameraComponent, configuration.shutterSpeed());
        setIso(cameraComponent, configuration.iso());
        setExposureMode(cameraComponent, configuration.exposureMode());
        setExposureMeteringMode(cameraComponent, configuration.exposureMeteringMode());
        setExposureCompensation(cameraComponent, configuration.exposureCompensation());
        setDynamicRangeCompressionStrength(cameraComponent, configuration.dynamicRangeCompressionStrength());
        setAutomaticWhiteBalanceMode(cameraComponent, configuration.automaticWhiteBalanceMode());
        setAutomaticWhiteBalanceGains(cameraComponent, configuration.automaticWhiteBalanceRedGain(), configuration.automaticWhiteBalanceBlueGain());
        setImageEffect(cameraComponent, configuration.imageEffect());
        setColourEffect(cameraComponent, configuration.colourEffect(), configuration.u(), configuration.v());
        setMirror(cameraComponent, configuration.mirror());
        setRotation(cameraComponent, configuration.rotation());
        setCrop(cameraComponent, configuration.crop());
    }

    private void applyCameraCapturePortFormat() {
        logger.debug("applyCameraCapturePortFormat()");

        int result;

        if (configuration.shutterSpeed() != null) {
            if (configuration.shutterSpeed() > 6000000) {
                setFpsRange(cameraComponent, 50, 1000, 166, 1000);
            } else if (configuration.shutterSpeed() > 1000000) {
                setFpsRange(cameraComponent, 167, 1000, 999, 1000);
            }
        }

        if (configuration.useVideoMode()) {
            logger.info("Configure video port");
            // Set the encode format on the video port
            cameraVideoPort.format.encoding_variant = I420.value();
            cameraVideoPort.format.encoding = I420.value();
            cameraVideoPort.format.es.video.width = configuration.width();
            cameraVideoPort.format.es.video.height = configuration.height();
            cameraVideoPort.format.es.video.crop.x = 0;
            cameraVideoPort.format.es.video.crop.y = 0;
            cameraVideoPort.format.es.video.crop.width = configuration.width();
            cameraVideoPort.format.es.video.crop.height = configuration.height();
            cameraVideoPort.format.es.video.frame_rate.num = configuration.fps();
            cameraVideoPort.format.es.video.frame_rate.den = 1;

            cameraVideoPort.format.es.setType(MMAL_VIDEO_FORMAT_T.class);
            cameraVideoPort.write();

            result = mmal_port_format_commit(cameraVideoPort);
            logger.info("cameraVideoPort commit result={}", result);
            if (result != MMAL_SUCCESS) {
                throw new RuntimeException("Failed to commit camera capture port format");
            }

            // Ensure there are enough buffers to avoid dropping frames
            if (cameraVideoPort.buffer_num < VIDEO_OUTPUT_BUFFERS_NUM) {
                logger.info("Enlarge video buffer count from {} to {}", cameraVideoPort.buffer_num, VIDEO_OUTPUT_BUFFERS_NUM);
                cameraVideoPort.buffer_num = VIDEO_OUTPUT_BUFFERS_NUM;
            }
        }

        logger.info("Configure capture port");
        cameraCapturePort.format.encoding = OPAQUE.value();
        cameraCapturePort.format.es.video.width = alignUp(configuration.width(), ALIGN_WIDTH);
        cameraCapturePort.format.es.video.height = alignUp(configuration.height(), ALIGN_HEIGHT);
        cameraCapturePort.format.es.video.crop.x = 0;
        cameraCapturePort.format.es.video.crop.y = 0;
        cameraCapturePort.format.es.video.crop.width = configuration.width();
        cameraCapturePort.format.es.video.crop.height = configuration.height();
        cameraCapturePort.format.es.video.frame_rate.num = STILLS_FRAME_RATE_NUM;
        cameraCapturePort.format.es.video.frame_rate.den = STILLS_FRAME_RATE_DEN;

        cameraCapturePort.format.es.setType(MMAL_VIDEO_FORMAT_T.class);
        cameraCapturePort.write();

        logger.trace("format={}", cameraCapturePort.format.es.video);

        result = mmal_port_format_commit(cameraCapturePort);
        logger.debug("cameraCapturePort commit result ={}", result);

        if (result != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to commit camera capture port format");
        }

        /* Ensure there are enough buffers to avoid dropping frames */
        if (cameraCapturePort.buffer_num < VIDEO_OUTPUT_BUFFERS_NUM) {
            logger.debug("Enlarge capture buffer count from {} to {}", cameraCapturePort.buffer_num, VIDEO_OUTPUT_BUFFERS_NUM);
            cameraCapturePort.buffer_num = VIDEO_OUTPUT_BUFFERS_NUM;
        }
    }

    private void connectCameraToEncoder() {
        logger.debug("connectCameraToEncoder()");

        PointerByReference pConnection = new PointerByReference();

        if (configuration.useVideoMode()) {
            connectPorts(cameraVideoPort, encoderInputPort, pConnection);
        } else {
            connectPorts(cameraCapturePort, encoderInputPort, pConnection);
        }

        cameraEncoderConnection = pConnection.getValue();

        logger.info("cameraEncoderConnection={}", cameraEncoderConnection);
    }

    private void createEncoderBufferCallback() {
        logger.debug("createEncoderBufferCallback()");

        encoderBufferCallback = new EncoderBufferCallback(picturePool);

        Native.setCallbackThreadInitializer(encoderBufferCallback, callbackThreadInitializer);
    }

    private void createEncoderVideoBufferCallback() {
        if (encoderVideoBufferCallback == null) {
            logger.debug("createEncoderVideoBufferCallback()");
            encoderVideoBufferCallback = new EncoderVideoBufferCallback(picturePool);

            Native.setCallbackThreadInitializer(encoderVideoBufferCallback, callbackThreadInitializer);
        }
    }

    private void enableEncoderOutput() {
        logger.debug("enableEncoderOutput()");

        int result;
        if (configuration.useVideoMode()) {
            result = mmal_port_enable(encoderOutputPort, encoderVideoBufferCallback);
        } else {
            result = mmal_port_enable(encoderOutputPort, encoderBufferCallback);
        }
        logger.debug("result={}", result);

        if (result != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to enable encoder output port");
        }
    }

    private void sendBuffersToEncoder() {
        logger.debug("sendBuffersToEncoder()");

        int bufferCount = mmal_queue_length(picturePool.queue);
        logger.debug("bufferCount={}", bufferCount);

        for (int i = 0; i < bufferCount; i++) {
            MMAL_BUFFER_HEADER_T buffer = mmal_queue_get(picturePool.queue);
            logger.trace("buffer={}", buffer);

            if (buffer == null) {
                throw new RuntimeException(String.format("Failed to get buffer %d from queue", i));
            }

            int result = mmal_port_send_buffer(encoderOutputPort.getPointer(), buffer.getPointer());
            logger.debug("result={}", result);

            if (result != MMAL_SUCCESS) {
                throw new RuntimeException(String.format("Failed to send buffer %d to encoder output port", i));
            }
        }
    }

    private void processCapture(PictureCaptureHandler<?> pictureCaptureHandler) throws CaptureFailedException {
        logger.debug("processCapture()");

        try {
            logger.info("Preparing to capture...");

            Integer delay = configuration.delay();
            logger.debug("delay={}", delay);
            if (delay != null && delay > 0) {
                try {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e) {
                    logger.error("Interrupted while waiting before capture", e);
                    throw new CaptureFailedException("Interrupted while waiting before capture", e);
                }
            }

            try {
                pictureCaptureHandler.begin();
            }
            catch (Exception e) {
                logger.error("Picture capture handler failed to begin", e);
                throw new CaptureFailedException("Picture capture handler failed to begin", e);
            }

            encoderBufferCallback.setPictureCaptureHandler(pictureCaptureHandler);

            startCapture();

            logger.debug("wait for capture to complete");
            encoderBufferCallback.waitForCaptureToFinish(configuration.captureTimeout());
            logger.info("Capture completed");
        }
        catch (CaptureTimeoutException | InterruptedException e) {
            throw new CaptureFailedException(e);
        }
        finally {
            try {
                pictureCaptureHandler.end();
            }
            catch (Exception e) {
                logger.error("Callback failure after capture finished", e);
            }

            encoderBufferCallback.setPictureCaptureHandler(null);
        }
    }

    private void processVideoCapture(VideoCaptureHandler handler) throws CaptureFailedException {
        logger.debug("processVideoCapture()");

        Integer delay = configuration.delay();
        logger.debug("delay={}", delay);
        if (delay != null && delay > 0) {
            try {
                Thread.sleep(delay);
            }
            catch (InterruptedException e) {
                logger.error("Interrupted while waiting before capture", e);
                throw new CaptureFailedException("Interrupted while waiting before capture", e);
            }
        }

        startCapture();

        boolean captureNext = true;

        // send first frame immediately
        long frameNumber = encoderVideoBufferCallback.getFrameNumber();
        byte[] data = encoderVideoBufferCallback.getData();
        if (data != null) {
            captureNext = handler.frameReady(data, frameNumber);
        }

        long lastFrameTime = System.currentTimeMillis();
        long lastFrame = encoderVideoBufferCallback.getFrameNumber();
        while (captureNext) {

            //throw an exception if camera freezes
            if (configuration.captureTimeout() != null && (configuration.captureTimeout() > 0)
                    && (System.currentTimeMillis() - lastFrameTime > configuration.captureTimeout())) {
                throw new CaptureFailedException("Failed to obtain next image in given time");
            }

            // wait for next frame
            if (lastFrame < encoderVideoBufferCallback.getFrameNumber()) {
                lastFrameTime = System.currentTimeMillis();
                lastFrame = encoderVideoBufferCallback.getFrameNumber();
                captureNext = handler.frameReady(encoderVideoBufferCallback.getData(), lastFrame);
            }

            // have a nap
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {}
        }
    }

    private void startCapture() throws CaptureFailedException {
        logger.debug("startCapture()");

        int result;
        if (configuration.useVideoMode()) {
            result = mmal_port_parameter_set_boolean(cameraVideoPort, MMAL_PARAMETER_CAPTURE, 1);
        } else {
            result = mmal_port_parameter_set_boolean(cameraCapturePort, MMAL_PARAMETER_CAPTURE, 1);
        }

        logger.debug("result={}", result);

        if (result != MMAL_SUCCESS) {
            throw new CaptureFailedException("Failed to start capture");
        }

        logger.info("Capture started");
    }

    private void disableEncoderOutputPort() {
        logger.debug("disableEncoderOutputPort()");

        disablePort(encoderOutputPort);
    }

    public void capture(VideoCaptureHandler handler) throws Exception {
        if (!configuration.useVideoMode()) {
            throw new Exception("Capture method can be used in video mode only. Configure camera to useVideoMode(true)");
        }

        logger.info(">>> Begin capture video >>>");

        processVideoCapture(handler);

        logger.info("<<< End capture video <<<");

    }
}
