Place the generated frplib AAR files here.

Expected layout:

```text
app/libs/universal/frplib-universal.aar
app/libs/arm64-v8a/frplib-arm64-v8a.aar
app/libs/armeabi-v7a/frplib-armeabi-v7a.aar
app/libs/x86_64/frplib-x86_64.aar
```

The CI workflow downloads these files automatically from the latest `frplib` release.
