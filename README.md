# Photo companion demo

A simple demo app that loads a random photo from an Android phone
running the example service and displays it on a Pebble Time.

This includes some useful utility classes which could be used in your
own projects:

- file_receiver.c: assembles downloaded pieces of a file into a buffer
- CompanionService: handles incoming Pebble messages in the background
- OutboxManager: can be used as part of CompanionService or standalone
  to reliably send messages to the Pebble, including automatic
  retries.
- SimpleImageEncoder: handles dithering to a minimal subset of the
  Pebble Time colors to reduce file sizes.

# Setup

This assumes basic familiarity with the Pebble SDK and Android app development.

- Install latest Pebble SDK
- Install Android Studio and the Android SDKs
- Enable developer mode in the Pebble Android app settings
- Enable developer connection in the Developer tab and write down the IP address
- Open android-companion project and install on your phone
- Compile pebble app using "pebble build" and install with
  "pebble install --phone <your phone's ip>"

# Credits

* PNG API for Pebble from https://github.com/pebble-examples/pebble-faces example
* uPNG code by Lode Vandevenne, Sean Middleditch
* PNGJ encoder library for Java
* Pebble Technology!

# License

This code is free to use per the terms of the individual files. See COPYING.md
for more details.
