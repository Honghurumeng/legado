# Legado 本地 Release 签名记录

记录时间：2026-05-06 12:58:44 CST

## APK 产物

- Release APK：`/Users/rel001/Documents/GitHub/legado/app/build/outputs/apk/app/release/legado_app_3.26.050611.apk`
- APK 大小：约 `15M`
- idsig 文件：`/Users/rel001/Documents/GitHub/legado/app/build/outputs/apk/app/release/legado_app_3.26.050611.apk.idsig`

## Keystore

- Keystore 路径：`/Users/rel001/Documents/GitHub/legado/release/legado-local-release.jks`
- Keystore 文件大小：约 `2.8K`
- Alias：`legado-local-release`
- Store password：`LegadoLocalRelease2026!`
- Key password：`LegadoLocalRelease2026!`
- Key algorithm：`RSA`
- Key size：`2048`
- Validity：`10000` 天
- 证书 DN：`CN=Legado Local Release, OU=Local Build, O=Legado, L=Shanghai, ST=Shanghai, C=CN`

## 签名校验

校验命令：

```bash
/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/apksigner verify --verbose app/build/outputs/apk/app/release/legado_app_3.26.050611.apk
```

校验结果：

```text
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```

## 重新打包命令

在项目根目录 `/Users/rel001/Documents/GitHub/legado` 执行：

```bash
./gradlew :app:assembleAppRelease --rerun-tasks \
  -PRELEASE_STORE_FILE=../release/legado-local-release.jks \
  -PRELEASE_STORE_PASSWORD='LegadoLocalRelease2026!' \
  -PRELEASE_KEY_ALIAS=legado-local-release \
  -PRELEASE_KEY_PASSWORD='LegadoLocalRelease2026!'
```

## 安装与升级注意事项

- 这个 release 包的 applicationId 是 `io.legadoai.app`。
- 后续如果要覆盖安装升级，必须继续使用同一个 keystore：`release/legado-local-release.jks`。
- 如果设备上已经安装过同包名但签名不同的旧 release 包，需要先卸载旧包再安装这个包。
- `release/` 目录已在项目 `.gitignore` 中，不会被 git 默认提交。
- 请妥善保存 `release/legado-local-release.jks` 和上面的密码；丢失后无法再为同一安装包签出可覆盖升级的 release APK。
