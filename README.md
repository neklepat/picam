picam
=====

An easy-to-use Open Source Java library to access the [Raspberry Pi](https://www.raspberrypi.org/)
[camera module](https://www.raspberrypi.org/products/camera-module).

This library provides a direct Java API to the camera - behind the scenes the *native* MMAL
library is used.

This library does *not* require any external native processes nor does it wrap
any native executable program.

The implementation is based loosely on that used by the native `RaspiStill`
utility.

This project is unofficial and is not affiliated in any way with the Raspberry
Pi Foundation.

Installation
------------

Add the following Maven dependency to your project:

    <dependencies>
        <dependency>
            <groupId>uk.co.caprica</groupId>
            <artifactId>picam</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>

Basic Usage
-----------

More detailed installation and usage instructions will be provided soon.

Using the library is simple, essentially:

```
CameraConfiguration config = cameraConfiguration()
    .width(1920)
    .height(1080);

try (Camera camera = new Camera(config)) {
    camera.takePicture(new FilePictureCaptureHandler(new File("picam.png")));
}
```

Captured images can be directly saved to disk, or returned and processed as a
`byte[]`.

Status
------

The current API should be considered alpha, it is stable but is still subject to change.

Feedback is welcome at the [github project](https://github.com/caprica/picam).

Not all camera features or effects are implemented, the aim is to add more
support over time:

- [x] image width/height
- [ ] image encoding (hard-coded to PNG currently)
- [ ] stereo mode
- [x] brightness
- [x] contrast
- [x] saturation
- [x] sharpness
- [x] video stabilisation
- [ ] shutter speed
- [x] ISO
- [x] exposure mode
- [x] exposure metering mode
- [x] exposure compensation
- [x] dynamic range compression strength
- [x] automatic white balance modes
- [ ] automatic white balance red/blue gain
- [-] image effects (most, but not all, are working)
- [ ] image effect parameters
- [ ] colour effects
- [x] flip horizontally/vertically
- [x] rotation (90 degree steps)
- [ ] crop (region of interest)
- [ ] burst capture mode
- [ ] EXIF tags
- [x] initial capture delay (time for the sensor to 'settle')
- [ ] raw capture

Trademark Acknowledgement
-------------------------

Raspberry Pi is a trademark of the Raspberry Pi Foundation.

Demo Application
----------------

This screen-shot shows a Java web application running on the Pi:

![picam-demo](https://github.com/caprica/picam/raw/master/etc/demo.png "picam-demo")
