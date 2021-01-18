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
import uk.co.caprica.picam.handlers.PictureCaptureHandler;
import uk.co.caprica.picam.handlers.VideoCaptureHandler;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static uk.co.caprica.picam.bindings.LibMmal.*;
import static uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_FLAG.MMAL_BUFFER_HEADER_FLAG_FRAME_END;
import static uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_FLAG.MMAL_BUFFER_HEADER_FLAG_TRANSMISSION_FAILED;
import static uk.co.caprica.picam.bindings.internal.MMAL_STATUS_T.MMAL_SUCCESS;

class EncoderVideoBufferCallback implements MMAL_PORT_BH_CB_T {

    private final Logger logger = LoggerFactory.getLogger(EncoderVideoBufferCallback.class);

    private final MMAL_POOL_T picturePool;

    private AtomicReference<VideoCaptureHandler> captureHandler = new AtomicReference();

    private ByteArrayOutputStream workingBuffer = new ByteArrayOutputStream();

    private AtomicReference<ByteArrayOutputStream> readyBuffer = new AtomicReference<>(null);

    private AtomicLong frameCounter = new AtomicLong(0);

    EncoderVideoBufferCallback(MMAL_POOL_T picturePool) {
        this.picturePool = picturePool;

        // create image buffers
        workingBuffer = new ByteArrayOutputStream();
        readyBuffer = new AtomicReference<>(null);

        logger.info("EncoderVideoBufferCallback created {}", this);
    }

    private void flipBuffers() {
        readyBuffer.set(workingBuffer);
        workingBuffer = new ByteArrayOutputStream();
        frameCounter.getAndIncrement();
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
                workingBuffer.write(buffer.data.getByteArray(buffer.offset, bufferLength));
            }

            if ((flags & (MMAL_BUFFER_HEADER_FLAG_TRANSMISSION_FAILED)) != 0) {
                logger.info("buffer transmission failed");
            } else
            if ((flags & MMAL_BUFFER_HEADER_FLAG_FRAME_END ) != 0) {
                flipBuffers();
            }
        } catch (Exception e) {
            logger.error("Error in callback handling picture data", e);
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

    public byte[] getData() {
        if (readyBuffer.get() != null) {
            return readyBuffer.get().toByteArray();
        } else {
            return null;
        }
    }

    public long getFrameNumber() {
        return frameCounter.get();
    }
}
