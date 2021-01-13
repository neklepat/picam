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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

public class ByteArrayPictureCaptureHandler implements PictureCaptureHandler<byte[]> {

    private final Logger logger = LoggerFactory.getLogger(ByteArrayPictureCaptureHandler.class);

    private final Integer initialSize;

    private ByteArrayOutputStream out;

    public ByteArrayPictureCaptureHandler() {
        this.initialSize = null;
    }

    public ByteArrayPictureCaptureHandler(int initialSize) {
        this.initialSize = initialSize;
    }

    @Override
    public void begin() throws Exception {
        logger.debug("Begin handler {}", this.hashCode());
        out = initialSize != null ? new ByteArrayOutputStream(initialSize) : new ByteArrayOutputStream();
    }

    @Override
    public void pictureData(byte[] data) throws Exception {
        logger.debug("Writing picture data to {}", this.hashCode());
        out.write(data);
    }

    @Override
    public void end() throws Exception {
        logger.debug("End handler {}", this.hashCode());
    }

    @Override
    public byte[] result() {
        logger.debug("Return handler {} result", this.hashCode());
        return out.toByteArray();
    }

}
