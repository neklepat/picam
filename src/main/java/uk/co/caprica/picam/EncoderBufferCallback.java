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

import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_T;
import uk.co.caprica.picam.bindings.internal.MMAL_POOL_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PORT_BH_CB_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PORT_T;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static uk.co.caprica.picam.bindings.LibMmal.mmal_buffer_header_mem_lock;
import static uk.co.caprica.picam.bindings.LibMmal.mmal_buffer_header_mem_unlock;
import static uk.co.caprica.picam.bindings.LibMmal.mmal_buffer_header_release;
import static uk.co.caprica.picam.bindings.LibMmal.mmal_port_send_buffer;
import static uk.co.caprica.picam.bindings.LibMmal.mmal_queue_get;
import static uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_FLAG.*;
import static uk.co.caprica.picam.bindings.internal.MMAL_STATUS_T.MMAL_SUCCESS;

class EncoderBufferCallback implements MMAL_PORT_BH_CB_T {

    private final Logger logger = LoggerFactory.getLogger(EncoderBufferCallback.class);

    private Semaphore captureFinishedSemaphore;

    private final MMAL_POOL_T picturePool;

    private boolean frameStarted = true;

    private AtomicReference<PictureCaptureHandler> pictureCaptureHandler = new AtomicReference();

    EncoderBufferCallback(MMAL_POOL_T picturePool) {
        this.picturePool = picturePool;
    }

    void setPictureCaptureHandler(PictureCaptureHandler pictureCaptureHandler) {
        this.pictureCaptureHandler.set(pictureCaptureHandler);
    }

    void waitForCaptureToFinish(int captureTimeout) throws InterruptedException, CaptureTimeoutException {
        captureFinishedSemaphore = new Semaphore(0);
        if (captureTimeout >= 0) {
            boolean acquired = captureFinishedSemaphore.tryAcquire(captureTimeout, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new CaptureTimeoutException();
            }
        } else {
            captureFinishedSemaphore.acquire();
        }
    }

    @Override
    public void apply(Pointer pPort, Pointer pBuffer) {
        logger.debug("apply()");

        logger.trace("port={}", pPort);
        logger.trace("buffer={}", pBuffer);

        boolean finished = false;

        // Lock the native buffer before accessing any of its contents
        mmal_buffer_header_mem_lock(pBuffer);

        try {
            MMAL_BUFFER_HEADER_T buffer = new MMAL_BUFFER_HEADER_T(pBuffer);
            buffer.read();

            int bufferLength = buffer.length;
            logger.debug("bufferLength={}", bufferLength);

            int flags = buffer.flags;
            logger.debug("flags={}", flags);

            if ( bufferLength > 0) {
                byte[] data = buffer.data.getByteArray(buffer.offset, bufferLength);
                PictureCaptureHandler handler = pictureCaptureHandler.get();
                if (frameStarted) {
                    if (handler != null) {
                        handler.pictureData(data);
                    } else {
                        frameStarted = false;
                    }
                }
            }

            if (frameStarted && (flags & (MMAL_BUFFER_HEADER_FLAG_FRAME_END | MMAL_BUFFER_HEADER_FLAG_TRANSMISSION_FAILED)) != 0) {
                finished = true;
                frameStarted = false;
                logger.info("set finished = true");
            } else
            if ((flags & MMAL_BUFFER_HEADER_FLAG_FRAME_END ) != 0) {
                frameStarted = pictureCaptureHandler.get() != null;
                logger.trace("set frameStarted = {}", frameStarted);
            }
        } catch (Exception e) {
            logger.error("Error in callback handling picture data", e);
            finished = true;
        } finally {
            // Whatever happened, unlock the native buffer
            mmal_buffer_header_mem_unlock(pBuffer);
        }

        mmal_buffer_header_release(pBuffer);

        MMAL_PORT_T port = new MMAL_PORT_T(pPort);
        port.read();

        if (port.isEnabled()) {
            sendNextPictureBuffer(port);
        }

        logger.debug("finished={}", finished);

        if (finished) {
            logger.debug("signal capture complete");
            captureFinishedSemaphore.release();
        }
    }

    private void sendNextPictureBuffer(MMAL_PORT_T port) {
        logger.debug("sendNextPictureBuffer()");

        MMAL_BUFFER_HEADER_T nextBuffer = mmal_queue_get(picturePool.queue);
        logger.trace("nextBuffer={}", nextBuffer);

        if (nextBuffer == null) {
            throw new RuntimeException("Failed to get next buffer from picture pool");
        }

        int result = mmal_port_send_buffer(port.getPointer(), nextBuffer.getPointer());
        logger.debug("result={}", result);

        if (result != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to send next picture buffer to encoder");
        }
    }
}
