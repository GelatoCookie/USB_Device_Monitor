# Zebra RFID SDK libraries

Place the **Zebra RFID API3 SDK** binaries here so the app can compile and connect
to the RFD40 sled.

Required (file names may vary by SDK release):

- `API3_LIB-release.aar`  (Zebra RFID API3)
- any companion `.jar` / `.aar` files shipped in the same SDK bundle

Where to get them:

- Zebra RFID SDK for Android (developer.zebra.com), or
- Copy the `app/libs/*.aar` files from the reference project
  `GelatoCookie/TC53eBspTest`.

`app/build.gradle` already pulls everything in this folder via:

```gradle
implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])
```

Until these binaries are present, classes under `com.zebra.rfid.api3.*`
(used by `RFIDHandler.java`) will not resolve and the project will not build.
